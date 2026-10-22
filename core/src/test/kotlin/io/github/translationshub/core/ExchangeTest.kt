package io.github.translationshub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExchangeTest {

    private val de = Locale("de")

    private val set = TranslationSet(
        "/res",
        listOf(
            StringsFile("/res/values/strings.xml", FolderQualifiers(null), StringsXmlParser.parse(
                """<resources>
                    <!-- Greets the user -->
                    <string name="hello">Hello, %1${'$'}s</string>
                    <string name="quote">Say "hi", then go</string>
                    <string name="brand" translatable="false">Acme</string>
                    <plurals name="files"><item quantity="one">One file</item><item quantity="other">%d files</item></plurals>
                    <string-array name="days"><item>Mon</item><item>Tue</item></string-array>
                </resources>""",
            )),
            StringsFile("/res/values-de/strings.xml", FolderQualifiers(de), StringsXmlParser.parse(
                """<resources>
                    <string name="hello">Hallo, %1${'$'}s</string>
                    <plurals name="files"><item quantity="one">Eine Datei</item><item quantity="other">%d Dateien</item></plurals>
                    <string-array name="days"><item>Mo</item><item>Di</item></string-array>
                </resources>""",
            )),
        ),
    )

    @Test
    fun `android-quoted strings are read in the form compose shows`() {
        assertEquals("Start", Exchange.unquote("\"Start\""))
        assertEquals("Estás \\'offline\\'", Exchange.unquote("\"Estás 'offline'\""))
        assertEquals("Say \\\"hi\\\"", Exchange.unquote("\"Say \\\"hi\\\"\""))
        assertEquals("Already \\'escaped\\'", Exchange.unquote("\"Already \\'escaped\\'\""))
        assertEquals("not \"quoted", Exchange.unquote("not \"quoted"))
        assertEquals("ends with \\\"", Exchange.unquote("ends with \\\""))
        val xml = "<resources><string name=\"hello\">\"Hallo, %1\$s\"</string>" +
            "<plurals name=\"files\"><item quantity=\"one\">\"Eine Datei\"</item></plurals>" +
            "<string name=\"days[0]\">\"Mo\"</string></resources>"
        val values = Exchange.fromStringsXml(xml, set, de).values
        assertEquals(TextValue("Hallo, %1\$s"), values[ResourceKey(ResourceKind.STRING, "hello")])
        assertEquals("Eine Datei", (values[ResourceKey(ResourceKind.PLURALS, "files")] as PluralsValue).items[PluralCategory.ONE])
        assertEquals("Mo", (values[ResourceKey(ResourceKind.ARRAY, "days")] as ArrayValue).items[0])
    }

    @Test
    fun `arrays written as one string per item read back as the array`() {
        val xml = Exchange.toStringsXml(set, de, arraysAsStrings = true)
        assertTrue("<string name=\"days[1]\">Di</string>" in xml)
        assertTrue("string-array" !in xml)
        val back = Exchange.fromStringsXml(xml.replace(">Di<", ">Dienstag<"), set, de)
        assertEquals(ArrayValue(listOf("Mo", "Dienstag")), back.values[ResourceKey(ResourceKind.ARRAY, "days")])
        assertEquals(TextValue("Hallo, %1${'$'}s"), back.values[ResourceKey(ResourceKind.STRING, "hello")])
        assertEquals(emptyList<String>(), back.skipped)
    }

    @Test
    fun `csv lists every segment and reads back`() {
        val csv = Exchange.toCsv(set)
        val rows = Exchange.parseCsv(csv)
        assertEquals(listOf("key", "default", "de"), rows[0])
        assertEquals(listOf("hello", "quote", "files#one", "files#other", "days[0]", "days[1]"), rows.drop(1).map { it[0] })
        assertTrue("\"Say \"\"hi\"\", then go\"" in csv)

        val edited = csv.replace("Hallo, %1\$s", "Servus, %1\$s").replace("\"Say \"\"hi\"\", then go\",", "\"Say \"\"hi\"\", then go\",\"Sag \"\"hallo\"\", dann geh\"")
        val imported = Exchange.fromCsv(edited, set).getValue(de)
        assertEquals(TextValue("Servus, %1\$s"), imported.values[ResourceKey(ResourceKind.STRING, "hello")])
        assertEquals(TextValue("Sag \"hallo\", dann geh"), imported.values[ResourceKey(ResourceKind.STRING, "quote")])
        assertEquals(ArrayValue(listOf("Mo", "Di")), imported.values[ResourceKey(ResourceKind.ARRAY, "days")])
    }

    @Test
    fun `xliff has source, target and note, and a partial file keeps the other plural forms`() {
        val xliff = Exchange.toXliff(set, de)
        assertTrue("target-language=\"de\"" in xliff)
        assertTrue("<trans-unit id=\"files#few\">" !in xliff)
        assertTrue("<source>Hello, %1\$s</source>\n        <target>Hallo, %1\$s</target>\n        <note>Greets the user</note>" in xliff)
        assertTrue("<source>Say &quot;hi&quot;, then go</source>\n      </trans-unit>" in xliff.replace("        <note>", "<note>"))

        val partial = """<xliff version="1.2"><file target-language="de"><body>
            <trans-unit id="files#other"><source>%d files</source><target>%d Dokumente</target></trans-unit>
            <trans-unit id="gone"><source>x</source><target>y</target></trans-unit>
        </body></file></xliff>"""
        val (locale, imported) = Exchange.fromXliff(partial, set)
        assertEquals(de, locale)
        assertEquals(
            PluralsValue(mapOf(PluralCategory.ONE to "Eine Datei", PluralCategory.OTHER to "%d Dokumente")),
            imported.values[ResourceKey(ResourceKind.PLURALS, "files")],
        )
        assertEquals(listOf("gone"), imported.skipped)
    }

    @Test
    fun `strings xml round-trip, untranslatable keys left out`() {
        val xml = Exchange.toStringsXml(set, de)
        val imported = Exchange.fromStringsXml(xml, set)
        assertEquals(set.keys.mapNotNull { k -> set.translation(k, de)?.let { k to it.value } }.toMap(), imported.values)
        val android = Exchange.fromStringsXml("<resources><string name=\"brand\">X</string><string name=\"extra\">Y</string></resources>", set)
        assertTrue(android.values.isEmpty())
        assertEquals(listOf("extra"), android.skipped)
    }
}
