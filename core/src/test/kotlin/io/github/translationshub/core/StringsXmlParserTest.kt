package io.github.translationshub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StringsXmlParserTest {

    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
            <!-- Shown on the home screen -->
            <string name="greeting">Hello, %1${'$'}s!</string>
            <string name="escaped">It\'s Tom &amp; Jerry\n&lt;3</string>
            <string name="app_id" translatable="false">khatapay</string>
            <string name="styled">Tap <b>here</b> for <xliff:g id="count">%d</xliff:g></string>

            <!-- Section comment, separated by a blank line -->

            <plurals name="files">
                <item quantity="one">One file</item>
                <item quantity="other">%d files</item>
            </plurals>
            <string-array name="days">
                <item>Mon</item>
                <item>Tue</item>
            </string-array>
            <color name="ignored">#fff</color>
            <string name="after_color">Still read</string>
        </resources>
    """.trimIndent()

    private val entries = StringsXmlParser.parse(xml).associateBy { it.key }

    private fun string(name: String) = entries.getValue(ResourceKey(ResourceKind.STRING, name))

    @Test
    fun `strings keep Compose escapes and decode XML entities`() {
        assertEquals(TextValue("Hello, %1\$s!"), string("greeting").value)
        assertEquals(TextValue("It\\'s Tom & Jerry\\n<3"), string("escaped").value)
    }

    @Test
    fun `inline markup is kept as written`() {
        assertEquals(TextValue("Tap <b>here</b> for <xliff:g id=\"count\">%d</xliff:g>"), string("styled").value)
    }

    @Test
    fun `comment right above an entry is its context`() {
        assertEquals("Shown on the home screen", string("greeting").comment)
        assertNull(string("escaped").comment)
        assertNull(entries.getValue(ResourceKey(ResourceKind.PLURALS, "files")).comment)
    }

    @Test
    fun `translatable false`() {
        assertEquals(false, string("app_id").translatable)
        assertEquals(true, string("greeting").translatable)
    }

    @Test
    fun `plurals and arrays`() {
        assertEquals(
            PluralsValue(mapOf(PluralCategory.ONE to "One file", PluralCategory.OTHER to "%d files")),
            entries.getValue(ResourceKey(ResourceKind.PLURALS, "files")).value,
        )
        assertEquals(ArrayValue(listOf("Mon", "Tue")), entries.getValue(ResourceKey(ResourceKind.ARRAY, "days")).value)
    }

    @Test
    fun `other resource types are skipped without losing what follows`() {
        assertEquals(TextValue("Still read"), string("after_color").value)
        assertEquals(7, entries.size)
    }
}
