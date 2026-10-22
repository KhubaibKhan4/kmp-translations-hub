package io.github.translationshub.idea.translate

import io.github.translationshub.core.Json
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Protection
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One string to translate, with the key's XML comment as context for services that take it. */
data class TranslationRequest(val text: String, val context: String?, val key: String)

/** A failure the user can act on (wrong key, quota, language not installed); not retried. */
class ProviderException(message: String) : Exception(message)

/**
 * A translation service. [translate] returns one result per request, in order; null for a string the service
 * couldn't translate. Placeholders are already protected when [protection] is set.
 */
interface TranslationProvider {
    val id: String
    val displayName: String

    /** How placeholders are hidden from this service; null for language models, which are told to keep them. */
    val protection: Protection.Style?

    /** How to send a string once more when the service lost or changed its placeholders; null for no retry. */
    val retryProtection: Protection.Style? get() = Protection.Style.RAW_PLACEHOLDERS

    /** Strings per request, characters per request, and requests in flight across all locales. */
    val maxBatch: Int
    val maxChars: Int
    val concurrency: Int

    /** Null when ready; otherwise what to set up first ("Add a DeepL key in Settings"). */
    fun unavailableReason(): String?

    suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?>
}

/** java.net.http with retries on 429 and 5xx, honoring Retry-After. */
internal object Http {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    suspend fun send(request: HttpRequest.Builder, service: String, attempts: Int = 5): HttpResponse<String> {
        var wait = 1_000L
        repeat(attempts) { attempt ->
            val response = runCatching { client.sendAsync(request.timeout(Duration.ofSeconds(120)).build(), HttpResponse.BodyHandlers.ofString()).await() }
                .getOrElse { e -> if (attempt == attempts - 1) throw ProviderException("$service: ${e.message ?: e::class.simpleName}") else null }
            if (response != null) {
                val code = response.statusCode()
                if (code in 200..299) return response
                if (code != 429 && code < 500) throw ProviderException("$service: HTTP $code ${errorText(response.body())}")
                if (attempt == attempts - 1) throw ProviderException("$service: HTTP $code after $attempts tries ${errorText(response.body())}")
                val retryAfter = response.headers().firstValue("retry-after").orElse(null)?.toLongOrNull()?.times(1000)
                delay(retryAfter ?: wait)
            } else {
                delay(wait)
            }
            wait = (wait * 2).coerceAtMost(30_000)
        }
        throw ProviderException("$service: no response")
    }

    /** The service's own error message from a JSON body, when there is one. */
    private fun errorText(body: String): String {
        val json = runCatching { Json.parse(body) }.getOrNull() as? Map<*, *> ?: return body.take(200)
        val error = json["error"]
        return when (error) {
            is Map<*, *> -> (error["message"] ?: error).toString()
            null -> (json["message"] ?: body.take(200)).toString()
            else -> error.toString()
        }
    }

    fun json(body: String): Map<*, *> =
        runCatching { Json.parse(body) as? Map<*, *> }.getOrNull() ?: throw ProviderException("Unexpected response: ${body.take(200)}")

    fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
}

/** DeepL (free and Pro keys; free keys end in `:fx`). Keeps the `<x/>` placeholder tags in place with tag handling. */
class DeepLProvider(private val key: () -> String?) : TranslationProvider {
    override val id = "deepl"
    override val displayName = "DeepL"
    override val protection = Protection.Style.XML_TAGS
    override val maxBatch = 50
    override val maxChars = 25_000
    override val concurrency = 4

    override fun unavailableReason() = if (key().isNullOrBlank()) "Add a DeepL API key in Settings → Tools → KMP Translations Hub" else null

    override suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?> {
        val apiKey = key() ?: throw ProviderException("No DeepL key")
        val host = if (apiKey.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = Json.write(mapOf(
            "text" to requests.map { it.text },
            "source_lang" to from.substringBefore('-').uppercase(),
            "target_lang" to deeplTarget(to),
            "tag_handling" to "xml",
            "ignore_tags" to listOf("x"),
            // DeepL takes one context per request: the comments of the strings in it.
            "context" to requests.mapNotNull { it.context }.distinct().joinToString("\n").ifEmpty { null },
        ).filterValues { it != null })
        val response = Http.send(
            HttpRequest.newBuilder(URI("https://$host/v2/translate"))
                .header("Authorization", "DeepL-Auth-Key $apiKey").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
            "DeepL",
        )
        val translations = Http.json(response.body())["translations"] as? List<*> ?: throw ProviderException("DeepL: no translations")
        return translations.map { (it as? Map<*, *>)?.get("text") as? String }
    }

    private fun deeplTarget(to: Locale): String = when {
        to.language == "en" -> if (to.region == "GB") "EN-GB" else "EN-US"
        to.language == "pt" -> if (to.region == "BR") "PT-BR" else "PT-PT"
        to.language == "zh" -> if (to.script == "Hant" || to.region in setOf("TW", "HK", "MO")) "ZH-HANT" else "ZH-HANS"
        to.language == "nb" || to.language == "no" -> "NB"
        else -> to.language.uppercase()
    }
}

/** Google Cloud Translation (v2, API key). `format: html` keeps the `<x/>` tags. */
class GoogleCloudProvider(private val key: () -> String?) : TranslationProvider {
    override val id = "google"
    override val displayName = "Google Cloud Translation"
    override val protection = Protection.Style.XML_TAGS
    override val maxBatch = 100
    override val maxChars = 25_000
    override val concurrency = 4

    override fun unavailableReason() = if (key().isNullOrBlank()) "Add a Google Cloud API key in Settings → Tools → KMP Translations Hub" else null

    override suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?> {
        val apiKey = key() ?: throw ProviderException("No Google Cloud key")
        val body = Json.write(mapOf("q" to requests.map { it.text }, "source" to from, "target" to googleTarget(to), "format" to "html"))
        val response = Http.send(
            HttpRequest.newBuilder(URI("https://translation.googleapis.com/language/translate/v2"))
                .header("X-Goog-Api-Key", apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
            "Google",
        )
        val data = Http.json(response.body())["data"] as? Map<*, *> ?: throw ProviderException("Google: no data")
        return (data["translations"] as? List<*>).orEmpty().map { (it as? Map<*, *>)?.get("translatedText") as? String }
    }

    private fun googleTarget(to: Locale): String = when {
        to.language == "zh" -> if (to.script == "Hant" || to.region in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
        to.language == "pt" && to.region == "PT" -> "pt-PT"
        else -> to.language
    }
}

/**
 * MyMemory: free, no key. About 5,000 characters a day per IP, ten times that with an email address (optional,
 * in Settings). One string per request, so it suits a few new strings rather than a whole app.
 */
class MyMemoryProvider(private val email: () -> String?) : TranslationProvider {
    override val id = "mymemory"
    override val displayName = "MyMemory (free, no key)"

    // A translation memory of software strings: it keeps %1$s but drops or rewrites made-up tokens (tested).
    override val protection = Protection.Style.RAW_PLACEHOLDERS
    override val retryProtection = Protection.Style.BRACKETS
    override val maxBatch = 1
    override val maxChars = 500
    override val concurrency = 2

    override fun unavailableReason(): String? = null

    override suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?> = requests.map { request ->
        val url = buildString {
            append("https://api.mymemory.translated.net/get?q=").append(Http.encode(request.text))
            append("&langpair=").append(Http.encode("${from.substringBefore('-')}|${to.tag}"))
            email()?.takeIf { it.isNotBlank() }?.let { append("&de=").append(Http.encode(it)) }
        }
        val json = Http.json(Http.send(HttpRequest.newBuilder(URI(url)).GET(), "MyMemory").body())
        val status = (json["responseStatus"] as? Number)?.toInt() ?: (json["responseStatus"] as? String)?.toIntOrNull()
        val text = (json["responseData"] as? Map<*, *>)?.get("translatedText") as? String
        when {
            status == 429 || text?.startsWith("MYMEMORY WARNING") == true ->
                throw ProviderException("MyMemory's free daily limit is used up. Add an email in Settings for 10× more, or use another provider.")
            status != 200 -> throw ProviderException("MyMemory: ${json["responseDetails"] ?: "status $status"}")
            else -> text
        }
    }
}
