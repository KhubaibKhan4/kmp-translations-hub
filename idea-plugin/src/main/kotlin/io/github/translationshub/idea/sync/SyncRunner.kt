package io.github.translationshub.idea.sync

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import io.github.translationshub.core.Exchange
import io.github.translationshub.core.Locale
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceValue
import io.github.translationshub.core.Sync
import io.github.translationshub.core.TranslationLock
import io.github.translationshub.core.TranslationSet
import io.github.translationshub.idea.TranslationsController
import io.github.translationshub.idea.translate.TranslationSettings
import io.github.translationshub.idea.wrapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent

/**
 * "Sync" in the Translations tab: the default strings go up, translators' translations come down, and translations
 * made in the IDE go up, by the rules in [Sync]. Translations are always downloaded first, so an upload never
 * replaces a translation someone changed on the platform.
 */
object SyncRunner {

    data class Options(val uploadSource: Boolean, val download: Boolean, val uploadTranslations: Boolean)

    data class Conflict(val locale: Locale, val key: ResourceKey, val local: ResourceValue, val remote: ResourceValue)

    data class Summary(
        val fileName: String,
        val sourceStrings: Int?,
        val downloaded: Map<Locale, Int>,
        val uploaded: Map<Locale, Int>,
        val conflicts: List<Conflict>,
        /** Locales here that the platform project doesn't have: add the language there to sync them. */
        val notOnPlatform: List<Locale>,
    )

    /**
     * The sync itself, off the UI thread. [apply] writes one locale's results through
     * [TranslationsController.applySynced], on the UI thread; [read] and [lock] see what it wrote.
     */
    suspend fun run(
        platform: TranslationPlatform,
        fileName: String,
        defaultSource: String,
        options: Options,
        read: () -> TranslationSet,
        lock: () -> TranslationLock,
        apply: (Locale, Map<ResourceKey, ResourceValue>, Map<ResourceKey, ResourceValue>) -> Unit,
        progress: (String) -> Unit = {},
    ): Summary {
        // Sync state recorded for another platform or project says nothing about this one.
        val syncLock = { lock().also { it.syncWith(platform.syncKey) } }
        progress("Reading the ${platform.displayName} project")
        val languages = platform.languages()
        val source = languages.source.ifBlank { defaultSource }
        val matched = Sync.matchLocales(read().locales, languages.targets, source)

        var sourceStrings: Int? = null
        if (options.uploadSource) {
            progress("Uploading the default strings")
            val set = read()
            platform.uploadSource(fileName, Exchange.toStringsXml(set, null, arraysAsStrings = platform.flatArrays), source)
            sourceStrings = set.keys.count { set.source(it)?.translatable == true }
        }

        val remote = if (options.download || options.uploadTranslations) {
            progress("Downloading translations")
            platform.download(fileName, matched.values.toList())
        } else emptyMap()

        val downloaded = LinkedHashMap<Locale, Int>()
        val uploaded = LinkedHashMap<Locale, Int>()
        val conflicts = mutableListOf<Conflict>()
        for ((locale, code) in matched) {
            val set = read()
            // A language with nothing translated can come back as an empty file (POEditor) rather than an empty <resources>.
            val values = remote[code]?.takeIf { it.isNotBlank() }?.let { Exchange.fromStringsXml(it, set, locale).values }.orEmpty()
            val plan = Sync.planPull(set, syncLock(), locale, values)
            val conflicted = plan.filter { it.action == Sync.Action.CONFLICT }
            conflicted.forEach { conflicts += Conflict(locale, it.key, it.local!!, it.remote) }
            val write = if (options.download) plan.filter { it.action == Sync.Action.WRITE }.associate { it.key to it.remote } else emptyMap()
            val same = plan.filter { it.action == Sync.Action.SAME }.associate { it.key to it.remote }
            apply(locale, write, write + same)
            if (write.isNotEmpty()) downloaded[locale] = write.size

            if (options.uploadTranslations && locale in read().locales) {
                val current = read()
                val pending = Sync.pendingUpload(current, syncLock(), locale) - conflicted.map { it.key }.toSet()
                if (pending.isNotEmpty()) {
                    progress("Uploading ${locale.tag} translations")
                    platform.uploadTranslations(fileName, code, Exchange.toStringsXml(current, locale, pending, arraysAsStrings = platform.flatArrays))
                    apply(locale, emptyMap(), pending.associateWith { current.translation(it, locale)!!.value })
                    uploaded[locale] = pending.size
                }
            }
        }
        val notOnPlatform = read().locales.filter { it !in matched }
        return Summary(fileName, sourceStrings, downloaded, uploaded, conflicts, notOnPlatform)
    }

    fun start(project: Project, controller: TranslationsController, onDone: () -> Unit) {
        val settings = SyncSettings.getInstance(project)
        val platform = settings.platform().getOrElse { e ->
            val answer = Messages.showOkCancelDialog(project, e.message, "Sync Translations", "Open Settings", "Cancel", Messages.getInformationIcon())
            if (answer == Messages.OK) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, SyncSettingsConfigurable::class.java)
            }
            return
        }
        val fileName = fileName(project, controller)
        val dialog = SyncDialog(project, platform.displayName, fileName)
        if (!dialog.showAndGet()) return
        val options = dialog.options()
        val defaultSource = TranslationSettings.getInstance().state.sourceLanguage

        object : Task.Backgroundable(project, "Syncing with ${platform.displayName}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = runBlocking {
                    val job = async(Dispatchers.Default) {
                        runCatching {
                            run(platform, fileName, defaultSource, options, controller::read, controller::lock, apply = { locale, values, synced ->
                                onUi { if (!project.isDisposed) controller.applySynced(platform.syncKey, locale, values, synced) }
                            }) { indicator.text = it }
                        }
                    }
                    while (job.isActive) {
                        if (indicator.isCanceled) job.cancel()
                        delay(100)
                    }
                    if (job.isCancelled) null else job.await()
                }
                ApplicationManager.getApplication().invokeLater({
                    result?.onSuccess { summary ->
                        notify(project, platform, summary)
                        if (summary.conflicts.isNotEmpty()) resolve(project, controller, platform, summary.conflicts)
                    }?.onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) return@onFailure
                        notify(project, "Sync with ${platform.displayName} failed", e.message ?: e::class.simpleName.orEmpty(), NotificationType.ERROR)
                    }
                    onDone()
                }, ModalityState.defaultModalityState())
            }
        }.queue()
    }

    /** One name per `composeResources` folder, from its module and source set. */
    private fun fileName(project: Project, controller: TranslationsController): String {
        val base = project.guessProjectDir()
        val relative = base?.let { VfsUtilCore.getRelativePath(controller.root, it) } ?: controller.root.name
        return Sync.remoteFileName(relative)
    }

    private fun onUi(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action, ModalityState.defaultModalityState())

    private fun notify(project: Project, platform: TranslationPlatform, summary: Summary) {
        val lines = buildList {
            summary.sourceStrings?.let { add("Uploaded $it default strings as ${summary.fileName}") }
            summary.downloaded.forEach { (locale, n) -> add("${locale.tag}: $n downloaded") }
            summary.uploaded.forEach { (locale, n) -> add("${locale.tag}: $n uploaded") }
            if (summary.conflicts.isNotEmpty()) add("${summary.conflicts.size} changed on both sides: kept as they are here until you choose")
            if (summary.notOnPlatform.isNotEmpty()) {
                add("Not in the ${platform.displayName} project, so not synced: ${summary.notOnPlatform.joinToString { it.tag }}. Add the languages there.")
            }
        }
        notify(project, "Synced with ${platform.displayName}", lines.joinToString("<br>").ifEmpty { "Everything is already in sync." },
            if (summary.conflicts.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("KMP Translations Hub").createNotification(title, content, type).notify(project)
    }

    /** Asks once for all conflicts: the platform's, or the IDE's (uploaded on the next sync). */
    private fun resolve(project: Project, controller: TranslationsController, platform: TranslationPlatform, conflicts: List<Conflict>) {
        val shown = conflicts.take(12).joinToString("\n") { c ->
            "• ${c.key.name} (${c.locale.tag}): here \"${preview(c.local)}\", ${platform.displayName} \"${preview(c.remote)}\""
        } + if (conflicts.size > 12) "\n… and ${conflicts.size - 12} more" else ""
        val answer = Messages.showYesNoCancelDialog(
            project,
            "${conflicts.size} translations changed both here and on ${platform.displayName} since the last sync:\n\n$shown\n\n" +
                "Use ${platform.displayName}'s versions, or keep yours and upload them on the next sync?",
            "Sync Conflicts", "Use ${platform.displayName}'s", "Keep Mine", "Decide Later", Messages.getWarningIcon(),
        )
        val byLocale = conflicts.groupBy { it.locale }
        when (answer) {
            Messages.YES -> byLocale.forEach { (locale, list) -> list.associate { it.key to it.remote }.let { controller.applySynced(platform.syncKey, locale, it, it) } }
            Messages.NO -> byLocale.forEach { (locale, list) -> controller.applySynced(platform.syncKey, locale, emptyMap(), list.associate { it.key to it.remote }) }
        }
    }

    private fun preview(value: ResourceValue) = value.texts.joinToString(" | ").let { if (it.length > 40) it.take(39) + "…" else it }
}

internal class SyncDialog(project: Project, private val platformName: String, private val fileName: String) : DialogWrapper(project) {
    private val uploadSource = JBCheckBox("Upload the default strings (new and changed keys)", true)
    private val download = JBCheckBox("Download translations from $platformName", true)
    private val uploadTranslations = JBCheckBox("Upload translations made here that $platformName doesn't have", true)

    init {
        title = "Sync with $platformName"
        setOKButtonText("Sync")
        init()
    }

    fun options() = SyncRunner.Options(uploadSource.isSelected, download.isSelected, uploadTranslations.isSelected)

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(uploadSource)
        .addComponent(download)
        .addComponent(uploadTranslations)
        .addComponent(JBLabel(wrapped(
            "This folder's strings are <b>$fileName</b> on $platformName. Downloaded translations count as a translator's: Translate Changes never overwrites them. " +
                "A translation changed both here and on $platformName since the last sync is kept as it is here until you choose.",
            380,
        )).apply { foreground = UIUtil.getContextHelpForeground() })
        .panel

    override fun doValidate() =
        if (!uploadSource.isSelected && !download.isSelected && !uploadTranslations.isSelected) com.intellij.openapi.ui.ValidationInfo("Choose at least one") else null
}
