package io.github.translationshub.idea

import io.github.translationshub.core.Locale
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.idea.translate.RatingPrompt
import io.github.translationshub.idea.translate.TranslateChanges
import io.github.translationshub.idea.translate.TranslationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingPromptTest {
    private val de = Locale("de")
    private val ok = TranslateChanges.Summary(mapOf(de to 2), emptyMap(), emptyMap())

    @Test
    fun `asks once, on the third successful run`() {
        val state = TranslationSettings.State()
        val asked = (1..6).map { RatingPrompt.recordRun(state, ok) }
        assertEquals(listOf(false, false, true, false, false, false), asked)
        assertTrue(state.ratingPromptShown)
    }

    @Test
    fun `runs that translated nothing or had failures don't count`() {
        val state = TranslationSettings.State()
        val nothing = TranslateChanges.Summary(emptyMap(), emptyMap(), emptyMap())
        val failed = TranslateChanges.Summary(mapOf(de to 1), mapOf(de to mapOf(ResourceKey(ResourceKind.STRING, "a") to "lost %1\$s")), emptyMap())
        val error = TranslateChanges.Summary(mapOf(de to 1), emptyMap(), mapOf(de to "quota"))
        repeat(3) {
            assertFalse(RatingPrompt.recordRun(state, nothing))
            assertFalse(RatingPrompt.recordRun(state, failed))
            assertFalse(RatingPrompt.recordRun(state, error))
        }
        assertEquals(0, state.successfulRuns)
    }
}
