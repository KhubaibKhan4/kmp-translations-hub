package io.github.translationshub.idea

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.Exchange
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Origin
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.StringsXmlParser
import io.github.translationshub.core.TextValue
import io.github.translationshub.idea.sync.PlatformLanguages
import io.github.translationshub.idea.sync.SyncRunner
import io.github.translationshub.idea.sync.TranslationPlatform
import kotlinx.coroutines.runBlocking

/** A platform that keeps one file's strings per language in memory, the way the real ones merge uploads. */
private class FakePlatform(val targets: List<String>, override val syncKey: String = "fake:1") : TranslationPlatform {
    override val id = "fake"
    override val displayName = "Fake"
    val strings = mutableMapOf<String, MutableMap<String, String>>()
    val uploads = mutableListOf<Pair<String, Set<String>>>()

    override suspend fun languages() = PlatformLanguages("en", listOf("en") + targets)

    override suspend fun uploadSource(fileName: String, xml: String, source: String) = merge(source, xml)

    override suspend fun uploadTranslations(fileName: String, code: String, xml: String) = merge(code, xml)

    private fun merge(code: String, xml: String) {
        val entries = StringsXmlParser.parse(xml).associate { it.name to (it.value as TextValue).text }
        uploads += code to entries.keys
        strings.getOrPut(code) { mutableMapOf() }.putAll(entries)
    }

    override suspend fun download(fileName: String, codes: List<String>) = codes.associateWith { code ->
        // Like POEditor: a language with nothing translated is an empty file.
        strings[code]?.let { s -> "<resources>" + s.entries.joinToString("") { "<string name=\"${it.key}\">${it.value}</string>" } + "</resources>" } ?: ""
    }
}

class SyncRunnerTest : BasePlatformTestCase() {

    private val de = Locale("de")
    private val fr = Locale("fr")
    private fun key(name: String) = ResourceKey(ResourceKind.STRING, name)
    private lateinit var controller: TranslationsController

    override fun setUp() {
        super.setUp()
        fun add(folder: String, body: String): VirtualFile = myFixture.addFileToProject(
            "app/src/commonMain/composeResources/$folder/strings.xml",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body</resources>\n",
        ).virtualFile
        add("values", "    <string name=\"hello\">Hello</string>\n    <string name=\"bye\">Bye</string>\n    <string name=\"cart\">Cart</string>\n" +
            "    <string name=\"brand\" translatable=\"false\">Acme</string>\n")
        val root = add("values-de", "    <string name=\"bye\">Tschüss</string>\n    <string name=\"cart\">Wagen</string>\n").parent.parent
        controller = TranslationsController(project, root)
    }

    private fun sync(platform: FakePlatform, options: SyncRunner.Options = SyncRunner.Options(true, true, true)) = runBlocking {
        SyncRunner.run(platform, "app-commonMain-strings.xml", "en", options, controller::read, controller::lock,
            apply = { locale, values, synced -> controller.applySynced(platform.syncKey, locale, values, synced) })
    }

    private fun german(name: String) = (controller.read().translation(key(name), de)?.value as? TextValue)?.text

    fun `test first sync uploads source, downloads translators' work, keeps conflicts, and adds new languages`() {
        val platform = FakePlatform(listOf("de", "fr")).apply {
            strings["de"] = mutableMapOf("hello" to "Hallo", "bye" to "Auf Wiedersehen")
            strings["fr"] = mutableMapOf("hello" to "Bonjour")
        }
        val summary = sync(platform)

        assertEquals(3, summary.sourceStrings)
        assertEquals(mapOf("hello" to "Hello", "bye" to "Bye", "cart" to "Cart"), platform.strings["en"])
        // hello is new here; bye differs from the person's translation here: a conflict, kept and not uploaded.
        assertEquals("Hallo", german("hello"))
        assertEquals("Tschüss", german("bye"))
        assertEquals(listOf(key("bye")), summary.conflicts.map { it.key })
        assertEquals("Auf Wiedersehen", platform.strings["de"]!!["bye"])
        // cart only exists here: uploaded.
        assertEquals(mapOf(de to 1), summary.uploaded)
        assertEquals("Wagen", platform.strings["de"]!!["cart"])
        // fr exists only on the platform: the locale is added with its translation.
        assertTrue(fr in controller.read().locales)
        assertEquals(mapOf(de to 1, fr to 1), summary.downloaded)
        assertEquals(Origin.Human, controller.lock().record(key("hello"), de)!!.origin)
    }

    fun `test a second sync moves only what changed, each way`() {
        val platform = FakePlatform(listOf("de")).apply { strings["de"] = mutableMapOf("hello" to "Hallo") }
        sync(platform)
        platform.uploads.clear()

        val again = sync(platform, SyncRunner.Options(uploadSource = false, download = true, uploadTranslations = true))
        assertTrue("nothing to move: ${again}", again.downloaded.isEmpty() && again.uploaded.isEmpty() && again.conflicts.isEmpty())

        controller.setValue(key("cart"), de, TextValue("Einkaufswagen"))
        platform.strings["de"]!!["hello"] = "Hallo!"
        val third = sync(platform, SyncRunner.Options(uploadSource = false, download = true, uploadTranslations = true))
        assertEquals("Hallo!", german("hello"))
        assertEquals("Einkaufswagen", platform.strings["de"]!!["cart"])
        assertEquals(listOf("de" to setOf("cart")), platform.uploads)
        assertEquals(mapOf(de to 1), third.downloaded)
    }

    fun `test keeping mine over a conflict uploads it on the next sync`() {
        val platform = FakePlatform(listOf("de")).apply { strings["de"] = mutableMapOf("bye" to "Auf Wiedersehen") }
        val first = sync(platform)
        val conflict = first.conflicts.single()
        controller.applySynced(platform.syncKey, de, emptyMap(), mapOf(conflict.key to conflict.remote))

        sync(platform, SyncRunner.Options(uploadSource = false, download = true, uploadTranslations = true))
        assertEquals("Tschüss", german("bye"))
        assertEquals("Tschüss", platform.strings["de"]!!["bye"])
    }

    fun `test download only writes nothing to the platform`() {
        val platform = FakePlatform(listOf("de")).apply { strings["de"] = mutableMapOf("hello" to "Hallo") }
        val summary = sync(platform, SyncRunner.Options(uploadSource = false, download = true, uploadTranslations = false))
        assertTrue(platform.uploads.isEmpty())
        assertNull(summary.sourceStrings)
        assertEquals("Hallo", german("hello"))
        assertTrue(Exchange.toStringsXml(controller.read(), de).contains("Hallo"))
    }

    fun `test switching to another platform uploads what the old one already had`() {
        val lokalise = FakePlatform(listOf("de"), syncKey = "lokalise:1")
        sync(lokalise)
        assertEquals("Wagen", lokalise.strings["de"]!!["cart"])

        val poeditor = FakePlatform(listOf("de"), syncKey = "poeditor:9")
        val summary = sync(poeditor)
        assertEquals(mapOf(de to 2), summary.uploaded)
        assertEquals(mapOf("bye" to "Tschüss", "cart" to "Wagen"), poeditor.strings["de"])
        assertEquals("poeditor:9", controller.lock().syncedWith)
    }

    fun `test new translations keep the default file's order`() {
        fun add(folder: String, body: String): VirtualFile = myFixture.addFileToProject(
            "shop/src/commonMain/composeResources/$folder/strings.xml", "<resources>\n$body</resources>\n").virtualFile
        add("values", listOf("first", "anchor", "middle", "last").joinToString("") { "    <string name=\"$it\">$it</string>\n" })
        val de = add("values-de", "    <string name=\"anchor\">Anker</string>\n")
        val shop = TranslationsController(project, de.parent.parent)
        shop.applySynced("fake:1", this.de, listOf("last", "first", "middle").associate { key(it) to TextValue(it.uppercase()) }, emptyMap())
        val names = Regex("name=\"(\\w+)\"").findAll(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(de)!!.text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("first", "anchor", "middle", "last"), names)
    }
}
