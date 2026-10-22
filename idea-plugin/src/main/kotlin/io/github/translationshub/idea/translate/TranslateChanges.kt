package io.github.translationshub.idea.translate

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import io.github.translationshub.core.Locale
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.TranslationPlan
import io.github.translationshub.idea.TranslationsController
import io.github.translationshub.idea.displayName
import io.github.translationshub.idea.listRenderer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.swing.JComponent

/**
 * "Translate Changes": sends only what's missing or outdated (per `translations.lock.json`) in the chosen locales,
 * all locales at once, and writes each locale as soon as it's done, one Undo per locale.
 */
object TranslateChanges {

    /** What would be sent for each locale; [includeReview] adds a person's translations whose default text changed. */
    fun pending(controller: TranslationsController, includeReview: Boolean): Map<Locale, List<ResourceKey>> {
        val set = controller.read()
        val lock = controller.lock()
        return set.locales.associateWith { locale ->
            set.keys.filter { key ->
                set.source(key)?.translatable == true && lock.status(set, key, locale).let { it.needsMachineTranslation || (includeReview && it.needsReview) }
            }
        }
    }

    data class Summary(val written: Map<Locale, Int>, val failed: Map<Locale, Map<ResourceKey, String>>, val errors: Map<Locale, String>)

    /**
     * Translates and writes. Runs the locales in parallel; [apply] writes one locale's values (on the EDT in the IDE,
     * directly in tests).
     */
    suspend fun run(
        controller: TranslationsController,
        provider: TranslationProvider,
        sourceLanguage: String,
        work: Map<Locale, List<ResourceKey>>,
        apply: (Locale, Map<ResourceKey, io.github.translationshub.core.ResourceValue>) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Summary = coroutineScope {
        val set = controller.read()
        val plans = work.filterValues { it.isNotEmpty() }.map { (locale, keys) -> TranslationPlan.build(set, locale, keys) }
        val total = plans.sumOf { it.segments.size }
        val runner = TranslationRunner(provider, sourceLanguage)
        val progress = IntArray(plans.size)
        val written = mutableMapOf<Locale, Int>()
        val failed = mutableMapOf<Locale, Map<ResourceKey, String>>()
        val errors = mutableMapOf<Locale, String>()
        plans.mapIndexed { i, plan ->
            async {
                try {
                    val result = runner.run(plan) { done ->
                        synchronized(progress) {
                            progress[i] = done
                            onProgress(progress.sum(), total)
                        }
                    }
                    apply(plan.locale, result.values)
                    synchronized(written) {
                        written[plan.locale] = result.values.size
                        if (result.failed.isNotEmpty()) failed[plan.locale] = result.failed
                    }
                } catch (e: ProviderException) {
                    synchronized(written) { errors[plan.locale] = e.message ?: "Failed" }
                }
            }
        }.awaitAll()
        Summary(written, failed, errors)
    }

    /** Shows the dialog, then runs in the background with progress and Cancel. */
    fun start(project: Project, controller: TranslationsController, onDone: () -> Unit) {
        val dialog = TranslateDialog(project, controller)
        if (!dialog.showAndGet()) return
        val provider = dialog.provider
        val settings = TranslationSettings.getInstance()
        settings.state.provider = provider.id
        val work = dialog.selectedWork()
        object : Task.Backgroundable(project, "Translating with ${provider.displayName}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                val summary = runBlockingCancellable {
                    run(controller, provider, settings.state.sourceLanguage, work, apply = { locale, values ->
                        ApplicationManager.getApplication().invokeAndWait({
                            if (!project.isDisposed) controller.applyTranslations(locale, values, provider.id)
                        }, ModalityState.defaultModalityState())
                    }) { done, total ->
                        indicator.fraction = if (total == 0) 1.0 else done.toDouble() / total
                        indicator.text2 = "$done of $total strings"
                    }
                }
                ApplicationManager.getApplication().invokeLater({ onDone(); notify(project, provider, summary) }, ModalityState.defaultModalityState())
            }
        }.queue()
    }

    private fun notify(project: Project, provider: TranslationProvider, summary: Summary) {
        val lines = buildList {
            summary.written.forEach { (locale, n) -> add("${locale.tag}: $n translated") }
            summary.failed.forEach { (locale, keys) -> add("${locale.tag}: ${keys.size} not translated (${keys.values.distinct().take(2).joinToString("; ")})") }
            summary.errors.forEach { (locale, error) -> add("${locale.tag}: $error") }
        }
        val type = if (summary.errors.isEmpty() && summary.failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING
        NotificationGroupManager.getInstance().getNotificationGroup("KMP Translations Hub")
            .createNotification("Translated with ${provider.displayName}", lines.joinToString("<br>").ifEmpty { "Nothing to translate." }, type)
            .notify(project)
    }
}

internal class TranslateDialog(project: Project, private val controller: TranslationsController) : DialogWrapper(project) {

    private val settings = TranslationSettings.getInstance()
    private val providers = settings.providers()
    private val providerBox = ComboBox(providers.toTypedArray()).apply {
        renderer = listRenderer<TranslationProvider> { p -> p?.let { it.displayName + (it.unavailableReason()?.let { r -> "  ($r)" } ?: "") } ?: "" }
        // The same instances as the items: a fresh list's providers would never match the selection.
        selectedItem = providers.firstOrNull { it.id == settings.state.provider } ?: providers.first()
    }
    private val includeReview = JBCheckBox("Also redo translations a person wrote whose default text changed")
    private val locales = CheckBoxList<Locale>()
    private val summary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var work: Map<Locale, List<ResourceKey>> = emptyMap()

    val provider: TranslationProvider get() = providerBox.item

    init {
        title = "Translate Changes"
        setOKButtonText("Translate")
        includeReview.addActionListener { refresh() }
        locales.setCheckBoxListListener { _, _ -> updateSummary() }
        init()
        refresh()
    }

    private fun refresh() {
        val checked = (0 until locales.itemsCount).mapNotNull { locales.getItemAt(it)?.takeIf(locales::isItemSelected) }.toSet()
        work = TranslateChanges.pending(controller, includeReview.isSelected)
        locales.clear()
        for ((locale, keys) in work) {
            locales.addItem(locale, "${locale.tag} · ${locale.displayName} · ${keys.size} to translate", keys.isNotEmpty() && (checked.isEmpty() || locale in checked))
        }
        updateSummary()
    }

    private fun updateSummary() {
        val n = selectedWork().values.sumOf { it.size }
        summary.text = if (n == 0) "Everything is translated and up to date." else "$n strings will be sent. Strings already translated and unchanged are skipped."
    }

    fun selectedWork(): Map<Locale, List<ResourceKey>> = work.filterKeys { locales.isItemSelected(it) }

    /** The form alone, for the listing renders (a headless dialog has no window to render). */
    internal fun form(): JComponent = createCenterPanel()

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Translate with:", providerBox)
        .addLabeledComponentFillVertically("Locales:", JBScrollPane(locales))
        .addComponent(includeReview)
        .addComponent(summary)
        .panel

    override fun doValidate(): ValidationInfo? {
        provider.unavailableReason()?.let { return ValidationInfo(it, providerBox) }
        if (selectedWork().values.all { it.isEmpty() }) return ValidationInfo("Nothing to translate in the selected locales")
        return null
    }
}
