package io.github.translationshub.idea

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.LicensingFacade

/**
 * KMP Translations Hub Pro, sold through JetBrains Marketplace (which also runs the free trial). Same scheme as
 * KMP Device Hub: the product code lives only in the built plugin.xml (`-Ptranslationshub.productCode`), and a
 * build without one has every Pro feature on.
 */
object ProLicense {
    const val PLUGIN_ID = "io.github.khubaibkhan4.kmptranslationshub"
    private const val MARKETPLACE_URL = "https://plugins.jetbrains.com/search?search=KMP%20Translations%20Hub"

    val features = listOf(
        "Translate changes: only missing and outdated strings, every locale at once, placeholders protected",
        "On-device Apple translation and MyMemory with no key, or your own DeepL, Google, Claude or OpenAI key",
        "Extract hardcoded strings to composeResources",
        "Checks for escaping, length, unused keys and untranslated text",
        "XLIFF, CSV and Android strings.xml import and export",
        "Run in this locale on a device, with KMP Device Hub",
    )

    /** The Marketplace product code, or null in a build without the paid plan. */
    val productCode: String? by lazy {
        // Read from our own built plugin.xml: the platform's plugin-descriptor lookups are internal API.
        val xml = runCatching {
            val jar = java.io.File(ProLicense::class.java.protectionDomain.codeSource.location.toURI())
            if (!jar.isFile) return@runCatching ""
            java.util.jar.JarFile(jar).use { file ->
                file.getJarEntry("META-INF/plugin.xml")?.let { file.getInputStream(it).bufferedReader().readText() }.orEmpty()
            }
        }.getOrDefault("")
        Regex("""<product-descriptor\b[^>]*\bcode="([^"]+)"""").find(xml)?.groupValues?.get(1)
    }

    enum class Status { FREE_BUILD, LICENSED, NOT_LICENSED, LICENSING_UNAVAILABLE }

    private val verifier = LicenseVerifier()
    @Volatile private var cached: Pair<Status, Long>? = null

    /** Whether Pro is unlocked; the IDE's stamp is checked against JetBrains' signatures and cached for 6 hours. */
    fun isLicensed(): Boolean = status().let { it == Status.FREE_BUILD || it == Status.LICENSED } || devOverride()

    fun status(): Status {
        if (devLock()) return Status.NOT_LICENSED
        val code = productCode ?: return Status.FREE_BUILD
        cached?.let { (status, at) -> if (System.currentTimeMillis() - at < CACHE_MS) return status }
        // No facade: licensing isn't loaded yet, or (Android Studio) "JetBrains Marketplace Licensing Support" isn't installed.
        val facade = LicensingFacade.getInstance() ?: return Status.LICENSING_UNAVAILABLE
        val status = if (verifier.isValid(facade.getConfirmationStamp(code))) Status.LICENSED else Status.NOT_LICENSED
        cached = status to System.currentTimeMillis()
        return status
    }

    /** `-Dtranslationshub.pro=true|false` forces Pro on or off, in the plugin's own tests only. */
    private fun devOverride() = isUnitTest() && System.getProperty("translationshub.pro") == "true"

    private fun devLock() = isUnitTest() && System.getProperty("translationshub.pro") == "false"

    private fun isUnitTest() = ApplicationManager.getApplication()?.isUnitTestMode == true

    /** Runs [action] if Pro is unlocked, otherwise explains Pro and offers the trial. */
    fun runOrUpsell(project: Project, feature: String, action: () -> Unit) {
        if (isLicensed()) action() else showUpsell(project, feature)
    }

    fun showUpsell(project: Project, feature: String) {
        if (status() == Status.LICENSING_UNAVAILABLE) {
            val choice = Messages.showDialog(project,
                "$feature is part of KMP Translations Hub Pro.\n\nThis IDE can't check JetBrains Marketplace licenses yet. In Android Studio, " +
                    "install the free \"JetBrains Marketplace Licensing Support\" plugin (Settings → Plugins → Marketplace), restart, and then " +
                    "start your free trial.",
                "KMP Translations Hub Pro", arrayOf("Open Plugins", "Not Now"), 0, Messages.getInformationIcon())
            if (choice == 0) com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(project, "preferences.pluginManager", "JetBrains Marketplace Licensing")
            return
        }
        val message = buildString {
            append("$feature is part of KMP Translations Hub Pro.\n\nPro adds:\n")
            features.forEach { append("  • ").append(it).append('\n') }
            append("\nTry it free: JetBrains Marketplace runs the trial. The table, checks and navigation stay free.")
        }
        val choice = Messages.showDialog(project, message, "KMP Translations Hub Pro", arrayOf("Start Free Trial", "Not Now"), 0, Messages.getInformationIcon())
        if (choice == 0) requestLicense(message)
    }

    /** Opens the IDE's license dialog with this plugin preselected, as JetBrains' reference code does. */
    private fun requestLicense(message: String) {
        val code = productCode
        val actions = ActionManager.getInstance()
        val register = actions.getAction("RegisterPlugins") ?: actions.getAction("Register")
        if (code == null || register == null) {
            BrowserUtil.browse(MARKETPLACE_URL)
            return
        }
        val context = DataContext { id ->
            when (id) {
                "register.product-descriptor.code" -> code
                "register.message" -> message
                else -> null
            }
        }
        ActionUtil.performAction(register, AnActionEvent.createEvent(context, Presentation(), "", ActionUiKind.NONE, null))
        cached = null
    }

    private const val CACHE_MS = 6 * 60 * 60 * 1000L
}
