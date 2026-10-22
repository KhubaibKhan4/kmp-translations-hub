package io.github.translationshub.idea.sync

import io.github.translationshub.core.Json
import io.github.translationshub.idea.translate.Http
import io.github.translationshub.idea.translate.ProviderException
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpRequest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream

/** The project's languages on a translation platform, as the platform writes their codes (`de`, `pt-BR`, `pt_BR`). */
data class PlatformLanguages(val source: String, val targets: List<String>)

/**
 * A translation platform that translators work in. Everything goes through Android `strings.xml`, which all of them
 * read and write with plurals and arrays, so the plugin's own import and export do the format work. [fileName]
 * names one `composeResources` folder's strings on the platform.
 */
interface TranslationPlatform {
    val id: String
    val displayName: String

    /** The platform and project, as recorded in `translations.lock.json`: sync state belongs to one of them. */
    val syncKey: String

    /** True when the platform's Android import skips `<string-array>`: each item is then sent as its own string. */
    val flatArrays: Boolean get() = false

    suspend fun languages(): PlatformLanguages

    /** Uploads the default-language strings: adds new keys and updates changed ones. */
    suspend fun uploadSource(fileName: String, xml: String, source: String)

    suspend fun uploadTranslations(fileName: String, code: String, xml: String)

    /** Each language's translated strings; a language with nothing to download may be left out. */
    suspend fun download(fileName: String, codes: List<String>): Map<String, String>
}

/** Crowdin, or Crowdin Enterprise when an organization is set. Files are matched by name, at any folder level. */
class CrowdinPlatform(
    private val token: String,
    private val projectId: String,
    organization: String? = null,
    private val baseUrl: String = organization?.takeIf { it.isNotBlank() }?.let { "https://${it.trim()}.api.crowdin.com/api/v2" } ?: "https://api.crowdin.com/api/v2",
) : TranslationPlatform {
    override val id = "crowdin"
    override val displayName = "Crowdin"
    override val syncKey = listOfNotNull("crowdin", organization?.takeIf { it.isNotBlank() }?.trim(), projectId).joinToString(":")

    override suspend fun languages(): PlatformLanguages {
        val project = data(get("/projects/$projectId")) as Map<*, *>
        return PlatformLanguages(
            project["sourceLanguageId"] as? String ?: throw ProviderException("Crowdin: the project has no source language"),
            (project["targetLanguageIds"] as? List<*>).orEmpty().filterIsInstance<String>(),
        )
    }

    override suspend fun uploadSource(fileName: String, xml: String, source: String) {
        val storageId = storage(fileName, xml)
        val fileId = fileId(fileName)
        if (fileId == null) {
            send("POST", "/projects/$projectId/files", mapOf("storageId" to storageId, "name" to fileName, "type" to "android"))
        } else {
            // Strings no longer in the file leave the Crowdin file; their translations stay in its translation memory.
            send("PUT", "/projects/$projectId/files/$fileId", mapOf("storageId" to storageId, "updateOption" to "keep_translations_and_approvals"))
        }
    }

    override suspend fun uploadTranslations(fileName: String, code: String, xml: String) {
        val fileId = fileId(fileName) ?: throw ProviderException("Crowdin: upload the default strings first ($fileName isn't in the project)")
        val storageId = storage(fileName, xml)
        send("POST", "/projects/$projectId/translations/$code", mapOf("storageId" to storageId, "fileId" to fileId, "importEqSuggestions" to false))
    }

    override suspend fun download(fileName: String, codes: List<String>): Map<String, String> {
        val fileId = fileId(fileName) ?: return emptyMap()
        return codes.associateWith { code ->
            // Without skipUntranslatedStrings, Crowdin fills untranslated strings with the source text.
            val build = data(send("POST", "/projects/$projectId/translations/builds/files/$fileId",
                mapOf("targetLanguageId" to code, "skipUntranslatedStrings" to true))) as Map<*, *>
            val url = build["url"] as? String ?: throw ProviderException("Crowdin: no download link for $code")
            String(Http.bytes(HttpRequest.newBuilder(URI(url)).GET(), "Crowdin"), Charsets.UTF_8)
        }
    }

    private suspend fun storage(fileName: String, xml: String): Long {
        val response = Http.send(
            authorized("/storages").header("Crowdin-API-FileName", Http.encode(fileName)).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofString(xml)),
            "Crowdin",
        )
        return id((data(response.body()) as Map<*, *>)["id"]) ?: throw ProviderException("Crowdin: no storage id")
    }

    private suspend fun fileId(fileName: String): Long? {
        var offset = 0
        while (true) {
            val page = (data(get("/projects/$projectId/files?limit=500&offset=$offset")) as? List<*>).orEmpty()
            page.map { (it as Map<*, *>)["data"] as Map<*, *> }.firstOrNull { it["name"] == fileName }?.let { return id(it["id"]) }
            if (page.size < 500) return null
            offset += 500
        }
    }

    private suspend fun get(path: String) = Http.send(authorized(path).GET(), "Crowdin").body()

    private suspend fun send(method: String, path: String, body: Map<String, Any?>) = Http.send(
        authorized(path).header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(body))),
        "Crowdin",
    ).body()

    private fun authorized(path: String) = HttpRequest.newBuilder(URI(baseUrl + path)).header("Authorization", "Bearer $token")

    private fun data(body: String): Any? = Http.json(body)["data"]

    /** Crowdin's ids are integers; the JSON reader gives numbers as doubles. */
    private fun id(value: Any?): Long? = (value as? Number)?.toLong()
}

/**
 * Lokalise. Keys are kept per file name (`distinguish_by_file`), so several modules can share a project; uploads
 * are processed in the background, and the plugin waits for them.
 */
class LokalisePlatform(
    private val token: String,
    private val projectId: String,
    private val baseUrl: String = "https://api.lokalise.com/api2",
    private val pollMillis: Long = 1_000,
) : TranslationPlatform {
    override val id = "lokalise"
    override val displayName = "Lokalise"
    override val syncKey = "lokalise:$projectId"

    override suspend fun languages(): PlatformLanguages {
        val project = Http.json(get("/projects/$projectId"))
        val languages = (Http.json(get("/projects/$projectId/languages?limit=500"))["languages"] as? List<*>).orEmpty()
        return PlatformLanguages(
            project["base_language_iso"] as? String ?: throw ProviderException("Lokalise: the project has no base language"),
            languages.mapNotNull { (it as? Map<*, *>)?.get("lang_iso") as? String },
        )
    }

    override suspend fun uploadSource(fileName: String, xml: String, source: String) = upload(fileName, source, xml)

    override suspend fun uploadTranslations(fileName: String, code: String, xml: String) = upload(fileName, code, xml)

    override suspend fun download(fileName: String, codes: List<String>): Map<String, String> {
        if (codes.isEmpty()) return emptyMap()
        val body = mapOf(
            "format" to "xml",
            "original_filenames" to true,
            "directory_prefix" to "%LANG_ISO%/",
            "filter_filenames" to listOf(fileName),
            "filter_langs" to codes,
            "export_empty_as" to "skip",
            "placeholder_format" to "printf",
        )
        val response = try {
            post("/projects/$projectId/files/download", body)
        } catch (e: ProviderException) {
            // "No keys for export with current export settings": nothing is translated yet, or the file isn't there yet.
            if (e.status == 406) return emptyMap() else throw e
        }
        val url = Http.json(response)["bundle_url"] as? String ?: throw ProviderException("Lokalise: no download link")
        val result = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(Http.bytes(HttpRequest.newBuilder(URI(url)).GET(), "Lokalise"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val code = entry.name.trimStart('/').substringBefore('/')
                if (!entry.isDirectory && entry.name.endsWith(".xml") && code in codes) result[code] = String(zip.readBytes(), Charsets.UTF_8)
            }
        }
        return result
    }

    private suspend fun upload(fileName: String, code: String, xml: String) {
        val body = mapOf(
            "data" to Base64.getEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8)),
            "filename" to fileName,
            "lang_iso" to code,
            "replace_modified" to true,
            "distinguish_by_file" to true,
        )
        var process = Http.json(post("/projects/$projectId/files/upload", body))["process"] as? Map<*, *>
            ?: throw ProviderException("Lokalise: the upload wasn't queued")
        val processId = process["process_id"] ?: throw ProviderException("Lokalise: no process id")
        repeat(300) {
            when (process["status"]) {
                "finished" -> return
                "failed", "cancelled" -> throw ProviderException("Lokalise: upload ${process["status"]}: ${process["message"] ?: ""}".trimEnd(' ', ':'))
            }
            delay(pollMillis)
            process = Http.json(get("/projects/$projectId/processes/$processId"))["process"] as? Map<*, *> ?: process
        }
        throw ProviderException("Lokalise: the upload is still processing; check the project in Lokalise")
    }

    private suspend fun get(path: String) = Http.send(authorized(path).GET(), "Lokalise").body()

    private suspend fun post(path: String, body: Map<String, Any?>) = Http.send(
        authorized(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))),
        "Lokalise",
    ).body()

    private fun authorized(path: String) = HttpRequest.newBuilder(URI(baseUrl + path)).header("X-Api-Token", token)
}

/**
 * POEditor. A project holds one set of terms, so use one POEditor project per `composeResources` folder when
 * modules share key names. POEditor takes one upload every 20 seconds, which the plugin waits out.
 */
class PoEditorPlatform(
    private val token: String,
    private val projectId: String,
    private val baseUrl: String = "https://api.poeditor.com/v2",
    private val uploadGapMillis: Long = 21_000,
) : TranslationPlatform {
    override val id = "poeditor"
    override val displayName = "POEditor"
    override val syncKey = "poeditor:$projectId"
    // POEditor's Android import drops <string-array> (tested): items go as days[0], days[1]….
    override val flatArrays = true
    private var lastUpload = 0L

    override suspend fun languages(): PlatformLanguages {
        val project = (result(form("projects/view"))["project"] as? Map<*, *>).orEmpty()
        val languages = (result(form("languages/list"))["languages"] as? List<*>).orEmpty()
        return PlatformLanguages(
            (project["reference_language"] as? String).orEmpty(),
            languages.mapNotNull { (it as? Map<*, *>)?.get("code") as? String },
        )
    }

    override suspend fun uploadSource(fileName: String, xml: String, source: String) {
        if (source.isBlank()) throw ProviderException("POEditor: set the project's default reference language, or the default language in Settings")
        // fuzzy_trigger: translations of a changed source string are marked fuzzy for translators to check.
        upload(fileName, xml, mapOf("updating" to "terms_translations", "language" to source, "overwrite" to "1", "sync_terms" to "0", "fuzzy_trigger" to "1"))
    }

    override suspend fun uploadTranslations(fileName: String, code: String, xml: String) =
        upload(fileName, xml, mapOf("updating" to "translations", "language" to code, "overwrite" to "1"))

    override suspend fun download(fileName: String, codes: List<String>): Map<String, String> = codes.associateWith { code ->
        val url = result(form("projects/export", mapOf("language" to code, "type" to "android_strings", "filters" to "translated")))["url"] as? String
            ?: throw ProviderException("POEditor: no download link for $code")
        String(Http.bytes(HttpRequest.newBuilder(URI(url)).GET(), "POEditor"), Charsets.UTF_8)
    }

    private suspend fun upload(fileName: String, xml: String, fields: Map<String, String>) {
        val wait = lastUpload + uploadGapMillis - System.currentTimeMillis()
        if (lastUpload > 0 && wait > 0) delay(wait)
        val boundary = "----KmpTranslationsHub" + UUID.randomUUID().toString().replace("-", "")
        val body = buildString {
            for ((name, value) in fields + mapOf("api_token" to token, "id" to projectId)) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: application/xml\r\n\r\n")
            append(xml).append("\r\n--$boundary--\r\n")
        }
        val response = Http.send(
            HttpRequest.newBuilder(URI("$baseUrl/projects/upload")).header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
            "POEditor",
        ).body()
        lastUpload = System.currentTimeMillis()
        result(response)
    }

    private suspend fun form(path: String, fields: Map<String, String> = emptyMap()): String {
        val body = (fields + mapOf("api_token" to token, "id" to projectId)).entries.joinToString("&") { "${Http.encode(it.key)}=${Http.encode(it.value)}" }
        return Http.send(
            HttpRequest.newBuilder(URI("$baseUrl/$path")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)),
            "POEditor",
        ).body()
    }

    /** POEditor answers HTTP 200 with `response.status` "fail" for errors. */
    private fun result(body: String): Map<*, *> {
        val json = Http.json(body)
        val response = json["response"] as? Map<*, *>
        if (response?.get("status") != "success") throw ProviderException("POEditor: ${response?.get("message") ?: body.take(200)}")
        return json["result"] as? Map<*, *> ?: emptyMap<Any, Any>()
    }
}
