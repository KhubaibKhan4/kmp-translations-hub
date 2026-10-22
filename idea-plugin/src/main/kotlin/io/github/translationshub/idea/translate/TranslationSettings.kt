package io.github.translationshub.idea.translate

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

/** Which service "Translate changes" uses and how. API keys are kept in the IDE's password safe, never in this file. */
@Service(Service.Level.APP)
@State(name = "KmpTranslationsHub", storages = [Storage("kmp-translations-hub.xml")])
class TranslationSettings : PersistentStateComponent<TranslationSettings.State> {

    class State {
        var provider: String = "mymemory"
        /** The language of the default `values` folder, which the folder name doesn't say. */
        var sourceLanguage: String = "en"
        var openAiModel: String = "gpt-5-mini"
        var claudeModel: String = ClaudeProvider.DEFAULT_MODEL
        var myMemoryEmail: String = ""
    }

    private var state = State()

    override fun getState() = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    fun key(providerId: String): String? = PasswordSafe.instance.getPassword(attributes(providerId))?.takeIf { it.isNotBlank() }

    fun setKey(providerId: String, key: String?) {
        PasswordSafe.instance.set(attributes(providerId), key?.takeIf { it.isNotBlank() }?.let { Credentials("api-key", it) })
    }

    private fun attributes(providerId: String) = CredentialAttributes(generateServiceName("KMP Translations Hub", providerId))

    /** Every provider, in the order they're offered: free ones first. */
    fun providers(): List<TranslationProvider> = listOf(
        AppleProvider(),
        MyMemoryProvider { state.myMemoryEmail },
        DeepLProvider { key("deepl") },
        GoogleCloudProvider { key("google") },
        ClaudeProvider({ key("claude") }, { state.claudeModel }),
        OpenAiProvider({ key("openai") }, { state.openAiModel }),
    )

    fun provider(id: String = state.provider): TranslationProvider = providers().firstOrNull { it.id == id } ?: providers().first { it.id == "mymemory" }

    companion object {
        fun getInstance(): TranslationSettings = service()
    }
}

/** Settings → Tools → KMP Translations Hub. */
class TranslationSettingsConfigurable : Configurable {

    private val settings = TranslationSettings.getInstance()
    private val providers = settings.providers()
    private val provider = ComboBox(providers.toTypedArray()).apply {
        renderer = listRenderer<TranslationProvider> { it?.displayName ?: "" }
    }
    private val sourceLanguage = JBTextField(8).keepWidth()
    private val email = JBTextField(24).keepWidth()
    private val openAiModel = JBTextField(24).keepWidth()
    private val claudeModel = JBTextField(24).keepWidth()
    private val keys = listOf("deepl", "google", "claude", "openai").associateWith { JBPasswordField().apply { columns = 32 }.keepWidth() }

    override fun getDisplayName() = "KMP Translations Hub"

    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Translate with:", provider)
            .addComponentToRightColumn(hint("Apple and MyMemory need no key. Apple runs on this Mac only and needs the languages downloaded in System Settings → General → Language & Region → Translation Languages."))
            .addLabeledComponent("Language of the default values folder:", sourceLanguage)
            .addSeparator()
            .addLabeledComponent("MyMemory email (optional):", email)
            .addComponentToRightColumn(hint("Raises MyMemory's free limit about tenfold. Sent to MyMemory only. MyMemory keeps the text it's sent: use Apple or your own key for confidential strings."))
            .addLabeledComponent("DeepL API key:", keys.getValue("deepl"))
            .addComponentToRightColumn(hint("DeepL API Free includes 500,000 characters a month."))
            .addLabeledComponent("Google Cloud API key:", keys.getValue("google"))
            .addLabeledComponent("Anthropic API key:", keys.getValue("claude"))
            .addLabeledComponent("Claude model:", claudeModel)
            .addLabeledComponent("OpenAI API key:", keys.getValue("openai"))
            .addLabeledComponent("OpenAI model:", openAiModel)
            .addComponentToRightColumn(hint("Keys are stored in the IDE's password safe. Strings are sent to the service you choose, with their keys and XML comments as context."))
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    private fun hint(text: String) = JBLabel(wrapped(text)).apply { foreground = UIUtil.getContextHelpForeground() }

    override fun isModified(): Boolean {
        val s = settings.state
        return provider.item?.id != s.provider || sourceLanguage.text.trim() != s.sourceLanguage || email.text.trim() != s.myMemoryEmail ||
            openAiModel.text.trim() != s.openAiModel || claudeModel.text.trim() != s.claudeModel || keys.any { (id, field) -> String(field.password) != (settings.key(id) ?: "") }
    }

    override fun apply() {
        val s = settings.state
        s.provider = provider.item?.id ?: s.provider
        s.sourceLanguage = sourceLanguage.text.trim().ifEmpty { "en" }
        s.myMemoryEmail = email.text.trim()
        s.openAiModel = openAiModel.text.trim()
        s.claudeModel = claudeModel.text.trim().ifEmpty { ClaudeProvider.DEFAULT_MODEL }
        keys.forEach { (id, field) -> settings.setKey(id, String(field.password)) }
    }

    override fun reset() {
        val s = settings.state
        provider.selectedItem = providers.firstOrNull { it.id == s.provider } ?: providers.first()
        sourceLanguage.text = s.sourceLanguage
        email.text = s.myMemoryEmail
        openAiModel.text = s.openAiModel
        claudeModel.text = s.claudeModel
        keys.forEach { (id, field) -> field.text = settings.key(id) ?: "" }
    }
}
