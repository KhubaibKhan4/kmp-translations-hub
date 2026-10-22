package io.github.translationshub.idea

import io.github.translationshub.idea.sync.SyncSettings
import io.github.translationshub.idea.sync.SyncSettings.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncSettingsTest {

    @Test
    fun `project ids are checked against each platform's format`() {
        assertNull(Platform.POEDITOR.idProblem("812345"))
        assertNull(Platform.CROWDIN.idProblem("123456"))
        assertNull(Platform.LOKALISE.idProblem("247023476ad9f651b867f2.18612955"))
        assertEquals("POEditor project IDs look like 812345: 247023476ad9f651b867f2.18612955 looks like a Lokalise ID.",
            Platform.POEDITOR.idProblem("247023476ad9f651b867f2.18612955"))
        assertEquals("Lokalise project IDs look like 3002780358964f9bab5a92.87762498: 812345 looks like a Crowdin ID.",
            Platform.LOKALISE.idProblem("812345"))
        assertEquals("Crowdin project IDs look like 123456.", Platform.CROWDIN.idProblem("my-project"))
    }

    @Test
    fun `the old single project id moves to the platform it looks like`() {
        val settings = SyncSettings()
        settings.loadState(SyncSettings.State().apply { platform = "poeditor"; projectId = "247023476ad9f651b867f2.18612955" })
        assertEquals("247023476ad9f651b867f2.18612955", settings.projectId(Platform.LOKALISE))
        assertEquals("", settings.projectId(Platform.POEDITOR))
        assertEquals("", settings.state.projectId)

        val numeric = SyncSettings()
        numeric.loadState(SyncSettings.State().apply { platform = "poeditor"; projectId = "812345" })
        assertEquals("812345", numeric.projectId(Platform.POEDITOR))
    }
}
