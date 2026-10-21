package io.github.translationshub.core

import java.security.MessageDigest

/** Who wrote a translation, as far as the lock file knows. */
sealed interface Origin {
    /** Machine-translated by a provider (`deepl`, `apple`, `mymemory`…) and not edited since. */
    data class Machine(val provider: String) : Origin

    /** Typed or edited by a person, or already there before the plugin tracked it. Never overwritten automatically. */
    data object Human : Origin
}

enum class TranslationState {
    /** No translation in this locale. */
    MISSING,

    /** Translated from the current source text. */
    UP_TO_DATE,

    /** The source text changed after this translation was made. */
    STALE,

    /** A translation the lock file has no record of (made before the plugin, or by hand outside it). */
    UNTRACKED,
}

data class TranslationStatus(val state: TranslationState, val origin: Origin?) {
    /** What "Translate changes" sends to the provider: missing ones, and stale machine translations. */
    val needsMachineTranslation: Boolean
        get() = state == TranslationState.MISSING || (state == TranslationState.STALE && origin is Origin.Machine)

    /** Stale text a person wrote: flagged for review, never replaced without asking. */
    val needsReview: Boolean get() = state == TranslationState.STALE && origin == Origin.Human
}

/**
 * `translations.lock.json`, next to a `composeResources` folder and committed with it. For every translation it
 * records a hash of the source text it was made from and a hash of its own text, so the plugin can tell
 * - what is new or changed in the source (only those are translated again), and
 * - which machine translations someone edited since (those become [Origin.Human] and are left alone),
 * without reading git history. Grouped by locale with one entry per line, so merges stay simple.
 */
class TranslationLock(entries: Map<Locale, Map<ResourceKey, Record>> = emptyMap()) {

    data class Record(val sourceHash: String, val textHash: String, val origin: Origin)

    private val records: MutableMap<Locale, MutableMap<ResourceKey, Record>> =
        entries.mapValuesTo(sortedMapOf()) { (_, byKey) -> byKey.toSortedMap() }

    fun record(key: ResourceKey, locale: Locale): Record? = records[locale]?.get(key)

    fun status(set: TranslationSet, key: ResourceKey, locale: Locale): TranslationStatus {
        val translation = set.translation(key, locale) ?: return TranslationStatus(TranslationState.MISSING, null)
        val record = record(key, locale) ?: return TranslationStatus(TranslationState.UNTRACKED, Origin.Human)
        val origin = if (record.textHash == hash(translation.value)) record.origin else Origin.Human
        val source = set.source(key) ?: return TranslationStatus(TranslationState.UP_TO_DATE, origin)
        val state = if (record.sourceHash == hash(source.value)) TranslationState.UP_TO_DATE else TranslationState.STALE
        return TranslationStatus(state, origin)
    }

    /** The translatable keys "Translate changes" would send for [locale]. */
    fun pending(set: TranslationSet, locale: Locale): List<ResourceKey> =
        set.keys.filter { key -> set.source(key)?.translatable == true && status(set, key, locale).needsMachineTranslation }

    /** Records a translation of [source] written by [origin]: after machine translation, or when a person edits a cell. */
    fun put(key: ResourceKey, locale: Locale, source: ResourceValue, translation: ResourceValue, origin: Origin) {
        records.getOrPut(locale) { sortedMapOf() }[key] = Record(hash(source), hash(translation), origin)
    }

    /**
     * Starts tracking translations that are already there, as [Origin.Human] and current: the first time the
     * plugin sees a project, nothing that exists is re-translated.
     */
    fun adoptUntracked(set: TranslationSet) {
        for (locale in set.locales) for (key in set.keys) {
            val source = set.source(key) ?: continue
            val translation = set.translation(key, locale) ?: continue
            if (record(key, locale) == null) put(key, locale, source.value, translation.value, Origin.Human)
        }
    }

    /** Moves the records of a renamed key, so its translations don't look untracked afterwards. */
    fun rename(key: ResourceKey, newKey: ResourceKey) {
        for (byKey in records.values) byKey.remove(key)?.let { byKey[newKey] = it }
    }

    fun remove(key: ResourceKey) {
        records.values.forEach { it.remove(key) }
        records.values.removeIf { it.isEmpty() }
    }

    /** Drops records of keys and locales that no longer exist. */
    fun prune(set: TranslationSet) {
        val keys = set.keys.toSet()
        records.keys.retainAll(set.locales.toSet())
        records.values.forEach { it.keys.retainAll(keys) }
        records.values.removeIf { it.isEmpty() }
    }

    fun toJson(): String = buildString {
        append("{\n  \"version\": $VERSION,\n  \"translations\": {")
        records.entries.forEachIndexed { i, (locale, byKey) ->
            append(if (i == 0) "\n" else ",\n")
            append("    ").append(Json.quote(locale.tag)).append(": {")
            byKey.entries.forEachIndexed { j, (key, record) ->
                append(if (j == 0) "\n" else ",\n")
                append("      ").append(Json.quote(keyId(key))).append(": {\"source\": ").append(Json.quote(record.sourceHash))
                append(", \"text\": ").append(Json.quote(record.textHash))
                append(", \"by\": ").append(Json.quote(originId(record.origin))).append('}')
            }
            append(if (byKey.isEmpty()) "}" else "\n    }")
        }
        append(if (records.isEmpty()) "}\n}\n" else "\n  }\n}\n")
    }

    companion object {
        const val FILE_NAME = "translations.lock.json"
        private const val VERSION = 1

        /** Reads a lock file; entries it can't understand are skipped rather than failing the whole file. */
        fun fromJson(text: String): TranslationLock {
            val root = Json.parse(text) as? Map<*, *> ?: return TranslationLock()
            val translations = root["translations"] as? Map<*, *> ?: return TranslationLock()
            val entries = translations.entries.mapNotNull { (tag, byKey) ->
                val locale = Locale.fromTag(tag as String) ?: return@mapNotNull null
                locale to (byKey as? Map<*, *>).orEmpty().entries.mapNotNull { (id, value) ->
                    val key = parseKeyId(id as String) ?: return@mapNotNull null
                    val fields = value as? Map<*, *> ?: return@mapNotNull null
                    val source = fields["source"] as? String ?: return@mapNotNull null
                    val textHash = fields["text"] as? String ?: return@mapNotNull null
                    key to Record(source, textHash, parseOrigin(fields["by"] as? String))
                }.toMap()
            }.toMap()
            return TranslationLock(entries)
        }

        /** A short, stable hash of a value's text: 48 bits is plenty to tell versions of one string apart. */
        fun hash(value: ResourceValue): String {
            val canonical = when (value) {
                is TextValue -> "s\u0000" + value.text
                is PluralsValue -> "p\u0000" + value.items.toSortedMap().entries.joinToString("\u0000") { "${it.key.xmlName}=${it.value}" }
                is ArrayValue -> "a\u0000" + value.items.joinToString("\u0000")
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }

        private fun keyId(key: ResourceKey) = "${key.kind.xmlTag}/${key.name}"

        private fun parseKeyId(id: String): ResourceKey? {
            val kind = ResourceKind.fromXml(id.substringBefore('/', "")) ?: return null
            return ResourceKey(kind, id.substringAfter('/'))
        }

        private fun originId(origin: Origin) = when (origin) {
            is Origin.Machine -> origin.provider
            Origin.Human -> "human"
        }

        private fun parseOrigin(id: String?): Origin = if (id == null || id == "human") Origin.Human else Origin.Machine(id)
    }
}

/** Just enough JSON (objects, arrays, strings, numbers, booleans, null) for the lock file and the translation services, so core stays dependency-free. */
object Json {

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' || c == '\u2028' || c == '\u2029' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** Maps, lists, strings, numbers, booleans and null, as compact JSON. */
    fun write(value: Any?): String = buildString { write(value, this) }

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> out.append(quote(value))
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Number -> out.append(value.toDouble().let { if (it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e15) it.toLong().toString() else it.toString() })
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    out.append(quote(k.toString())).append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> error("Can't write ${value::class.simpleName} as JSON")
        }
    }

    fun parse(text: String): Any? = Parser(text).run { value().also { skipSpace(); require(i == s.length) { "Trailing JSON at $i" } } }

    private class Parser(val s: String) {
        var i = 0

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipSpace()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skipSpace()
            if (s.getOrNull(i) == '}') return map.also { i++ }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                map[key] = value()
                skipSpace()
                if (s.getOrNull(i) == ',') i++ else break
            }
            expect('}')
            return map
        }

        private fun array(): List<Any?> {
            val list = mutableListOf<Any?>()
            i++
            skipSpace()
            if (s.getOrNull(i) == ']') return list.also { i++ }
            while (true) {
                list += value()
                skipSpace()
                if (s.getOrNull(i) == ',') i++ else break
            }
            expect(']')
            return list
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated JSON string" }
                when (val c = s[i++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> out.append(s.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: error("Bad JSON at $start")
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "Bad JSON at $i" }
            i += word.length
            return value
        }

        private fun expect(c: Char) {
            require(s.getOrNull(i) == c) { "Expected '$c' at $i" }
            i++
        }
    }
}
