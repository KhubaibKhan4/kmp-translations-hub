package io.github.translationshub.idea

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.Locale

class AddLocalesDialogTest : BasePlatformTestCase() {

    private fun dialog(existing: List<Locale> = listOf(Locale("de"))) =
        AddLocalesDialog(project, existing).also { Disposer.register(testRootDisposable, it.disposable) }

    fun `test top 10, typed tags and the ones already there`() {
        val d = dialog()
        assertEquals("Choose at least one locale", d.validationError())
        d.tick(LocaleChooser.TOP)
        d.setOtherTags("sr-Latn, es-MX de")
        // German is in the top 10 but already in the project; es-MX and sr-Latn come from the text field.
        assertEquals(
            listOf("es", "fr", "pt-BR", "it", "ja", "ko", "zh-Hans", "ar", "hi", "sr-Latn", "es-MX"),
            d.selected().map { it.tag },
        )
        assertEquals("Already added: de", d.validationError())
        d.setOtherTags("sr-Latn, es-MX, klingon!")
        assertEquals("Not a language tag: klingon!", d.validationError())
        d.setOtherTags("sr-Latn")
        assertNull(d.validationError())
    }

    fun `test search filters the list and keeps what's ticked`() {
        val d = dialog()
        d.tick(listOf("ja"))
        d.setSearch("portu")
        assertEquals(listOf("pt-BR", "pt-PT"), d.shown().map { it.tag })
        d.setSearch("")
        assertTrue(Locale("ja") in d.selected())
        assertFalse(Locale("de") in d.shown())
    }

    fun `test typed tags parse loosely`() {
        val (locales, invalid) = LocaleChooser.parseTags("pt-BR, sr-Latn;es-419\nzh_Hant   ??")
        assertEquals(listOf("pt-BR", "sr-Latn", "es-419", "zh-Hant"), locales.map { it.tag })
        assertEquals(listOf("??"), invalid)
    }
}
