package io.github.translationshub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChecksTest {

    private fun file(folder: String, xml: String) =
        StringsFile("/res/$folder/strings.xml", FolderQualifiers.parse(folder)!!, StringsXmlParser.parse("<resources>$xml</resources>"))

    private val set = TranslationSet(
        "/res",
        listOf(
            file(
                "values",
                """
                <string name="hello">Hello %1${'$'}s, you have %2${'$'}d messages</string>
                <string name="bye">Bye</string>
                <string name="brand" translatable="false">Acme</string>
                <plurals name="files"><item quantity="one">One file</item><item quantity="other">%d files</item></plurals>
                <string-array name="days"><item>Mon</item><item>Tue</item></string-array>
                """,
            ),
            file(
                "values-de",
                """
                <string name="hello">%2${'$'}d Nachrichten für %1${'$'}s</string>
                <plurals name="files"><item quantity="one">Eine Datei</item><item quantity="other">%d Dateien</item></plurals>
                <string-array name="days"><item>Mo</item></string-array>
                <string name="old_key">Alt</string>
                """,
            ),
            file(
                "values-pl",
                """
                <string name="hello">Cześć %1${'$'}s, masz %2${'$'}s wiadomości</string>
                <string name="bye">Pa</string>
                <plurals name="files"><item quantity="one">Jeden plik</item><item quantity="other">%d plików</item></plurals>
                <string-array name="days"><item>Pn</item><item>Wt</item></string-array>
                """,
            ),
        ),
    )

    private val problems = Checks.run(set)
    private val de = Locale("de")
    private val pl = Locale("pl")

    @Test
    fun `reordered placeholders are fine, a changed type is not`() {
        val mismatches = problems.filterIsInstance<Problem.PlaceholderMismatch>()
        assertEquals(listOf(pl), mismatches.map { it.locale })
        assertEquals(mapOf(1 to 's', 2 to 'd'), mismatches.single().expected)
    }

    @Test
    fun `missing translations skip untranslatable keys`() {
        val missing = problems.filterIsInstance<Problem.Missing>().map { it.key.name to it.locale }
        assertEquals(listOf("bye" to de), missing)
    }

    @Test
    fun `plural forms follow the language`() {
        val plurals = problems.filterIsInstance<Problem.MissingPluralCategories>()
        assertEquals(listOf(pl), plurals.map { it.locale })
        assertEquals(setOf(PluralCategory.FEW, PluralCategory.MANY), plurals.single().missing)
    }

    @Test
    fun `array sizes and keys left behind`() {
        assertEquals(listOf(de), problems.filterIsInstance<Problem.ArraySizeMismatch>().map { it.locale })
        assertTrue(problems.any { it is Problem.NotInSource && it.key.name == "old_key" })
        assertEquals("old_key", set.keys.last().name)
    }

    @Test
    fun `placeholders skip escaped percent and number plain ones by position`() {
        assertEquals(mapOf(1 to 's', 2 to 'd'), Placeholders.signature("100%% of %s in %d"))
        assertEquals(mapOf(1 to 'f'), Placeholders.signature("%1\$.2f km"))
    }

    @Test
    fun `plural rules`() {
        assertEquals(setOf(PluralCategory.OTHER), PluralRules.categories(Locale("ja")))
        assertEquals(PluralCategory.entries.toSet(), PluralRules.categories(Locale("ar")))
        assertEquals(setOf(PluralCategory.ONE, PluralCategory.OTHER), PluralRules.categories(Locale("ur")))
    }
}

class ProChecksTest {

    private fun set(source: String, german: String) = TranslationSet(
        "/res",
        listOf(
            StringsFile("/res/values/strings.xml", FolderQualifiers(null), StringsXmlParser.parse("<resources>$source</resources>")),
            StringsFile("/res/values-de/strings.xml", FolderQualifiers(Locale("de")), StringsXmlParser.parse("<resources>$german</resources>")),
        ),
    )

    @Test
    fun `escapes, untranslated and length`() {
        val problems = Checks.run(set(
            """<string name="a">Line\none é It\'s</string><string name="b">Open settings</string><string name="c">OK</string><string name="d">Save</string>""",
            """<string name="a">Zeile\rzwei</string><string name="b">Open settings</string><string name="c">OK</string><string name="d">Speichern und alle Änderungen übernehmen</string>""",
        ))
        assertEquals(listOf("a" to "\\r"), problems.filterIsInstance<Problem.UnknownEscape>().map { it.key.name to it.escape })
        assertEquals(listOf("b"), problems.filterIsInstance<Problem.Untranslated>().map { it.key.name })
        assertEquals(listOf("d"), problems.filterIsInstance<Problem.TooLong>().map { it.key.name })
    }

    @Test
    fun `free tier checks only what the free plan lists`() {
        val s = set(
            """<plurals name="files"><item quantity="one">One</item><item quantity="other">%d</item></plurals><string name="b">Open settings</string>""",
            """<plurals name="files"><item quantity="other">%d Dateien</item></plurals><string name="b">Open settings</string>""",
        )
        assertTrue(Checks.run(s).any { it is Problem.MissingPluralCategories })
        assertTrue(Checks.run(s, CheckOptions.FREE).none { it is Problem.MissingPluralCategories || it is Problem.Untranslated })
    }

    @Test
    fun `unused keys`() {
        val s = set("""<string name="used">A</string><string name="unused">B</string>""", "")
        val unused = Checks.run(s, CheckOptions(usedKeys = setOf(ResourceKey(ResourceKind.STRING, "used")))).filterIsInstance<Problem.Unused>()
        assertEquals(listOf("unused"), unused.map { it.key.name })
    }
}
