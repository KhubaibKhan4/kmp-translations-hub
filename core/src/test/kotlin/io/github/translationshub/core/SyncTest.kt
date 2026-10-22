package io.github.translationshub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {

    private val de = Locale("de")
    private fun key(name: String) = ResourceKey(ResourceKind.STRING, name)

    private fun set(german: String) = TranslationSet(
        "/res",
        listOf(
            StringsFile("/res/values/strings.xml", FolderQualifiers(null), StringsXmlParser.parse(
                """<resources>
                    <!-- Shown on the home screen -->
                    <string name="hello">Hello</string>
                    <string name="bye">Bye</string>
                    <string name="cart">Cart</string>
                    <string name="brand" translatable="false">Acme</string>
                </resources>""",
            )),
            StringsFile("/res/values-de/strings.xml", FolderQualifiers(de), StringsXmlParser.parse("<resources>$german</resources>")),
        ),
    )

    private fun actions(set: TranslationSet, lock: TranslationLock, remote: Map<String, String>) =
        Sync.planPull(set, lock, de, remote.entries.associate { key(it.key) to TextValue(it.value) }).associate { it.key.name to it.action }

    @Test
    fun `first pull writes new and machine translations, flags a person's that differs`() {
        val set = set("""<string name="hello">Hallo</string><string name="bye">Tschüss</string><string name="cart">Wagen</string>""")
        val lock = TranslationLock().apply {
            adoptUntracked(set)
            put(key("cart"), de, TextValue("Cart"), TextValue("Wagen"), Origin.Machine("deepl"))
        }
        val result = actions(set, lock, mapOf("hello" to "Hallo", "bye" to "Auf Wiedersehen", "cart" to "Warenkorb", "brand" to "Acme"))
        assertEquals(mapOf("hello" to Sync.Action.SAME, "bye" to Sync.Action.CONFLICT, "cart" to Sync.Action.WRITE), result)
    }

    @Test
    fun `after a sync, the side that changed wins and both changing is a conflict`() {
        val set = set("""<string name="hello">Hallo!</string><string name="bye">Tschüss</string><string name="cart">Korb</string>""")
        val lock = TranslationLock().apply {
            markSynced(key("hello"), de, TextValue("Hello"), TextValue("Hallo"))
            markSynced(key("bye"), de, TextValue("Bye"), TextValue("Tschüss"))
            markSynced(key("cart"), de, TextValue("Cart"), TextValue("Warenkorb"))
            // Edited in the IDE since: hello and cart.
            put(key("hello"), de, TextValue("Hello"), TextValue("Hallo!"), Origin.Human)
            put(key("cart"), de, TextValue("Cart"), TextValue("Korb"), Origin.Human)
        }
        val result = actions(set, lock, mapOf("hello" to "Hallo", "bye" to "Ciao", "cart" to "Einkaufswagen"))
        assertEquals(mapOf("hello" to Sync.Action.KEEP_LOCAL, "bye" to Sync.Action.WRITE, "cart" to Sync.Action.CONFLICT), result)
    }

    @Test
    fun `missing translations are written and empty ones on the platform are ignored`() {
        val set = set("")
        val result = actions(set, TranslationLock(), mapOf("hello" to "Hallo", "bye" to " "))
        assertEquals(mapOf("hello" to Sync.Action.WRITE), result)
    }

    @Test
    fun `upload sends only translations the platform doesn't have as they are`() {
        val set = set("""<string name="hello">Hallo</string><string name="bye">Tschüss</string>""")
        val lock = TranslationLock().apply { markSynced(key("hello"), de, TextValue("Hello"), TextValue("Hallo")) }
        assertEquals(setOf(key("bye")), Sync.pendingUpload(set, lock, de))
        lock.put(key("hello"), de, TextValue("Hello"), TextValue("Hallo!"), Origin.Human)
        assertEquals(setOf(key("bye")), Sync.pendingUpload(set, lock, de), "the IDE file still says Hallo")
    }

    @Test
    fun `synced hash survives edits and round-trips through JSON`() {
        val lock = TranslationLock()
        lock.markSynced(key("hello"), de, TextValue("Hello"), TextValue("Hallo"))
        lock.put(key("hello"), de, TextValue("Hello"), TextValue("Hallo!"), Origin.Human)
        val read = TranslationLock.fromJson(lock.toJson())
        assertEquals(TranslationLock.hash(TextValue("Hallo")), read.record(key("hello"), de)!!.syncedHash)
        assertTrue("\"synced\"" in lock.toJson())
        assertNull(TranslationLock.fromJson(TranslationLock().apply { put(key("a"), de, TextValue("A"), TextValue("B"), Origin.Human) }.toJson()).record(key("a"), de)!!.syncedHash)
    }

    @Test
    fun `locales match exactly, by language, or come back as new`() {
        val local = listOf(Locale("de"), Locale("es"), Locale("pt", region = "BR"))
        val matched = Sync.matchLocales(local, listOf("en", "de", "es-ES", "pt_BR", "fr", "zh-CN", "not a code"), source = "en")
        assertEquals(
            mapOf(Locale("de") to "de", Locale("pt", region = "BR") to "pt_BR", Locale("es") to "es-ES", Locale("fr") to "fr", Locale("zh", region = "CN") to "zh-CN"),
            matched,
        )
        // Two regions for one language: neither is guessed to be the plain one.
        val two = Sync.matchLocales(listOf(Locale("es")), listOf("es-ES", "es-MX"), source = "en")
        assertEquals(setOf(Locale("es", region = "ES"), Locale("es", region = "MX")), two.keys)
    }

    @Test
    fun `source export keeps comments and leaves out untranslatable keys`() {
        val xml = Exchange.toStringsXml(set(""), null)
        assertTrue("<!-- Shown on the home screen -->" in xml)
        assertTrue("brand" !in xml)
        assertEquals(listOf("hello", "bye", "cart"), StringsXmlParser.parse(xml).map { it.name })
        assertEquals(listOf("bye"), StringsXmlParser.parse(Exchange.toStringsXml(set(""), null, setOf(key("bye")))).map { it.name })
    }

    @Test
    fun `remote file names come from the module and source set`() {
        assertEquals("composeApp-commonMain-strings.xml", Sync.remoteFileName("composeApp/src/commonMain/composeResources"))
        assertEquals("shared-ui-androidMain-strings.xml", Sync.remoteFileName("shared/ui/src/androidMain/composeResources"))
        assertEquals("strings.xml", Sync.remoteFileName("composeResources"))
    }

    @Test
    fun `keeping your own over the platform's makes it the newer change`() {
        val set = set("""<string name="hello">Hallo!</string>""")
        val lock = TranslationLock().apply { markSynced(key("hello"), de, TextValue("Hello"), TextValue("Hallo!"), platform = TextValue("Servus")) }
        assertEquals(mapOf("hello" to Sync.Action.KEEP_LOCAL), actions(set, lock, mapOf("hello" to "Servus")))
        assertEquals(setOf(key("hello")), Sync.pendingUpload(set, lock, de))
    }

    @Test
    fun `sync state belongs to one platform and survives JSON`() {
        val set = set("""<string name="hello">Hallo</string>""")
        val lock = TranslationLock().apply { syncWith("lokalise:1"); markSynced(key("hello"), de, TextValue("Hello"), TextValue("Hallo")) }
        val read = TranslationLock.fromJson(lock.toJson())
        assertEquals("lokalise:1", read.syncedWith)
        assertEquals(emptySet(), Sync.pendingUpload(set, read, de))
        read.syncWith("poeditor:9")
        assertEquals(setOf(key("hello")), Sync.pendingUpload(set, read, de))
        assertNull(read.record(key("hello"), de)!!.syncedHash)
    }
}
