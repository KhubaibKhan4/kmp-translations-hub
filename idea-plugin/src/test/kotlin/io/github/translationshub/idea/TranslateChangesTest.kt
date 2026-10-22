package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Origin
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.PluralsValue
import io.github.translationshub.core.Protection
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.idea.translate.Http
import io.github.translationshub.idea.translate.ProviderException
import io.github.translationshub.idea.translate.TranslateChanges
import io.github.translationshub.idea.translate.TranslationProvider
import io.github.translationshub.idea.translate.TranslationRequest
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.util.concurrent.atomic.AtomicInteger

/** A provider that "translates" by tagging text with the locale, keeping the ⟦n⟧ tokens where they were. */
private class FakeProvider(private val breakPlaceholdersOf: String? = null) : TranslationProvider {
    override val id = "fake"
    override val displayName = "Fake"
    override val protection = Protection.Style.BRACKETS
    override val maxBatch = 2
    override val maxChars = 1000
    override val concurrency = 3
    val requests = mutableListOf<List<String>>()
    val inFlight = AtomicInteger()
    var maxInFlight = 0

    override fun unavailableReason(): String? = null

    override suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?> {
        synchronized(this) {
            this.requests += requests.map { it.text }
            maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
        }
        kotlinx.coroutines.delay(20)
        inFlight.decrementAndGet()
        return requests.map { r -> if (r.key == breakPlaceholdersOf) "[${to.tag}] lost" else "[${to.tag}] ${r.text}" }
    }
}

class TranslateChangesTest : BasePlatformTestCase() {

    private val de = Locale("de")
    private val pl = Locale("pl")
    private lateinit var root: VirtualFile
    private lateinit var controller: TranslationsController

    override fun setUp() {
        super.setUp()
        fun add(folder: String, body: String) = myFixture.addFileToProject(
            "app/src/commonMain/composeResources/$folder/strings.xml",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body</resources>\n",
        ).virtualFile
        add("values",
            "    <string name=\"hello\">Hello, %1\$s</string>\n    <string name=\"bye\">Bye</string>\n    <string name=\"brand\" translatable=\"false\">Acme</string>\n" +
                "    <plurals name=\"files\">\n        <item quantity=\"one\">One file</item>\n        <item quantity=\"other\">%d files</item>\n    </plurals>\n")
        add("values-pl", "")
        root = add("values-de", "    <string name=\"bye\">Tschüss</string>\n").parent.parent
        controller = TranslationsController(project, root)
    }

    private fun translate(provider: TranslationProvider = FakeProvider(), work: Map<Locale, List<ResourceKey>> = TranslateChanges.pending(controller, false)) =
        runBlocking { TranslateChanges.run(controller, provider, "en", work, apply = { l, v -> controller.applyTranslations(l, v, provider.id) }) }

    fun `test only missing strings are sent, in parallel, with placeholders kept`() {
        val provider = FakeProvider()
        val summary = translate(provider)
        val set = controller.read()
        // de had "bye" already (a person's): only hello and files are sent. Polish gets four plural forms.
        assertEquals(mapOf(de to 2, pl to 3), summary.written)
        assertEquals(TextValue("[de] Hello, %1\$s"), set.translation(ResourceKey(ResourceKind.STRING, "hello"), de)!!.value)
        assertEquals(TextValue("Tschüss"), set.translation(ResourceKey(ResourceKind.STRING, "bye"), de)!!.value)
        assertEquals(setOf(PluralCategory.ONE, PluralCategory.FEW, PluralCategory.MANY, PluralCategory.OTHER),
            (set.translation(ResourceKey(ResourceKind.PLURALS, "files"), pl)!!.value as PluralsValue).items.keys)
        assertTrue(provider.requests.flatten().none { "Acme" in it })
        assertTrue("batches ran in parallel", provider.maxInFlight > 1)
        assertTrue(provider.maxInFlight <= 3)
        assertTrue(provider.requests.all { it.size <= 2 })
    }

    fun `test a second run sends nothing, and a changed default sends just that key`() {
        translate()
        assertTrue(TranslateChanges.pending(controller, false).values.all { it.isEmpty() })

        controller.setValue(ResourceKey(ResourceKind.STRING, "hello"), null, TextValue("Hello there, %1\$s"))
        controller.setValue(ResourceKey(ResourceKind.STRING, "bye"), null, TextValue("Goodbye"))
        val pending = TranslateChanges.pending(controller, false)
        // bye's German was written by a person: it needs review, but is only redone when asked.
        assertEquals(mapOf(de to listOf("hello"), pl to listOf("hello", "bye")), pending.mapValues { (_, keys) -> keys.map { it.name } })
        assertEquals(listOf("hello", "bye"), TranslateChanges.pending(controller, true).getValue(de).map { it.name })

        val provider = FakeProvider()
        translate(provider, pending)
        assertEquals(TextValue("[de] Hello there, %1\$s"), controller.read().translation(ResourceKey(ResourceKind.STRING, "hello"), de)!!.value)
        assertEquals(Origin.Machine("fake"), controller.lock().record(ResourceKey(ResourceKind.STRING, "hello"), de)!!.origin)
    }

    fun `test a result that loses placeholders is not saved`() {
        val summary = translate(FakeProvider(breakPlaceholdersOf = "hello"))
        assertNull(controller.read().translation(ResourceKey(ResourceKind.STRING, "hello"), de))
        assertEquals("Fake changed a placeholder", summary.failed.getValue(de).getValue(ResourceKey(ResourceKind.STRING, "hello")))
        // The rest of the locale was still written.
        assertNotNull(controller.read().translation(ResourceKey(ResourceKind.PLURALS, "files"), de))
    }

    fun `test a string whose tokens get dropped is retried with plain placeholders`() {
        // Like MyMemory's memory: drops made-up ⟦0⟧ tokens, keeps real %1$s.
        val memory = object : TranslationProvider by FakeProvider() {
            override val protection = Protection.Style.BRACKETS
            override val retryProtection = Protection.Style.RAW_PLACEHOLDERS
            override suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?> =
                requests.map { r -> "[${to.tag}] " + r.text.replace(Regex("⟦\\d+⟧"), "") }
        }
        val summary = translate(memory, mapOf(de to listOf(ResourceKey(ResourceKind.STRING, "hello"))))
        assertEquals(mapOf(de to 1), summary.written)
        assertEquals(TextValue("[de] Hello, %1\$s"), controller.read().translation(ResourceKey(ResourceKind.STRING, "hello"), de)!!.value)
    }

    fun `test MyMemory live with strings that failed before`() {
        // Network test, opt-in: -Dtranslationshub.live=true (./gradlew :idea-plugin:test -Ptranslationshub.live=true).
        if (System.getProperty("translationshub.live") != "true") return
        val provider = io.github.translationshub.idea.translate.MyMemoryProvider { null }
        val texts = listOf("Archive %1\$s?", "Remove %1\$s", "Credit limit %1\$s", "%1\$d customers", "Added %1\$d customers. %2\$d skipped.", "It\\'s %1\$s")
        val plan = io.github.translationshub.core.TranslationPlan(Locale("es"), texts.mapIndexed { i, t ->
            io.github.translationshub.core.TranslationPlan.Segment(ResourceKey(ResourceKind.STRING, "k$i"), io.github.translationshub.core.TranslationPlan.Part.Whole, t, null)
        })
        val result = runBlocking { io.github.translationshub.idea.translate.TranslationRunner(provider, "en").run(plan) }
        result.values.forEach { (k, v) -> println("LIVE ${k.name}: $v") }
        result.failed.forEach { (k, why) -> println("LIVE FAILED ${k.name}: $why") }
        assertEquals(texts.size, result.values.size)
    }

    fun `test a service error is reported per locale`() {
        val failing = object : TranslationProvider by FakeProvider() {
            override suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?> = throw ProviderException("Quota used up")
        }
        val summary = translate(failing)
        assertEquals(mapOf(de to "Quota used up", pl to "Quota used up"), summary.errors)
        assertTrue(summary.written.isEmpty())
    }

    fun `test http retries a 429 and honors Retry-After`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val first = calls.incrementAndGet() == 1
                val body = if (first) "{\"message\":\"slow down\"}" else "{\"ok\":true}"
                if (first) exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(if (first) 429 else 200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        try {
            val response = runBlocking { Http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/")).GET(), "Test") }
            assertEquals("{\"ok\":true}", response.body())
            assertEquals(2, calls.get())
        } finally {
            server.stop(0)
        }
    }

    fun `test a 401 fails at once with the service's message`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = "{\"error\":{\"message\":\"Invalid API key\"}}"
                exchange.sendResponseHeaders(401, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        try {
            val error = runCatching { runBlocking { Http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/")).GET(), "Test") } }.exceptionOrNull()
            assertEquals("Test: HTTP 401 Invalid API key", error?.message)
        } finally {
            server.stop(0)
        }
    }
}
