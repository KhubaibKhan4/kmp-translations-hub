package io.github.translationshub.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.translationshub.core.Locale
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** Picks locales to add: common app languages, or any BCP 47 tag typed in (`pt-BR`, `sr-Latn`). */
object LocaleChooser {

    /** Languages app stores and translation services cover best, most requested first. */
    val COMMON: List<String> = listOf(
        "es", "fr", "de", "pt-BR", "it", "ja", "ko", "zh-Hans", "zh-Hant", "ar", "hi", "ur", "ru", "tr", "id", "vi",
        "th", "pl", "nl", "sv", "uk", "he", "fa", "bn", "ms", "ro", "cs", "el", "hu", "da", "fi", "nb", "pt-PT",
        "es-419", "fil", "ca", "hr", "sr", "sk", "sl", "bg", "lt", "lv", "et", "sw", "ta", "te", "mr", "gu", "kn",
        "ml", "pa", "kk", "uz", "az", "ka", "hy", "km", "my", "ne", "si", "am", "zu", "af", "is", "ga", "eu", "gl",
    )

    /** The ten languages most apps localize first, for "Select the top 10". */
    val TOP: List<String> = listOf("es", "fr", "de", "pt-BR", "it", "ja", "ko", "zh-Hans", "ar", "hi")

    /** Several locales at once, for Add Locales; null if cancelled. */
    fun chooseMany(project: Project, existing: List<Locale>): List<Locale>? {
        val dialog = AddLocalesDialog(project, existing)
        return if (dialog.showAndGet()) dialog.selected() else null
    }

    /** One locale, e.g. the language of an imported `strings.xml` whose folder doesn't say. */
    fun choose(project: Project, existing: List<Locale>): Locale? {
        val options = COMMON.mapNotNull(Locale::fromTag).filterNot { it in existing }.map { "${it.tag} · ${it.displayName}" }
        val picked = Messages.showEditableChooseDialog(
            "Language tag, e.g. pt-BR or sr-Latn:", "Choose Locale", null, options.toTypedArray(), options.firstOrNull() ?: "",
            object : InputValidatorEx {
                override fun checkInput(inputString: String) = getErrorText(inputString) == null
                override fun canClose(inputString: String) = checkInput(inputString)
                override fun getErrorText(inputString: String): String? {
                    val locale = parse(inputString) ?: return "Not a language tag"
                    return if (locale in existing) "${locale.tag} already exists" else null
                }
            },
        ) ?: return null
        return parse(picked)
    }

    /** `pt-BR · Portuguese (Brazil)` or just `pt-BR`. */
    fun parse(text: String): Locale? = Locale.fromTag(text.substringBefore('·').trim())

    /** Tags typed as `sr-Latn, es-419 tl`: the locales, and the words that aren't language tags. */
    fun parseTags(text: String): Pair<List<Locale>, List<String>> {
        val words = text.split(',', ' ', ';', '\n').map(String::trim).filter(String::isNotEmpty)
        return words.mapNotNull(Locale::fromTag).distinct() to words.filter { Locale.fromTag(it) == null }
    }
}

/** Add Locales: tick any number of languages, or type other tags; all are created in one step. */
internal class AddLocalesDialog(project: Project, private val existing: List<Locale>) : DialogWrapper(project) {

    private val available = LocaleChooser.COMMON.mapNotNull(Locale::fromTag).filterNot { it in existing }
    private val checked = linkedSetOf<Locale>()
    private val list = CheckBoxList<Locale>()
    private val search = SearchTextField(false)
    private val other = JBTextField(30).apply { emptyText.text = "e.g. sr-Latn, es-MX" }.keepWidth()
    private val summary = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        title = "Add Locales"
        setOKButtonText("Add")
        list.setCheckBoxListListener { index, value ->
            val locale = list.getItemAt(index) ?: return@setCheckBoxListListener
            if (value) checked += locale else checked -= locale
            updateSummary()
        }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refill()
        })
        other.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateSummary()
        })
        init()
        refill()
    }

    /** Ticked and typed locales, ticked first, without duplicates or ones the project has. */
    fun selected(): List<Locale> = (checked + LocaleChooser.parseTags(other.text).first).distinct().filterNot { it in existing }

    /** Ticks these, for "Select the top 10" and tests. */
    fun tick(tags: List<String>) {
        tags.mapNotNull(Locale::fromTag).filter { it in available }.forEach { checked += it }
        refill()
    }

    fun setOtherTags(text: String) {
        other.text = text
    }

    fun setSearch(text: String) {
        search.text = text
    }

    /** What the list shows now, for tests. */
    fun shown(): List<Locale> = (0 until list.itemsCount).mapNotNull(list::getItemAt)

    fun validationError(): String? = doValidate()?.message

    private fun refill() {
        val query = search.text.trim()
        list.clear()
        for (locale in available) {
            val label = "${locale.tag} · ${locale.displayName}"
            if (query.isEmpty() || label.contains(query, ignoreCase = true)) list.addItem(locale, label, locale in checked)
        }
        updateSummary()
    }

    private fun updateSummary() {
        val chosen = selected()
        summary.text = wrapped(if (chosen.isEmpty()) "Each gets a values-xx folder with an empty strings.xml; one Undo removes them all."
        else "${chosen.size} to add: ${chosen.take(12).joinToString { it.tag }}${if (chosen.size > 12) "…" else ""}", 360)
    }

    override fun createCenterPanel(): JComponent {
        val links = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(ActionLink("Select the top 10") { tick(LocaleChooser.TOP) })
            add(JBLabel("   "))
            add(ActionLink("Clear") { checked.clear(); refill() })
        }
        return FormBuilder.createFormBuilder()
            .addComponent(search)
            .addComponentFillVertically(JBScrollPane(list).apply { preferredSize = JBUI.size(440, 300) }, 0)
            .addComponent(links)
            .addComponent(JBLabel("Other language tags, separated by commas:"), JBUI.scale(8))
            .addComponent(other)
            .addComponent(summary, JBUI.scale(8))
            .panel
    }

    override fun getPreferredFocusedComponent() = search

    override fun doValidate(): ValidationInfo? {
        val (typed, invalid) = LocaleChooser.parseTags(other.text)
        if (invalid.isNotEmpty()) return ValidationInfo("Not a language tag: ${invalid.joinToString()}", other)
        typed.filter { it in existing }.takeIf { it.isNotEmpty() }?.let { return ValidationInfo("Already added: ${it.joinToString { l -> l.tag }}", other) }
        if (selected().isEmpty()) return ValidationInfo("Choose at least one locale")
        return null
    }

    /** The form alone, for the listing renders (a headless dialog has no window to render). */
    internal fun form(): JComponent = createCenterPanel()
}
