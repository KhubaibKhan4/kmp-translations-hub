package io.github.translationshub.idea

import com.sun.net.httpserver.HttpServer
import io.github.translationshub.core.Json
import io.github.translationshub.idea.sync.CrowdinPlatform
import io.github.translationshub.idea.sync.LokalisePlatform
import io.github.translationshub.idea.sync.PlatformLanguages
import io.github.translationshub.idea.sync.PoEditorPlatform
import io.github.translationshub.idea.translate.ProviderException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The requests each platform client sends, and how it reads the answers, against a local stand-in for the API. */
class SyncPlatformsTest {

    data class Call(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val calls = mutableListOf<Call>()
    private lateinit var server: HttpServer
    private var route: (Call) -> Pair<Int, Any> = { 404 to "" }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"hello\">Hallo</string>\n</resources>\n"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val call = Call(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                    exchange.requestBody.readBytes().decodeToString(),
                )
                synchronized(calls) { calls += call }
                val (code, body) = route(call)
                val bytes = when (body) {
                    is ByteArray -> body
                    is String -> body.toByteArray()
                    else -> Json.write(body).toByteArray()
                }
                exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @After
    fun stop() = server.stop(0)

    private fun json(call: Call) = Json.parse(call.body) as Map<*, *>

    // ---- Crowdin -----------------------------------------------------------------------------------------------

    private fun crowdin(existingFile: Boolean) = CrowdinPlatform("tok", "42", baseUrl = base).also {
        route = { call ->
            when {
                call.path == "/projects/42" -> 200 to mapOf("data" to mapOf("sourceLanguageId" to "en", "targetLanguageIds" to listOf("de", "es-ES")))
                call.path == "/storages" -> 201 to mapOf("data" to mapOf("id" to 7))
                call.path.startsWith("/projects/42/files?") ->
                    200 to mapOf("data" to if (existingFile) listOf(mapOf("data" to mapOf("id" to 3, "name" to "app-commonMain-strings.xml"))) else emptyList())
                call.path == "/projects/42/files" || call.path == "/projects/42/files/3" -> 200 to mapOf("data" to mapOf("id" to 3))
                call.path.startsWith("/projects/42/translations/builds/files/3") -> 200 to mapOf("data" to mapOf("url" to "$base/download/de.xml"))
                call.path == "/download/de.xml" -> 200 to xml
                call.path == "/projects/42/translations/de" -> 200 to mapOf("data" to emptyMap<String, Any>())
                else -> 404 to mapOf("error" to mapOf("message" to "no route ${call.path}"))
            }
        }
    }

    @Test
    fun `crowdin reads languages and adds the file the first time`() = runBlocking {
        val platform = crowdin(existingFile = false)
        assertEquals(PlatformLanguages("en", listOf("de", "es-ES")), platform.languages())
        platform.uploadSource("app-commonMain-strings.xml", xml, "en")
        val storage = calls.single { it.path == "/storages" }
        assertEquals("Bearer tok", storage.headers["authorization"])
        assertEquals("app-commonMain-strings.xml", storage.headers["crowdin-api-filename"])
        assertEquals(xml, storage.body)
        val add = calls.single { it.method == "POST" && it.path == "/projects/42/files" }
        assertEquals(mapOf("storageId" to 7.0, "name" to "app-commonMain-strings.xml", "type" to "android"), json(add))
    }

    @Test
    fun `crowdin updates an existing file keeping translations, and downloads without untranslated strings`() = runBlocking {
        val platform = crowdin(existingFile = true)
        platform.uploadSource("app-commonMain-strings.xml", xml, "en")
        assertEquals("keep_translations_and_approvals", json(calls.single { it.method == "PUT" })["updateOption"])

        assertEquals(mapOf("de" to xml), platform.download("app-commonMain-strings.xml", listOf("de")))
        val build = calls.single { it.path.startsWith("/projects/42/translations/builds") }
        assertEquals(mapOf("targetLanguageId" to "de", "skipUntranslatedStrings" to true), json(build))
        // The signed download link gets no token.
        assertEquals(null, calls.single { it.path == "/download/de.xml" }.headers["authorization"])

        platform.uploadTranslations("app-commonMain-strings.xml", "de", xml)
        assertEquals(3.0, json(calls.single { it.path == "/projects/42/translations/de" })["fileId"])
        assertTrue(calls.any { it.method == "PUT" && it.path == "/projects/42/files/3" })
    }

    @Test
    fun `crowdin downloads nothing before the file is uploaded`() = runBlocking {
        assertEquals(emptyMap<String, String>(), crowdin(existingFile = false).download("app-commonMain-strings.xml", listOf("de")))
    }

    // ---- Lokalise ----------------------------------------------------------------------------------------------

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> for ((name, text) in files) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()

    private fun lokalise(finalStatus: String = "finished"): LokalisePlatform {
        var polls = 0
        route = { call ->
            when {
                call.path == "/projects/p1" -> 200 to mapOf("project_id" to "p1", "base_language_iso" to "en")
                call.path.startsWith("/projects/p1/languages") -> 200 to mapOf("languages" to listOf(mapOf("lang_iso" to "en"), mapOf("lang_iso" to "pt_BR")))
                call.path == "/projects/p1/files/upload" -> 202 to mapOf("process" to mapOf("process_id" to "q1", "status" to "queued"))
                call.path == "/projects/p1/processes/q1" -> 200 to mapOf("process" to mapOf("process_id" to "q1", "status" to if (++polls < 2) "running" else finalStatus, "message" to "bad file"))
                call.path == "/projects/p1/files/download" -> 200 to mapOf("bundle_url" to "$base/bundle.zip")
                call.path == "/bundle.zip" -> 200 to zip("pt_BR/app-commonMain-strings.xml" to xml, "en/app-commonMain-strings.xml" to "<resources/>")
                else -> 404 to ""
            }
        }
        return LokalisePlatform("tok", "p1", baseUrl = base, pollMillis = 1)
    }

    @Test
    fun `lokalise uploads base64 and waits for the import to finish`() = runBlocking {
        val platform = lokalise()
        assertEquals(PlatformLanguages("en", listOf("en", "pt_BR")), platform.languages())
        platform.uploadSource("app-commonMain-strings.xml", xml, "en")
        val upload = calls.single { it.path == "/projects/p1/files/upload" }
        assertEquals("tok", upload.headers["x-api-token"])
        val body = json(upload)
        assertEquals(xml, String(Base64.getDecoder().decode(body["data"] as String)))
        assertEquals("en", body["lang_iso"])
        assertEquals(true, body["distinguish_by_file"])
        assertEquals(2, calls.count { it.path == "/projects/p1/processes/q1" })
    }

    @Test
    fun `lokalise reports a failed import`() {
        val error = runCatching { runBlocking { lokalise("failed").uploadTranslations("f.xml", "de", xml) } }.exceptionOrNull()
        assertTrue(error is ProviderException && "bad file" in error.message!!)
    }

    @Test
    fun `lokalise downloads one file per language from the bundle`() = runBlocking {
        val files = lokalise().download("app-commonMain-strings.xml", listOf("pt_BR"))
        assertEquals(mapOf("pt_BR" to xml), files)
        val request = json(calls.single { it.path == "/projects/p1/files/download" })
        assertEquals(listOf("app-commonMain-strings.xml"), request["filter_filenames"])
        assertEquals("skip", request["export_empty_as"])
        assertEquals("printf", request["placeholder_format"])
    }

    @Test
    fun `lokalise with nothing translated yet downloads nothing`() = runBlocking {
        val platform = lokalise()
        val next = route
        route = { call -> if (call.path == "/projects/p1/files/download") 406 to mapOf("error" to mapOf("code" to 406, "message" to "No keys for export with current export settings")) else next(call) }
        assertEquals(emptyMap<String, String>(), platform.download("app-commonMain-strings.xml", listOf("de")))
    }

    // ---- POEditor ----------------------------------------------------------------------------------------------

    private fun ok(result: Any) = 200 to mapOf("response" to mapOf("status" to "success", "code" to "200", "message" to "OK"), "result" to result)

    private fun poeditor() = PoEditorPlatform("tok", "9", baseUrl = base, uploadGapMillis = 0).also {
        route = { call ->
            when (call.path) {
                "/projects/view" -> ok(mapOf("project" to mapOf("reference_language" to "en")))
                "/languages/list" -> ok(mapOf("languages" to listOf(mapOf("code" to "en"), mapOf("code" to "pt-br"))))
                "/projects/upload" -> ok(mapOf("terms" to mapOf("added" to 1)))
                "/projects/export" -> ok(mapOf("url" to "$base/export/pt-br.xml"))
                "/export/pt-br.xml" -> 200 to xml
                else -> 404 to ""
            }
        }
    }

    @Test
    fun `poeditor sends forms, uploads multipart, and exports translated strings`() = runBlocking {
        val platform = poeditor()
        assertEquals(PlatformLanguages("en", listOf("en", "pt-br")), platform.languages())
        assertTrue("api_token=tok" in calls.first().body && "id=9" in calls.first().body)

        platform.uploadSource("app-commonMain-strings.xml", xml, "en")
        val upload = calls.single { it.path == "/projects/upload" }
        assertTrue(upload.headers["content-type"]!!.startsWith("multipart/form-data; boundary="))
        for (part in listOf("terms_translations", "name=\"language\"\r\n\r\nen", "name=\"sync_terms\"\r\n\r\n0", "filename=\"app-commonMain-strings.xml\"", "<string name=\"hello\">Hallo</string>")) {
            assertTrue(part, part in upload.body)
        }

        assertEquals(mapOf("pt-br" to xml), platform.download("x", listOf("pt-br")))
        val export = calls.single { it.path == "/projects/export" }.body
        assertTrue("type=android_strings" in export && "filters=translated" in export && "language=pt-br" in export)
    }

    @Test
    fun `poeditor's failures in a 200 answer become errors`() {
        route = { 200 to mapOf("response" to mapOf("status" to "fail", "code" to "4011", "message" to "Invalid API token")) }
        val error = runCatching { runBlocking { PoEditorPlatform("bad", "9", baseUrl = base).languages() } }.exceptionOrNull()
        assertEquals("POEditor: Invalid API token", error?.message)
    }
}
