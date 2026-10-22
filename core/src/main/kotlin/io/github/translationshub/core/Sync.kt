package io.github.translationshub.core

/**
 * Sync with a translation platform (Crowdin, Lokalise, POEditor), decided here so every platform follows the same
 * rules. `translations.lock.json` remembers each translation's text when it last matched the platform
 * ([TranslationLock.Record.syncedHash]), which tells a change made in the IDE from one made on the platform:
 * - the default language belongs to the IDE: it is uploaded, never downloaded;
 * - a translation changed only on the platform is downloaded and counts as a person's;
 * - one changed only in the IDE is kept, and uploaded when translations are uploaded;
 * - one changed on both sides is a conflict: the IDE's is kept until the user picks the platform's.
 */
object Sync {

    enum class Action {
        /** The platform's translation is written: new, or changed only there. */
        WRITE,

        /** Already the same on both sides; only recorded as synced. */
        SAME,

        /** Changed only in the IDE since the last sync: kept. */
        KEEP_LOCAL,

        /** Changed on both sides, or a person's translation the platform disagrees with before the first sync. */
        CONFLICT,
    }

    data class PullItem(val key: ResourceKey, val local: ResourceValue?, val remote: ResourceValue, val action: Action)

    /**
     * What to do with the platform's translations [remote] for [locale]. Keys the default values don't have, or
     * that aren't translatable, are left out; so are empty translations (the platform has none yet).
     */
    fun planPull(set: TranslationSet, lock: TranslationLock, locale: Locale, remote: Map<ResourceKey, ResourceValue>): List<PullItem> =
        set.keys.mapNotNull { key ->
            val value = remote[key]?.takeIf { v -> v.texts.any { it.isNotBlank() } } ?: return@mapNotNull null
            if (set.source(key)?.translatable != true) return@mapNotNull null
            val local = set.translation(key, locale)?.value
            PullItem(key, local, value, action(local, value, lock.record(key, locale), lock.status(set, key, locale)))
        }

    private fun action(local: ResourceValue?, remote: ResourceValue, record: TranslationLock.Record?, status: TranslationStatus): Action {
        if (local == null) return Action.WRITE
        val localHash = TranslationLock.hash(local)
        val remoteHash = TranslationLock.hash(remote)
        if (localHash == remoteHash) return Action.SAME
        val synced = record?.syncedHash
        return when {
            // Never synced: a machine translation gives way to a translator's; a person's is a conflict.
            synced == null -> if (status.origin is Origin.Machine) Action.WRITE else Action.CONFLICT
            localHash == synced -> Action.WRITE
            remoteHash == synced -> Action.KEEP_LOCAL
            else -> Action.CONFLICT
        }
    }

    /** Translations of [locale] the platform doesn't have yet as they are: never synced, or changed in the IDE since. */
    fun pendingUpload(set: TranslationSet, lock: TranslationLock, locale: Locale): Set<ResourceKey> =
        set.keys.filterTo(linkedSetOf()) { key ->
            val translation = set.translation(key, locale) ?: return@filterTo false
            set.source(key)?.translatable == true && translation.value.texts.any { it.isNotBlank() } &&
                lock.record(key, locale)?.syncedHash != TranslationLock.hash(translation.value)
        }

    /**
     * Pairs the project's locales with the platform's language codes (`de`, `pt-BR`, `pt_BR`, `zh-CN`): exact
     * matches first, then a plain language with the one regional code the platform has for it (`es` with `es-ES`).
     * Codes that match no locale here come back as new locales, so their translations can be added; codes that
     * aren't languages, and [source], are ignored.
     */
    fun matchLocales(local: List<Locale>, remote: List<String>, source: String): Map<Locale, String> {
        val sourceLocale = parseCode(source)
        val parsed = remote.mapNotNull { code -> parseCode(code)?.let { it to code } }.filter { it.first != sourceLocale }
        val result = LinkedHashMap<Locale, String>()
        val unmatched = parsed.toMutableList()
        for ((locale, code) in parsed) if (locale in local) { result[locale] = code; unmatched.removeIf { it.second == code } }
        for ((locale, code) in unmatched.toList()) {
            val sameLanguage = unmatched.filter { it.first.language == locale.language }
            val plain = Locale(locale.language)
            if (sameLanguage.size == 1 && plain in local && plain !in result) {
                result[plain] = code
                unmatched.removeIf { it.second == code }
            }
        }
        for ((locale, code) in unmatched) if (locale !in result) result[locale] = code
        return result
    }

    /**
     * The name one `composeResources` folder's strings get on the platform, from its path in the project:
     * `composeApp/src/commonMain/composeResources` → `composeApp-commonMain-strings.xml`.
     */
    fun remoteFileName(relativeRoot: String): String {
        val parts = relativeRoot.split('/').filter { it.isNotEmpty() }.dropLast(1)
        val src = parts.lastIndexOf("src")
        val module = (if (src >= 0) parts.take(src) else parts.dropLast(1)).joinToString("-")
        val sourceSet = if (src >= 0) parts.drop(src + 1).joinToString("-") else parts.lastOrNull().orEmpty()
        return listOf(module, sourceSet, "strings.xml").filter { it.isNotEmpty() }.joinToString("-")
    }

    /** A platform's language code as a locale: `pt_BR`, `pt-br` and `zh-Hans` all work. */
    fun parseCode(code: String): Locale? = Locale.fromTag(code.trim().replace('_', '-'))
}
