package io.github.translationshub.idea

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.translationshub.core.Locale

/**
 * "Run in this locale" through KMP Device Hub: its hidden `KmpDeviceHub.RunInLocale` action relaunches the app in
 * a language on the selected device. Called by id, so neither plugin needs the other installed.
 */
object DeviceHubBridge {
    const val ACTION_ID = "KmpDeviceHub.RunInLocale"
    private const val MARKETPLACE_URL = "https://plugins.jetbrains.com/plugin/35272-kmp-device-hub"
    private val LOCALE: DataKey<String> = DataKey.create("kmpdevicehub.locale")

    fun isInstalled(): Boolean = ActionManager.getInstance().getAction(ACTION_ID) != null

    fun runIn(project: Project, locale: Locale) {
        val action = ActionManager.getInstance().getAction(ACTION_ID)
        if (action == null) {
            val choice = Messages.showDialog(project,
                "Running your app in ${locale.displayName} needs KMP Device Hub, which switches the language on Android devices, " +
                    "emulators and iOS simulators and relaunches the app.",
                "Run in ${locale.tag}", arrayOf("Get KMP Device Hub", "Cancel"), 0, Messages.getInformationIcon())
            if (choice == 0) BrowserUtil.browse(MARKETPLACE_URL)
            return
        }
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(LOCALE, locale.tag).build()
        performAction(action, AnActionEvent.createEvent(context, Presentation(), "KmpTranslationsHub", ActionUiKind.NONE, null))
    }
}
