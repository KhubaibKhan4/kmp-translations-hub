package io.github.translationshub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationLockTest {

    private val de = Locale("de")
    private val hello = ResourceKey(ResourceKind.STRING, "hello")
    private val bye = ResourceKey(ResourceKind.STRING, "bye")
    private val brand = ResourceKey(ResourceKind.STRING, "brand")

    private fun set(source: String, german: String) = TranslationSet(
        "/res",
        listOf(
            StringsFile("/res/values/strings.xml", FolderQualifiers(null), StringsXmlParser.parse("<resources>$source</resources>")),
            StringsFile("/res/values-de/strings.xml", FolderQualifiers(de), StringsXmlParser.parse("<resources>$german</resources>")),
        ),
    )

    private val v1 = set(
        """<string name="hello">Hello</string><string name="bye">Bye</string><string name="brand" translatable="false">Acme</string>""",
        """<string name="hello">Hallo</string>""",
    )

    @Test
    fun `first run adopts what exists and only the missing key is pending`() {
        val lock = TranslationLock()
        assertEquals(TranslationState.UNTRACKED, lock.status(v1, hello, de).state)
        lock.adoptUntracked(v1)
        assertEquals(TranslationStatus(TranslationState.UP_TO_DATE, Origin.Human), lock.status(v1, hello, de))
        assertEquals(listOf(bye), lock.pending(v1, de))
    }

    @Test
    fun `a changed source makes machine translations pending and human ones need review`() {
        val lock = TranslationLock()
        lock.put(hello, de, TextValue("Hello"), TextValue("Hallo"), Origin.Machine("deepl"))
        lock.put(bye, de, TextValue("Bye"), TextValue("Tschüss"), Origin.Human)
        val v2 = set(
            """<string name="hello">Hello there</string><string name="bye">Goodbye</string>""",
            """<string name="hello">Hallo</string><string name="bye">Tschüss</string>""",
        )
        val helloStatus = lock.status(v2, hello, de)
        assertEquals(TranslationStatus(TranslationState.STALE, Origin.Machine("deepl")), helloStatus)
        assertTrue(helloStatus.needsMachineTranslation)

        val byeStatus = lock.status(v2, bye, de)
        assertFalse(byeStatus.needsMachineTranslation)
        assertTrue(byeStatus.needsReview)
        assertEquals(listOf(hello), lock.pending(v2, de))
    }

    @Test
    fun `editing a machine translation by hand makes it human`() {
        val lock = TranslationLock()
        lock.put(hello, de, TextValue("Hello"), TextValue("Hallo"), Origin.Machine("apple"))
        val edited = set("""<string name="hello">Hello</string>""", """<string name="hello">Servus</string>""")
        assertEquals(TranslationStatus(TranslationState.UP_TO_DATE, Origin.Human), lock.status(edited, hello, de))
    }

    @Test
    fun `untranslatable keys are never pending`() {
        assertFalse(brand in TranslationLock().pending(v1, de))
    }

    @Test
    fun `json round-trip, sorted and one entry per line`() {
        val lock = TranslationLock()
        lock.put(hello, de, TextValue("Hello"), TextValue("Hallo"), Origin.Machine("deepl"))
        lock.put(bye, de, TextValue("Bye"), TextValue("Tschüss"), Origin.Human)
        lock.put(ResourceKey(ResourceKind.PLURALS, "files"), Locale("pt", region = "BR"),
            PluralsValue(mapOf(PluralCategory.OTHER to "%d files")), PluralsValue(mapOf(PluralCategory.OTHER to "%d arquivos")),
            Origin.Machine("mymemory"))
        val json = lock.toJson()
        val lines = json.lines()
        assertTrue(lines.indexOfFirst { "string/bye" in it } < lines.indexOfFirst { "string/hello" in it })
        assertTrue(lines.any { it.trim().startsWith("\"plurals/files\": {\"source\": ") })

        val read = TranslationLock.fromJson(json)
        assertEquals(json, read.toJson())
        assertEquals(Origin.Machine("deepl"), read.record(hello, de)!!.origin)
        assertEquals(Origin.Human, read.record(bye, de)!!.origin)
    }

    @Test
    fun `prune drops removed keys and locales`() {
        val lock = TranslationLock()
        lock.put(ResourceKey(ResourceKind.STRING, "gone"), de, TextValue("x"), TextValue("y"), Origin.Human)
        lock.put(hello, Locale("fr"), TextValue("Hello"), TextValue("Bonjour"), Origin.Human)
        lock.put(hello, de, TextValue("Hello"), TextValue("Hallo"), Origin.Human)
        lock.prune(v1)
        assertEquals("{\n  \"version\": 1,\n  \"translations\": {\n    \"de\": {\n      \"string/hello\": " +
            "{\"source\": \"${TranslationLock.hash(TextValue("Hello"))}\", \"text\": \"${TranslationLock.hash(TextValue("Hallo"))}\", " +
            "\"by\": \"human\"}\n    }\n  }\n}\n", lock.toJson())
    }
}
