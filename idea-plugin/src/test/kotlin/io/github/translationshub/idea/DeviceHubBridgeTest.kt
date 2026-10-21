package io.github.translationshub.idea

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.Locale

class DeviceHubBridgeTest : BasePlatformTestCase() {

    fun `test the locale reaches Device Hub's action`() {
        var received: String? = null
        val stand = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                received = e.getData(DataKey.create<String>("kmpdevicehub.locale"))
                assertEquals(project, e.project)
            }
        }
        assertFalse(DeviceHubBridge.isInstalled())
        ActionManager.getInstance().registerAction(DeviceHubBridge.ACTION_ID, stand)
        try {
            assertTrue(DeviceHubBridge.isInstalled())
            DeviceHubBridge.runIn(project, Locale("pt", region = "BR"))
            assertEquals("pt-BR", received)
        } finally {
            ActionManager.getInstance().unregisterAction(DeviceHubBridge.ACTION_ID)
        }
    }
}
