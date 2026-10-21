package io.github.translationshub.idea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import io.github.translationshub.core.Json
import io.github.translationshub.core.Locale
import io.github.translationshub.idea.translate.ClaudeProvider
import io.github.translationshub.idea.translate.ProviderException
import io.github.translationshub.idea.translate.TranslationRequest
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/** The SDK request the Claude provider sends, and how it reads answers, against a local stand-in for the API. */
class ClaudeProviderTest : BasePlatformTestCase() {

    private var lastBody: Map<*, *>? = null
    private var lastBeta: String? = null
    private lateinit var server: HttpServer
    private var reply: (Map<*, *>) -> Pair<Int, String> = { 200 to message("end_turn", "{\"translations\":[\"Hallo, %1\$s\",\"Tschüss\"]}") }

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/messages") { exchange ->
                val body = Json.parse(exchange.requestBody.readBytes().decodeToString()) as Map<*, *>
                lastBody = body
                lastBeta = exchange.requestHeaders.getFirst("anthropic-beta")
                val (code, text) = reply(body)
                val bytes = text.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    override fun tearDown() {
        try { server.stop(0) } finally { super.tearDown() }
    }

    private fun provider(model: String = ClaudeProvider.DEFAULT_MODEL) =
        ClaudeProvider({ "test-key" }, { model }, baseUrl = "http://127.0.0.1:${server.address.port}")

    private fun message(stop: String, text: String) = Json.write(mapOf(
        "id" to "msg_1", "type" to "message", "role" to "assistant", "model" to ClaudeProvider.DEFAULT_MODEL,
        "content" to listOf(mapOf("type" to "text", "text" to text)),
        "stop_reason" to stop, "stop_sequence" to null,
        "usage" to mapOf("input_tokens" to 10, "output_tokens" to 5),
    ))

    private val requests = listOf(TranslationRequest("Hello, %1\$s", "Home screen greeting", "hello"), TranslationRequest("Bye", null, "bye"))

    fun `test request shape and answer`() {
        val result = runBlocking { provider().translate(requests, "en", Locale("de")) }
        assertEquals(listOf("Hallo, %1\$s", "Tschüss"), result)

        val body = lastBody!!
        assertEquals("claude-opus-5-5", body["model"])
        assertEquals("default", body["fallbacks"])
        assertTrue(lastBeta.orEmpty(), "server-side-fallback-2026-07-01" in lastBeta.orEmpty())
        val outputConfig = body["output_config"] as Map<*, *>
        assertEquals("medium", outputConfig["effort"])
        assertEquals("json_schema", (outputConfig["format"] as Map<*, *>)["type"])
        assertNull("thinking is left to the model's default", body["thinking"])
        val user = ((body["messages"] as List<*>).single() as Map<*, *>)
        assertTrue(user["content"].toString().contains("Home screen greeting"))
        assertTrue(body["system"].toString().contains("German"))
    }

    fun `test models without server fallbacks don't send them`() {
        runBlocking { provider("claude-haiku-5-5").translate(requests, "en", Locale("de")) }
        assertNull(lastBody!!["fallbacks"])
    }

    fun `test refusal and bad key are reported, not parsed`() {
        reply = { 200 to message("refusal", "") }
        val refusal = runCatching { runBlocking { provider().translate(requests, "en", Locale("de")) } }.exceptionOrNull()
        assertTrue(refusal is ProviderException && "declined" in refusal.message!!)

        reply = { 401 to "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}" }
        val auth = runCatching { runBlocking { provider().translate(requests, "en", Locale("de")) } }.exceptionOrNull()
        assertEquals("Claude: the Anthropic API key isn't valid", auth?.message)
    }
}
