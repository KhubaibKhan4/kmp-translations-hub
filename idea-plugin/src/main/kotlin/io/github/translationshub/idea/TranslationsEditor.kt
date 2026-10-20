package io.github.translationshub.idea

import com.intellij.openapi.command.undo.DocumentReference
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.DocumentReferenceProvider
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import io.github.translationshub.core.Checks
import io.github.translationshub.core.Locale
import io.github.translationshub.core.ResourceEntry
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.core.TranslationSet
import java.awt.BorderLayout
import java.awt.Component
import java.beans.PropertyChangeListener
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable

/**
 * The "Translations" tab of an XML file in `composeResources/values…`: every key of that `composeResources` folder
 * against every locale. Edits are written through [StringsXmlWriter] as one command each, and Undo in this tab
 * undoes them, whichever locale file they went to.
 */
class TranslationsEditor(private val project: Project, private val file: VirtualFile, private val root: VirtualFile) :
    UserDataHolderBase(), FileEditor, DocumentReferenceProvider {

    val model = TranslationsTableModel(ResourceFolders.read(root), ::write)
    val table = JBTable(model)
    private val summary = JBLabel()
    private val panel = JPanel(BorderLayout())
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        table.setDefaultRenderer(Any::class.java, CellRenderer())
        table.setDefaultEditor(Any::class.java, CellEditor())
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setShowGrid(true)
        table.autoCreateRowSorter = true
        layOutColumns()
        summary.border = JBUI.Borders.empty(6, 8)
        panel.add(summary, BorderLayout.NORTH)
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        updateSummary()

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val changed = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (ResourceFolders.rootOf(changed) == root) scheduleReload()
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.startsWith(root.path + "/") }) scheduleReload()
            }
        })
    }

    fun reload() {
        if (!root.isValid) return
        val columnsBefore = model.columnCount
        model.update(ResourceFolders.read(root))
        if (model.columnCount != columnsBefore) layOutColumns()
        updateSummary()
    }

    private fun scheduleReload() {
        reloadAlarm.cancelAllRequests()
        reloadAlarm.addRequest(::reload, 200)
    }

    private fun layOutColumns() {
        val columns = table.columnModel
        for (i in 0 until columns.columnCount) columns.getColumn(i).preferredWidth = JBUI.scale(if (i == 0) 220 else 260)
    }

    private fun updateSummary() {
        val set = model.set
        val problems = Checks.run(set)
        summary.text = "${set.keys.size} keys · ${set.locales.size} locales · " +
            if (problems.isEmpty()) "no problems" else "${problems.size} problems"
    }

    /** Writes a `<string>` cell into the locale's file, after the key it follows in the source so the order matches. */
    private fun write(key: ResourceKey, locale: Locale?, text: String) {
        val set = model.set
        val target = targetFile(set, key, locale) ?: return
        val psi = PsiManager.getInstance(project).findFile(target) as? XmlFile ?: return
        val after = set.keys.subList(0, set.keys.indexOf(key)).asReversed().firstOrNull { previous ->
            (if (locale == null) set.sourceFile(previous) else set.translationFile(previous, locale))?.path == target.path
        }
        TranslationsWriteCommand.run(project, "Edit ${key.name} (${locale?.tag ?: "default"})", listOf(psi)) {
            StringsXmlWriter.setText(psi, key.name, text, after)
        }
        reload()
    }

    /**
     * The file a key's cell is written to: the one that already has it, else the locale's file named like the source
     * file. Phase 0 doesn't create locale folders yet (Phase 1: "Add locale").
     */
    private fun targetFile(set: TranslationSet, key: ResourceKey, locale: Locale?): VirtualFile? {
        val path = if (locale == null) set.sourceFile(key)?.path else {
            set.translationFile(key, locale)?.path ?: run {
                val sourceName = set.sourceFile(key)?.fileName
                val files = set.filesFor(locale)
                (files.firstOrNull { it.fileName == sourceName } ?: files.firstOrNull())?.path
            }
        }
        return path?.let(root.fileSystem::findFileByPath)
    }

    override fun getDocumentReferences(): Collection<DocumentReference> {
        val documents = FileDocumentManager.getInstance()
        val references = DocumentReferenceManager.getInstance()
        return ResourceFolders.xmlFiles(root).mapNotNull(documents::getDocument).map(references::create)
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = table
    override fun getName() = "Translations"
    override fun getFile() = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = file.isValid && root.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() {}

    private inner class CellRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val modelRow = table.convertRowIndexToModel(row)
            val modelColumn = table.convertColumnIndexToModel(column)
            val key = model.key(modelRow)
            when {
                value is ResourceKey -> {
                    append(key.name)
                    if (key.kind != ResourceKind.STRING) append("  ${key.kind.xmlTag}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                value is ResourceEntry -> {
                    append(TranslationsTableModel.display(value).replace('\n', '⏎'))
                    if (value.value !is TextValue) append("  ${value.value.texts.size} items", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                modelColumn >= TranslationsTableModel.FIRST_LOCALE_COLUMN && model.set.source(key)?.translatable != false -> {
                    append("missing", SimpleTextAttributes.ERROR_ATTRIBUTES)
                    if (!selected) background = MISSING_BACKGROUND
                }
            }
        }
    }

    private inner class CellEditor : DefaultCellEditor(JBTextField()) {
        override fun getTableCellEditorComponent(table: JTable, value: Any?, selected: Boolean, row: Int, column: Int): Component =
            super.getTableCellEditorComponent(table, TranslationsTableModel.display(value as? ResourceEntry), selected, row, column)
    }

    private companion object {
        val MISSING_BACKGROUND = JBColor(0xFDECEC, 0x4A2B2B)
    }
}
