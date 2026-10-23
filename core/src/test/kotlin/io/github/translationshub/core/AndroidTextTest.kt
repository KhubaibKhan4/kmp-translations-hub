package io.github.translationshub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidTextTest {

    @Test
    fun `quotes and a leading at sign are escaped, once`() {
        assertEquals("Don\\'t stop", AndroidText.escape("Don't stop"))
        assertEquals("Say \\\"hi\\\"", AndroidText.escape("Say \"hi\""))
        assertEquals("\\@home and @work", AndroidText.escape("@home and @work"))
        assertEquals("\\?", AndroidText.escape("?"))
        for (already in listOf("Don\\'t stop", "Say \\\"hi\\\"", "\\@home", "Line\\nbreak")) {
            assertEquals(already, AndroidText.escape(already), already)
        }
    }

    @Test
    fun `references, attributes, markup and quoted strings are left alone`() {
        for (text in listOf("@string/app_name", "@android:string/ok", "?attr/colorPrimary", "\"It's here\"")) {
            assertEquals(text, AndroidText.escape(text), text)
        }
        assertEquals("<a href=\"https://example.com\">Don\\'t</a>", AndroidText.escape("<a href=\"https://example.com\">Don't</a>"))
        assertEquals("%1\$s\\'s cart", AndroidText.escape("%1\$s's cart"))
    }

    @Test
    fun `unescaped quotes are found outside markup and quoted strings`() {
        assertEquals('\'', AndroidText.unescapedQuote("Don't"))
        assertEquals('"', AndroidText.unescapedQuote("Say \"hi"))
        assertNull(AndroidText.unescapedQuote("Don\\'t"))
        assertNull(AndroidText.unescapedQuote("\"It's here\""))
        assertNull(AndroidText.unescapedQuote("<b class=\"x\">Bold</b>"))
        assertTrue(AndroidText.isReference(" @string/app_name "))
        assertTrue(AndroidText.isReferenceValue(ArrayValue(listOf("@string/none", "1080p"))))
        assertTrue(!AndroidText.isReferenceValue(ArrayValue(listOf("Today", "Tomorrow"))))
    }

    @Test
    fun `android sets report unescaped quotes, compose sets don't`() {
        val de = Locale("de")
        fun set(format: ResourceFormat) = TranslationSet("/res", listOf(
            StringsFile("/res/values/strings.xml", FolderQualifiers(null), StringsXmlParser.parse("<resources><string name=\"a\">It's</string></resources>")),
            StringsFile("/res/values-de/strings.xml", FolderQualifiers(de), StringsXmlParser.parse("<resources><string name=\"a\">Es ist's</string></resources>")),
        ), format)
        val android = Checks.run(set(ResourceFormat.ANDROID), CheckOptions.FREE).filterIsInstance<Problem.UnescapedQuote>()
        assertEquals(listOf(null, de), android.map { it.locale })
        assertEquals(emptyList(), Checks.run(set(ResourceFormat.COMPOSE)).filterIsInstance<Problem.UnescapedQuote>())
    }

    @Test
    fun `android qualifiers aren't taken for languages`() {
        for (folder in listOf("values-night", "values-v21", "values-land", "values-sw600dp", "values-tv", "values-car", "values-ldrtl")) {
            assertNull(FolderQualifiers.parse(folder)!!.locale, folder)
        }
        assertEquals(Locale("de"), FolderQualifiers.parse("values-de-night")!!.locale)
        assertEquals(Locale("pt", region = "BR"), FolderQualifiers.parse("values-pt-rBR")!!.locale)
        assertEquals(ResourceFormat.ANDROID, ResourceFormat.ofRoot("res"))
    }

    @Test
    fun `untranslatable files follow android's conventions`() {
        assertTrue(AndroidText.isUntranslatableFile("donottranslate.xml", "<resources/>"))
        assertTrue(AndroidText.isUntranslatableFile("settings_keys.xml", "<resources xmlns:tools=\"t\" translatable=\"false\">"))
        assertTrue(!AndroidText.isUntranslatableFile("strings.xml", "<resources tools:ignore=\"MissingTranslation\">"))
        assertTrue(!AndroidText.isUntranslatableFile("strings.xml", "<resources><string name=\"a\" translatable=\"false\">A</string></resources>"))
    }

    @Test
    fun `an apostrophe fails the build, a double quote is only dropped`() {
        assertTrue(Problem.UnescapedQuote(ResourceKey(ResourceKind.STRING, "a"), null, '\'').failsBuild)
        assertTrue(!Problem.UnescapedQuote(ResourceKey(ResourceKind.STRING, "a"), null, '"').failsBuild)
    }

    @Test
    fun `a translation may keep the source's markup but not add any`() {
        assertTrue(Placeholders.addsNoMarkup("Read <b>this</b>", "Lies <b>das</b>"))
        assertTrue(Placeholders.addsNoMarkup("1440p", "1440p"))
        assertTrue(!Placeholders.addsNoMarkup("1440p", "<string name=\"cq\">1440P</string>"))
        assertTrue(!Placeholders.addsNoMarkup("Read <b>this</b>", "<b>Lies</b> <b>das</b>"))
        assertTrue(Placeholders.addsNoMarkup("5 < 6", "5 < 6"))
    }
}
