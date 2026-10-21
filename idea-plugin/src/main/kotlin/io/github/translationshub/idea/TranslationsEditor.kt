package io.github.translationshub.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
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
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Problem
import io.github.translationshub.core.ResourceEntry
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.core.TranslationState
import java.awt.BorderLayout
import java.awt.Component
import java.beans.PropertyChangeListener
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent as SwingDocumentEvent
import javax.swing.table.TableRowSorter

/**
 * The "Translations" tab of an XML file in `composeResources/values…`: every key of that `composeResources` folder
 * against every locale, with search, filters, problem markers and a side panel for plurals and arrays. All writes go
 * through [TranslationsController], one command each, and Undo in this tab undoes them, whichever file they went to.
 */
class TranslationsEditor(private val project: Project, private val file: VirtualFile, private val root: VirtualFile) :
    UserDataHolderBase(), FileEditor, DocumentReferenceProvider {

    enum class Filter(val label: String) { ALL("All keys"), MISSING("Missing translations"), PROBLEMS("With problems"), STALE("Outdated") }

    val controller = TranslationsController(project, root)
    val model = TranslationsTableModel(controller.read(), controller.lock(), { CheckSettings.options(project) }) { key, locale, text ->
        edit { controller.setValue(key, locale, TextValue(text)) }
    }
    val table = JBTable(model)
    val detail = CellDetailPanel({ ProLicense.isLicensed() }) { cell, value -> edit { controller.setValue(cell.key, cell.locale, value) } }
    val search = SearchTextField(false)
    val filter = ComboBox(Filter.entries.toTypedArray())
    private val sorter = TableRowSorter(model)
    private val summary = JBLabel()
    private val panel = JPanel(BorderLayout())
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        table.setDefaultRenderer(Any::class.java, CellRenderer())
        table.setDefaultEditor(Any::class.java, CellEditor())
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setShowGrid(true)
        table.rowSorter = sorter
        table.setCellSelectionEnabled(true)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelection() }
        table.columnModel.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelection() }
        layOutColumns()

        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: SwingDocumentEvent) = applyFilter()
        })
        filter.addActionListener { applyFilter() }

        val toolbar = ActionManager.getInstance().createActionToolbar("KmpTranslationsHub", DefaultActionGroup(actions()), true)
        toolbar.targetComponent = table
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(2, 4)
            add(toolbar.component)
            add(search)
            add(filter)
            add(summary.apply { border = JBUI.Borders.emptyLeft(8) })
        }
        val splitter = OnePixelSplitter(false, 0.72f).apply {
            firstComponent = JBScrollPane(table)
            secondComponent = JBScrollPane(detail)
        }
        panel.add(top, BorderLayout.NORTH)
        panel.add(splitter, BorderLayout.CENTER)
        updateSummary()

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val changed = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (ResourceFolders.rootOf(changed) == root || changed == LockFiles.find(root)) scheduleReload()
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.startsWith(root.path + "/") || it.path.startsWith(root.parent.path + "/translations.lock") }) scheduleReload()
            }
        })
    }

    fun reload() {
        if (!root.isValid) return
        val selected = detail.shownCell
        val columnsBefore = model.columnCount
        model.update(controller.read(), controller.lock())
        if (model.columnCount != columnsBefore) layOutColumns()
        updateSummary()
        selected?.let(::select)
    }

    /** Selects a cell (in model terms) and shows it in the side panel. */
    fun select(cell: Cell) {
        val row = model.set.keys.indexOf(cell.key).takeIf { it >= 0 }?.let(table::convertRowIndexToView)?.takeIf { it >= 0 }
        val column = if (cell.locale == null) TranslationsTableModel.SOURCE_COLUMN
        else model.set.locales.indexOf(cell.locale).takeIf { it >= 0 }?.plus(TranslationsTableModel.FIRST_LOCALE_COLUMN)
        if (row == null || column == null) {
            detail.show(model, null)
            return
        }
        table.changeSelection(row, table.convertColumnIndexToView(column), false, false)
        detail.show(model, cell)
    }

    private fun showSelection() {
        val row = table.selectedRow
        val column = table.selectedColumn
        if (row < 0 || column < 0) return detail.show(model, null)
        val modelColumn = table.convertColumnIndexToModel(column)
        val cell = model.cell(table.convertRowIndexToModel(row), modelColumn.coerceAtLeast(TranslationsTableModel.SOURCE_COLUMN))
        if (cell != detail.shownCell) detail.show(model, cell)
    }

    private fun applyFilter() {
        val query = search.text.trim()
        val mode = filter.item ?: Filter.ALL
        sorter.rowFilter = object : RowFilter<TranslationsTableModel, Int>() {
            override fun include(entry: Entry<out TranslationsTableModel, out Int>): Boolean {
                val row = entry.identifier
                val matchesMode = when (mode) {
                    Filter.ALL -> true
                    Filter.MISSING -> model.rowIsMissing(row)
                    Filter.PROBLEMS -> model.rowHasProblems(row)
                    Filter.STALE -> model.rowIsStale(row)
                }
                return matchesMode && (query.isEmpty() || model.searchText(row).contains(query, ignoreCase = true))
            }
        }
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
        val shown = if (table.rowCount != set.keys.size) "${table.rowCount} of " else ""
        val problems = model.problems.size
        val variants = set.variantFiles.map { it.qualifiers.folderName }.distinct()
        summary.text = "$shown${set.keys.size} keys · ${set.locales.size} locales · " +
            (if (problems == 0) "no problems" else "$problems problems") +
            if (variants.isEmpty()) "" else " · ${variants.size} variant folders not shown"
        // Theme and density folders override single keys rather than translate the app; name them on hover.
        summary.toolTipText = variants.takeIf { it.isNotEmpty() }?.joinToString(", ", "Not shown: ")
    }

    /** Runs a change and re-reads; a refused change (e.g. a duplicate key) is shown instead of thrown. */
    private fun edit(change: () -> Unit) {
        try {
            change()
        } catch (e: IllegalArgumentException) {
            Messages.showErrorDialog(project, e.message, "KMP Translations Hub")
        } catch (e: IllegalStateException) {
            Messages.showErrorDialog(project, e.message, "KMP Translations Hub")
        }
        reload()
    }

    private fun selectedKey(): ResourceKey? =
        table.selectedRow.takeIf { it >= 0 }?.let { model.key(table.convertRowIndexToModel(it)) }

    private fun actions(): List<AnAction> = listOf(
        action("Add Key", AllIcons.General.Add) {
            val dialog = AddKeyDialog(project) { name, kind -> model.set.source(ResourceKey(kind, name)) == null }
            if (dialog.showAndGet()) {
                edit { controller.addKey(dialog.keyName, dialog.value) }
                select(Cell(ResourceKey(dialog.kind, dialog.keyName), null))
            }
        },
        action("Rename Key", AllIcons.Actions.Edit, enabled = { selectedKey() != null }) {
            val key = selectedKey() ?: return@action
            val name = Messages.showInputDialog(project, "New name for ${key.name}, in every locale:", "Rename Key", null, key.name,
                KeyNameValidator { model.set.source(key.copy(name = it)) == null })
            if (name != null && name != key.name) edit { controller.renameKey(key, name) }
        },
        action("Delete Key", AllIcons.General.Remove, enabled = { selectedKey() != null }) {
            val key = selectedKey() ?: return@action
            val answer = Messages.showYesNoDialog(project, "Delete ${key.name} from the default values and all ${model.set.locales.size} locales?",
                "Delete Key", Messages.getQuestionIcon())
            if (answer == Messages.YES) edit { controller.deleteKey(key) }
        },
        action("Add Locale", AllIcons.Nodes.PpWeb) {
            val locale = LocaleChooser.choose(project, model.set.locales) ?: return@action
            edit { controller.addLocale(locale) }
        },
        action("Translate Changes…", AllIcons.Actions.Lightning) {
            ProLicense.runOrUpsell(project, "Translate changes") {
                io.github.translationshub.idea.translate.TranslateChanges.start(project, controller) { reload() }
            }
        },
        action("Export…", AllIcons.ToolbarDecorator.Export) {
            ProLicense.runOrUpsell(project, "Export") { ExchangeActions.export(project, model.set) }
        },
        action("Import…", AllIcons.ToolbarDecorator.Import) {
            ProLicense.runOrUpsell(project, "Import") { edit { ExchangeActions.import(project, controller) } }
        },
    )

    private fun action(text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    override fun getDocumentReferences(): Collection<DocumentReference> {
        val documents = FileDocumentManager.getInstance()
        val references = DocumentReferenceManager.getInstance()
        return (ResourceFolders.xmlFiles(root) + listOfNotNull(LockFiles.find(root))).mapNotNull(documents::getDocument).map(references::create)
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
            toolTipText = null
            if (value is ResourceKey) {
                append(key.name)
                if (key.kind != ResourceKind.STRING) append("  ${key.kind.xmlTag}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (model.set.source(key)?.translatable == false) append("  not translated", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                return
            }
            val cell = model.cell(modelRow, modelColumn)
            val problems = model.problems(cell).filterNot { it is Problem.Missing }
            val status = model.status(cell)
            if (value is ResourceEntry) {
                append(TranslationsTableModel.display(value).replace('\n', '⏎'))
                if (value.value !is TextValue) append("  ${value.value.texts.size} items", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                when {
                    status?.needsReview == true -> append("  source changed", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    status?.state == TranslationState.STALE -> append("  outdated", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            } else if (modelColumn >= TranslationsTableModel.FIRST_LOCALE_COLUMN && model.set.source(key)?.translatable != false) {
                append("missing", SimpleTextAttributes.ERROR_ATTRIBUTES)
                if (!selected) background = MISSING_BACKGROUND
            }
            if (problems.isNotEmpty()) {
                icon = AllIcons.General.Warning
                toolTipText = problems.joinToString("<br>", "<html>", "</html>") { it.message }
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

/** Accepts names Compose can generate an accessor for and that [isFree] says aren't taken. */
class KeyNameValidator(private val isFree: (String) -> Boolean) : com.intellij.openapi.ui.InputValidatorEx {
    override fun checkInput(inputString: String) = getErrorText(inputString) == null
    override fun canClose(inputString: String) = checkInput(inputString)
    override fun getErrorText(inputString: String): String? = when {
        !TranslationsController.isValidKeyName(inputString) -> "Use letters, digits and underscores, not starting with a digit"
        !isFree(inputString) -> "$inputString already exists"
        else -> null
    }
}

internal val Locale.displayName: String get() = java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.ENGLISH)
