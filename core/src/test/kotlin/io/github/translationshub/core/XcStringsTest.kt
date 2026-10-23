package io.github.translationshub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XcStringsTest {

    private val text = XcStringsTest::class.java.getResource("/Sample.xcstrings")!!.readText()
    private val de = Locale("de")
    private val fr = Locale("fr")
    private fun string(name: String) = ResourceKey(ResourceKind.STRING, name)

    @Test
    fun `an untouched catalog comes back byte for byte`() {
        assertEquals(text, XcStrings.parse(text).toJson())
        assertEquals(text.removeSuffix("\n"), XcStrings.parse(text.removeSuffix("\n")).toJson())
    }

    @Test
    fun `languages become columns, new ones count as missing`() {
        val set = XcStrings.parse(text).toSet("/app/Localizable.xcstrings")
        assertEquals(ResourceFormat.IOS, set.format)
        assertEquals(listOf(de, fr), set.locales)
        assertEquals(TextValue("Hello, %@!"), set.source(string("Hello, %@!"))!!.value)
        assertEquals(TextValue("Hallo, %@!"), set.translation(string("Hello, %@!"), de)!!.value)
        assertNull(set.translation(string("Hello, %@!"), fr), "a new localization isn't a translation yet")
        assertEquals("Shown above the feed", set.source(ResourceKey(ResourceKind.PLURALS, "%lld new posts"))!!.comment)
        val plural = set.translation(ResourceKey(ResourceKind.PLURALS, "%lld new posts"), de)!!.value as PluralsValue
        assertEquals("%lld neue Beiträge", plural.items[PluralCategory.OTHER])
        // A key with no source localization shows its key as the text.
        assertEquals(TextValue("🙂"), set.source(string("🙂"))!!.value)
        assertEquals("Tap \"Save\"\nto keep it", set.source(string("Tap \"Save\"\nto keep it"))!!.name)
    }

    @Test
    fun `stale, shouldTranslate false and device variations aren't translated`() {
        val set = XcStrings.parse(text).toSet("/x.xcstrings")
        for (key in listOf("Sign out", "Twine", "Uses %@ on %@")) assertFalse(set.source(string(key))!!.translatable, key)
        assertTrue(set.source(string("Hello, %@!"))!!.translatable)
        assertEquals(setOf(string("Hello, %@!"), string("Tap \"Save\"\nto keep it"), string("🙂"), ResourceKey(ResourceKind.PLURALS, "%lld new posts")),
            TranslationLock().pending(set, fr).toSet())
    }

    @Test
    fun `setting a value changes only its lines and marks it translated`() {
        val catalog = XcStrings.parse(text)
        catalog.set("Hello, %@!", fr, TextValue("Bonjour, %@ !"))
        val changed = catalog.toJson().lines()
        val original = text.lines()
        assertEquals(original.size, changed.size)
        val diff = original.indices.filter { original[it] != changed[it] }.map { original[it].trim() to changed[it].trim() }
        assertEquals(listOf("\"state\" : \"new\"," to "\"state\" : \"translated\",", "\"value\" : \"Hello, %@!\"" to "\"value\" : \"Bonjour, %@ !\""), diff)
        assertEquals(TextValue("Bonjour, %@ !"), XcStrings.parse(catalog.toJson()).toSet("/x").translation(string("Hello, %@!"), fr)!!.value)
    }

    @Test
    fun `plurals are written in Xcode's order and round-trip`() {
        val catalog = XcStrings.parse(text)
        val polish = Locale("pl")
        catalog.set("%lld new posts", polish, PluralsValue(mapOf(
            PluralCategory.OTHER to "%lld postu", PluralCategory.ONE to "%lld post", PluralCategory.FEW to "%lld posty", PluralCategory.MANY to "%lld postów")))
        val json = catalog.toJson()
        val forms = Regex(""""(few|many|one|other)" : \{""").findAll(json.substringAfter("\"pl\" : {")).map { it.groupValues[1] }.take(4).toList()
        assertEquals(listOf("few", "many", "one", "other"), forms)
        val back = XcStrings.parse(json).toSet("/x").translation(ResourceKey(ResourceKind.PLURALS, "%lld new posts"), polish)!!.value as PluralsValue
        assertEquals("%lld postów", back.items[PluralCategory.MANY])
    }

    @Test
    fun `a new language gets new entries with the source text, like Xcode`() {
        val catalog = XcStrings.parse(text)
        catalog.addLocale(Locale("ja"))
        val set = XcStrings.parse(catalog.toJson()).toSet("/x")
        assertTrue(Locale("ja") in set.locales)
        assertNull(set.translation(string("Hello, %@!"), Locale("ja")))
        assertTrue(catalog.toJson().contains("\"ja\" : {\n          \"stringUnit\" : {\n            \"state\" : \"new\",\n            \"value\" : \"Hello, %@!\""))
        assertFalse(catalog.toJson().substringAfter("\"Twine\" : {").substringBefore("}").contains("ja"))
    }

    @Test
    fun `keys are renamed in place, deleted and added`() {
        val catalog = XcStrings.parse(text)
        catalog.rename("Sign out", "Log out")
        catalog.delete("🙂")
        catalog.add("Retry", TextValue("Retry"))
        val keys = XcStrings.parse(catalog.toJson()).toSet("/x").keys.map { it.name }
        assertEquals(listOf("%lld new posts", "Hello, %@!", "Log out", "Tap \"Save\"\nto keep it", "Twine", "Uses %@ on %@", "Retry"), keys)
    }

    @Test
    fun `iOS placeholders are compared like the others`() {
        assertEquals(Placeholders.signature("%1\$@ has %2\$lld"), Placeholders.signature("%2\$lld bei %1\$@"))
        assertEquals(mapOf(1 to 'd'), Placeholders.signature("%lld"))
        assertEquals(Placeholders.signature("%d"), Placeholders.signature("%ld"))
        assertEquals(mapOf(1 to '@', 2 to '@'), Placeholders.signature("%@ on %@"))
        assertTrue(Protection.protect("Hi %@, %lld left", Protection.Style.BRACKETS).tokens == listOf("%@", "%lld"))
        // A percent sign in text isn't a placeholder.
        assertEquals(emptyMap(), Placeholders.signature("the limit of 80% is reached, 50% sure, % of"))
    }

    @Test
    fun `a translation keeps the source's leading and trailing spaces`() {
        assertEquals(" • %@", Placeholders.keepEdgeWhitespace(" • %@", "• %@"))
        assertEquals(" • %d Min. ", Placeholders.keepEdgeWhitespace(" • %dm read ", "  • %d Min."))
        assertEquals("Hallo", Placeholders.keepEdgeWhitespace("Hello", " Hallo "))
        assertEquals("  ", Placeholders.keepEdgeWhitespace("  ", ""))
    }
}
