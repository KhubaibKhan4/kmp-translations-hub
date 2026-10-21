package io.github.translationshub.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.translationshub.core.ArrayValue
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.PluralRules
import io.github.translationshub.core.PluralsValue
import io.github.translationshub.core.Problem
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.ResourceValue
import io.github.translationshub.core.TextValue
import io.github.translationshub.core.TranslationState
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Below the table: the selected cell in full, with its comment, source text, problems and an editor that fits the
 * kind: a text area for strings, one field per plural form the language uses, one line per array item.
 * Apply (or ⌘/Ctrl+Enter) writes it through [onApply].
 */
class CellDetailPanel(private val onApply: (Cell, ResourceValue) -> Unit) : JPanel(BorderLayout()) {

    private var cell: Cell? = null
    private var readValue: () -> ResourceValue? = { null }
    private val content = JPanel(BorderLayout())
    private val apply = JButton("Apply").apply { addActionListener { applyEdit() } }

    init {
        border = JBUI.Borders.empty(8)
        add(content, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(apply) }, BorderLayout.SOUTH)
        showNothing()
    }

    val shownCell: Cell? get() = cell

    fun show(model: TranslationsTableModel, cell: Cell?) {
        this.cell = cell
        content.removeAll()
        val key = cell?.key
        val source = key?.let(model.set::source)
        if (cell == null || key == null || (source == null && cell.locale == null)) {
            showNothing()
            return
        }
        val entry = if (cell.locale == null) source else model.set.translation(key, cell.locale)
        val editable = cell.locale == null || source?.translatable != false

        val form = FormBuilder.createFormBuilder()
        form.addComponent(JBLabel("${key.name}  ·  ${cell.locale?.tag ?: "default"}").apply { font = JBUI.Fonts.label().asBold() })
        source?.comment?.let { form.addComponent(JBLabel(it).apply { foreground = UIUtil.getContextHelpForeground() }) }
        if (cell.locale != null && source != null) {
            form.addLabeledComponent("Default:", JBLabel(TranslationsTableModel.display(source)).apply { foreground = UIUtil.getContextHelpForeground() })
        }
        model.status(cell)?.let { status ->
            when {
                status.needsReview -> form.addComponent(warning("The default text changed since this was written"))
                status.state == TranslationState.STALE -> form.addComponent(warning("Outdated machine translation: \"Translate changes\" will redo it"))
            }
        }
        model.problems(cell).forEach { form.addComponent(warning(it.message)) }

        when (key.kind) {
            ResourceKind.STRING -> {
                val area = textArea((entry?.value as? TextValue)?.text.orEmpty(), editable)
                form.addComponentFillVertically(JBScrollPane(area), 4)
                readValue = { TextValue(area.text) }
            }
            ResourceKind.PLURALS -> {
                val items = (entry?.value as? PluralsValue)?.items.orEmpty()
                val needed = cell.locale?.let(PluralRules::categories) ?: setOf(PluralCategory.ONE, PluralCategory.OTHER)
                val fields = (needed + items.keys).sorted().associateWith { category ->
                    JBTextField(items[category].orEmpty()).apply { isEditable = editable; submitOnCtrlEnter(this) }
                }
                for ((category, field) in fields) {
                    val label = category.xmlName + if (category in needed) "" else " (not used in ${cell.locale?.tag})"
                    form.addLabeledComponent("$label:", field)
                }
                // An empty field removes that form; `other` is required, so it is always written.
                readValue = {
                    PluralsValue(fields.mapValues { it.value.text }.filter { (category, text) -> text.isNotEmpty() || category == PluralCategory.OTHER })
                }
            }
            ResourceKind.ARRAY -> {
                val area = textArea((entry?.value as? ArrayValue)?.items.orEmpty().joinToString("\n"), editable)
                form.addLabeledComponent("One item per line:", JBScrollPane(area), true)
                readValue = { ArrayValue(area.text.split('\n')) }
            }
        }
        form.addComponentFillVertically(JPanel(), 0)
        content.add(form.panel, BorderLayout.CENTER)
        apply.isEnabled = editable
        apply.isVisible = true
        revalidate()
        repaint()
    }

    /** The value in the editor, as Apply would write it. */
    fun editedValue(): ResourceValue? = readValue()

    private fun applyEdit() {
        val cell = cell ?: return
        val value = readValue() ?: return
        onApply(cell, value)
    }

    private fun showNothing() {
        content.removeAll()
        content.add(JBLabel("Select a cell to see it in full and edit plurals and arrays").apply {
            foreground = UIUtil.getContextHelpForeground()
        }, BorderLayout.NORTH)
        readValue = { null }
        apply.isVisible = false
        revalidate()
        repaint()
    }

    private fun warning(text: String) = JBLabel(text, AllIcons.General.Warning, JBLabel.LEFT)

    private fun textArea(text: String, editable: Boolean) = JBTextArea(text, 3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = editable
        submitOnCtrlEnter(this)
    }

    private fun submitOnCtrlEnter(component: JComponent) {
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK)
        component.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, "translationshub.apply")
        component.actionMap.put("translationshub.apply", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = applyEdit()
        })
    }
}
