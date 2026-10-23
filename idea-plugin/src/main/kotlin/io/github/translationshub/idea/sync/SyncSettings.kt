package io.github.translationshub.idea.sync

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import io.github.translationshub.idea.keepWidth
import io.github.translationshub.idea.listRenderer
import io.github.translationshub.idea.wrapped
import javax.swing.JComponent

/**
 * Which translation platform this project syncs with, and its project on each platform. Kept per IDE project (in
 * `.idea`, so a team can share it); access tokens are kept in the IDE's password safe, never in this file.
 */
@Service(Service.Level.PROJECT)
@State(name = "KmpTranslationsHubSync", storages = [Storage("kmp-translations-hub.xml")])
class SyncSettings : PersistentStateComponent<SyncSettings.State> {

    enum class Platform(
        val id: String,
        val displayName: String,
        val tokenHint: String,
        val idHint: String,
        /** What a project ID looks like, so one platform's ID is caught before it's sent to another. */
        private val idFormat: Regex,
        val idExample: String,
    ) {
        CROWDIN("crowdin", "Crowdin", "A personal access token with project access: Account Settings → API.",
            "The number in the project's Tools → API.", Regex("[0-9]+"), "123456"),
        LOKALISE("lokalise", "Lokalise", "An API token with read and write access: Profile settings → API tokens → Generate new token (40 characters, letters and digits).",
            "More → Settings → General → Project ID.", Regex("[0-9a-zA-Z]+\\.[0-9]+"), "3002780358964f9bab5a92.87762498"),
        POEDITOR("poeditor", "POEditor", "Your API token: Account settings → API access.",
            "The number next to the project in Account settings → API access, or after id= in the project's address.", Regex("[0-9]+"), "812345");

        /** Null when [projectId] looks like this platform's, else what's wrong. */
        fun idProblem(projectId: String): String? =
            if (projectId.isEmpty() || idFormat.matches(projectId)) null
            else "$displayName project IDs look like $idExample" + (entries.firstOrNull { it != this && it.idFormat.matches(projectId) && it.idFormat.pattern != idFormat.pattern }
                ?.let { ": $projectId looks like a ${it.displayName} ID" } ?: "") + "."

        fun looksLike(projectId: String) = idFormat.matches(projectId)
    }

    class State {
        var platform: String = ""
        var crowdinProjectId: String = ""
        var lokaliseProjectId: String = ""
        var poeditorProjectId: String = ""
        /** Crowdin Enterprise only: the organization in `https://<organization>.crowdin.com`. */
        var crowdinOrganization: String = ""
        /** Before there was one ID per platform; moved to the platform it looks like when read. */
        var projectId: String = ""
    }

    private var state = State()

    override fun getState() = state

    override fun loadState(loaded: State) {
        state = loaded
        val legacy = clean(loaded.projectId)
        if (legacy.isNotEmpty()) {
            val kind = platformKind()?.takeIf { it.looksLike(legacy) } ?: Platform.entries.firstOrNull { it.looksLike(legacy) }
            if (kind != null && projectId(kind).isEmpty()) setProjectId(kind, legacy)
            loaded.projectId = ""
        }
    }

    fun platformKind(): Platform? = Platform.entries.firstOrNull { it.id == state.platform }

    fun projectId(platform: Platform): String = when (platform) {
        Platform.CROWDIN -> state.crowdinProjectId
        Platform.LOKALISE -> state.lokaliseProjectId
        Platform.POEDITOR -> state.poeditorProjectId
    }

    fun setProjectId(platform: Platform, id: String) {
        val value = clean(id)
        when (platform) {
            Platform.CROWDIN -> state.crowdinProjectId = value
            Platform.LOKALISE -> state.lokaliseProjectId = value
            Platform.POEDITOR -> state.poeditorProjectId = value
        }
    }

    // Trimmed both ways: a space or line break copied with a token makes the platforms reject the header.
    fun token(platform: Platform): String? =
        PasswordSafe.instance.getPassword(attributes(platform))?.let(::clean)?.takeIf { it.isNotEmpty() }

    fun setToken(platform: Platform, token: String?) {
        PasswordSafe.instance.set(attributes(platform), token?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { Credentials("api-token", it) })
    }

    /** The platform client, or what to set up first. */
    fun platform(): Result<TranslationPlatform> {
        val kind = platformKind() ?: return Result.failure(IllegalStateException(SETUP))
        val projectId = clean(projectId(kind)).ifEmpty { return Result.failure(IllegalStateException("Set the ${kind.displayName} project ID. $WHERE")) }
        kind.idProblem(projectId)?.let { return Result.failure(IllegalStateException("$it $WHERE")) }
        val token = token(kind) ?: return Result.failure(IllegalStateException("Add a ${kind.displayName} API token. $WHERE"))
        return Result.success(when (kind) {
            Platform.CROWDIN -> CrowdinPlatform(token, projectId, state.crowdinOrganization)
            Platform.LOKALISE -> LokalisePlatform(token, projectId)
            Platform.POEDITOR -> PoEditorPlatform(token, projectId)
        })
    }

    // Tokens belong to the user, not the project: one per platform for every project.
    private fun attributes(platform: Platform) = CredentialAttributes(generateServiceName("KMP Translations Hub", "sync-${platform.id}"))

    companion object {
        /** Drops whitespace anywhere in a token or ID: none of the platforms use it, and copying often adds it. */
        fun clean(value: String): String = value.filterNot(Char::isWhitespace)

        const val WHERE = "Settings → Tools → KMP Translations Hub → Translation Platform."
        const val SETUP = "Choose Crowdin, Lokalise or POEditor and your project there in $WHERE"

        fun getInstance(project: Project): SyncSettings = project.service()
    }
}

/** Settings → Tools → KMP Translations Hub → Translation Platform: the platform to sync with, then each platform's project and token. */
class SyncSettingsConfigurable(project: Project) : Configurable {

    private val settings = SyncSettings.getInstance(project)
    private val none = "None"
    private val platform = ComboBox<Any>((listOf<Any>(none) + SyncSettings.Platform.entries).toTypedArray()).apply {
        renderer = listRenderer<Any> { (it as? SyncSettings.Platform)?.displayName ?: none }
    }
    private val projectIds = SyncSettings.Platform.entries.associateWith { JBTextField(28).keepWidth() }
    private val organization = JBTextField(24).keepWidth()
    private val tokens = SyncSettings.Platform.entries.associateWith { JBPasswordField().apply { columns = 32 }.keepWidth() }

    override fun getDisplayName() = "Translation Platform"

    override fun createComponent(): JComponent {
        reset()
        val form = FormBuilder.createFormBuilder()
            .addComponent(hint("Sync this project's translations with the platform your translators use. Sync in the Translations tab uploads the default strings, " +
                "downloads translations, and uploads translations made in the IDE. A translation changed on both sides since the last sync is never overwritten without asking."))
            .addLabeledComponent("Sync with:", platform)
        for (kind in SyncSettings.Platform.entries) {
            form.addSeparator()
                .addLabeledComponent("${kind.displayName} project ID:", projectIds.getValue(kind))
                .addComponentToRightColumn(hint("${kind.idHint} For example ${kind.idExample}."))
            if (kind == SyncSettings.Platform.CROWDIN) {
                form.addLabeledComponent("Crowdin Enterprise organization:", organization)
                    .addComponentToRightColumn(hint("Only for Crowdin Enterprise: the name in https://&lt;organization&gt;.crowdin.com. Leave empty for crowdin.com."))
            }
            form.addLabeledComponent("${kind.displayName} API token:", tokens.getValue(kind)).addComponentToRightColumn(hint(kind.tokenHint))
        }
        return form
            .addSeparator()
            .addComponent(hint("Tokens are stored in the IDE's password safe. Your strings go only to the platform you sync with."))
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    private fun hint(text: String) = JBLabel(wrapped(text)).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun chosen() = platform.item as? SyncSettings.Platform

    override fun isModified(): Boolean {
        val s = settings.state
        return (chosen()?.id ?: "") != s.platform || organization.text.trim() != s.crowdinOrganization ||
            projectIds.any { (kind, field) -> SyncSettings.clean(field.text) != settings.projectId(kind) } ||
            tokens.any { (kind, field) -> SyncSettings.clean(String(field.password)) != (settings.token(kind) ?: "") }
    }

    override fun apply() {
        for ((kind, field) in projectIds) {
            kind.idProblem(SyncSettings.clean(field.text))?.let { throw com.intellij.openapi.options.ConfigurationException(it, "Wrong Project ID") }
        }
        val s = settings.state
        s.platform = chosen()?.id ?: ""
        s.crowdinOrganization = organization.text.trim()
        projectIds.forEach { (kind, field) -> settings.setProjectId(kind, field.text) }
        tokens.forEach { (kind, field) -> settings.setToken(kind, String(field.password)) }
    }

    override fun reset() {
        val s = settings.state
        platform.selectedItem = settings.platformKind() ?: none
        organization.text = s.crowdinOrganization
        projectIds.forEach { (kind, field) -> field.text = settings.projectId(kind) }
        tokens.forEach { (kind, field) -> field.text = settings.token(kind) ?: "" }
    }
}
