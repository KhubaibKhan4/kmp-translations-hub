package io.github.translationshub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationPlanTest {

    @Test
    fun `placeholders, markup and escapes are protected and restored`() {
        val text = "Hi %1\$s, <b>%2\$d</b> new & \\'fresh\\'\\n"
        for (style in Protection.Style.entries) {
            val p = Protection.protect(text, style)
            assertEquals(7, p.tokens.size, style.name)
            // A service that keeps tokens but reorders the words:
            val translated = when (style) {
                Protection.Style.XML_TAGS -> p.text.replace("Hi", "Hallo").replace("new &amp;", "neu &amp;")
                Protection.Style.BRACKETS -> p.text.replace("Hi", "Hallo").replace("new &", "neu &")
            }
            assertEquals("Hallo %1\$s, <b>%2\$d</b> neu & \\'fresh\\'\\n", Protection.restore(translated, p, style), style.name)
        }
    }

    @Test
    fun `a lost or duplicated token rejects the result`() {
        val p = Protection.protect("Hello %1\$s", Protection.Style.BRACKETS)
        assertNull(Protection.restore("Hallo", p, Protection.Style.BRACKETS))
        assertNull(Protection.restore("Hallo ⟦0⟧ ⟦0⟧", p, Protection.Style.BRACKETS))
        assertEquals("Hallo %1\$s", Protection.restore("Hallo ⟦ 0 ⟧", p, Protection.Style.BRACKETS))
    }

    @Test
    fun `plan covers the plural forms the target language needs`() {
        val set = TranslationSet("/res", listOf(
            StringsFile("/res/values/strings.xml", FolderQualifiers(null), StringsXmlParser.parse(
                """<resources>
                    <!-- Unread badge -->
                    <plurals name="files"><item quantity="one">One file</item><item quantity="other">%d files</item></plurals>
                    <string-array name="days"><item>Mon</item><item>Tue</item></string-array>
                    <string name="brand" translatable="false">Acme</string>
                    <string name="hi">Hi</string>
                </resources>""")),
        ))
        val keys = set.keys
        val plan = TranslationPlan.build(set, Locale("pl"), keys)
        assertEquals(
            listOf("files:one=One file", "files:few=%d files", "files:many=%d files", "files:other=%d files", "days:0=Mon", "days:1=Tue", "hi=Hi"),
            plan.segments.map { s ->
                when (val part = s.part) {
                    is TranslationPlan.Part.Plural -> "${s.key.name}:${part.category.xmlName}=${s.text}"
                    is TranslationPlan.Part.Item -> "${s.key.name}:${part.index}=${s.text}"
                    TranslationPlan.Part.Whole -> "${s.key.name}=${s.text}"
                }
            },
        )
        assertEquals("Unread badge", plan.segments.first().context)

        val results = listOf("1 plik", "%d pliki", "%d plików", "%d pliku", "Pn", null, "Cześć")
        val values = plan.assemble(results)
        assertEquals(PluralsValue(mapOf(PluralCategory.ONE to "1 plik", PluralCategory.FEW to "%d pliki", PluralCategory.MANY to "%d plików", PluralCategory.OTHER to "%d pliku")),
            values[ResourceKey(ResourceKind.PLURALS, "files")])
        assertNull(values[ResourceKey(ResourceKind.ARRAY, "days")], "a failed item leaves the whole array out")
        assertEquals(TextValue("Cześć"), values[ResourceKey(ResourceKind.STRING, "hi")])
    }
}
