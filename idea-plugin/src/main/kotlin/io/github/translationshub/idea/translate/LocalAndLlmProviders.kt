package io.github.translationshub.idea.translate

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import io.github.translationshub.core.Json
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Protection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Apple's on-device Translation framework (macOS 26+), through the bundled `translationshub-apple` helper. Free,
 * offline, nothing leaves the Mac; the languages have to be downloaded in System Settings first.
 */
class AppleProvider : TranslationProvider {
    override val id = "apple"
    override val displayName = "Apple on-device (free, no key, macOS)"
    override val protection = Protection.Style.BRACKETS
    override val maxBatch = 100
    override val maxChars = 20_000
    override val concurrency = 2

    override fun unavailableReason(): String? = when {
        !SystemInfo.isMac -> "Apple translation runs on macOS only"
        !SystemInfo.isOsVersionAtLeast("26.0") -> "Apple translation needs macOS 26 or newer"
        helper() == null -> "The Apple translation helper is missing from this build"
        else -> null
    }

    override suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?> = withContext(Dispatchers.IO) {
        val helper = helper() ?: throw ProviderException("The Apple translation helper is missing")
        val process = ProcessBuilder(helper.path, "translate", "--from", from, "--to", to.tag).start()
        process.outputStream.use { it.write(Json.write(mapOf("texts" to requests.map { r -> r.text })).toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw ProviderException("Apple translation didn't finish in 5 minutes")
        }
        val json = Http.json(output)
        (json["error"] as? String)?.let { throw ProviderException("Apple: $it") }
        (json["texts"] as? List<*>)?.map { it as? String } ?: throw ProviderException("Apple: no result")
    }

    /** Copies the helper out of the plugin jar once (executables can't run from inside a jar). */
    private fun helper(): File? = helperFile

    private val helperFile: File? by lazy {
        val stream = AppleProvider::class.java.getResourceAsStream("/bin/macos/translationshub-apple") ?: return@lazy null
        val target = File(PathManager.getSystemPath(), "kmp-translations-hub/translationshub-apple")
        target.parentFile.mkdirs()
        stream.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        target.setExecutable(true)
        target
    }
}

/** The instructions language-model providers get: what to keep, and the JSON to answer with. */
internal object LlmPrompt {

    fun system(from: String, to: Locale) = """
        You translate user-interface strings of a Compose Multiplatform app from ${languageName(from)} to ${languageName(to.tag)} (${to.tag}).
        Rules:
        - Keep placeholders such as %1${'$'}s, %2${'$'}d and %d exactly as they are; you may move them to where the grammar needs them.
        - Keep inline markup such as <b>…</b> and escapes such as \n, \' and \" exactly as written.
        - Match the length and tone of app UI text: short, natural, no explanations or quotes around the result.
        - Use each string's key and note only as context; never translate them.
        Answer with JSON: {"translations": [...]}, one translation per input string, in the same order.
    """.trimIndent()

    fun user(requests: List<TranslationRequest>): String = Json.write(mapOf(
        "strings" to requests.map { r -> listOfNotNull("key" to r.key, "text" to r.text, r.context?.let { "note" to it }).toMap() },
    ))

    /** The `translations` array of a model's answer, or null strings where it didn't give one. */
    fun parse(answer: String, count: Int): List<String?> {
        val json = runCatching { Json.parse(answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull() as? Map<*, *>
            ?: throw ProviderException("The model didn't answer with JSON")
        val list = json["translations"] as? List<*> ?: throw ProviderException("The model's answer has no translations")
        return List(count) { list.getOrNull(it) as? String }
    }

    val schema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf("translations" to mapOf("type" to "array", "items" to mapOf("type" to "string"))),
        "required" to listOf("translations"),
        "additionalProperties" to false,
    )

    private fun languageName(tag: String) = java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.ENGLISH).ifEmpty { tag }
}

/** OpenAI Chat Completions with a JSON-schema response, using your own key and the model set in Settings. */
class OpenAiProvider(private val key: () -> String?, private val model: () -> String) : TranslationProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    override val protection: Protection.Style? = null
    override val maxBatch = 40
    override val maxChars = 12_000
    override val concurrency = 3

    override fun unavailableReason() = when {
        key().isNullOrBlank() -> "Add an OpenAI API key in Settings → Tools → KMP Translations Hub"
        model().isBlank() -> "Choose an OpenAI model in Settings → Tools → KMP Translations Hub"
        else -> null
    }

    override suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?> {
        val body = Json.write(mapOf(
            "model" to model(),
            "messages" to listOf(
                mapOf("role" to "system", "content" to LlmPrompt.system(from, to)),
                mapOf("role" to "user", "content" to LlmPrompt.user(requests)),
            ),
            "response_format" to mapOf("type" to "json_schema", "json_schema" to mapOf("name" to "translations", "strict" to true, "schema" to LlmPrompt.schema)),
        ))
        val response = Http.send(
            HttpRequest.newBuilder(URI("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer ${key()}").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
            "OpenAI",
        )
        val choice = (Http.json(response.body())["choices"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: throw ProviderException("OpenAI: no answer")
        val content = (choice["message"] as? Map<*, *>)?.get("content") as? String ?: throw ProviderException("OpenAI: empty answer")
        return LlmPrompt.parse(content, requests.size)
    }
}
