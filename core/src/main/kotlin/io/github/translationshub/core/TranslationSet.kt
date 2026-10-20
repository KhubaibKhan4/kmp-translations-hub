package io.github.translationshub.core

/**
 * Every `values…` file of one `composeResources` folder (one module and source set): what the editor shows as one
 * table, keys down and locales across. The default `values` folder is the source column.
 *
 * Folders with other qualifiers (`values-dark`, `values-ur-dark`) are kept in [variantFiles] and not shown as
 * columns: they override single keys for a theme or density rather than translate the app.
 */
class TranslationSet(
    /** The `composeResources` folder, as a path. */
    val root: String,
    files: List<StringsFile>,
) {
    val sourceFiles: List<StringsFile> = files.filter { it.qualifiers.locale == null && it.qualifiers.other.isEmpty() }
    val variantFiles: List<StringsFile> = files.filter { it.qualifiers.other.isNotEmpty() }
    private val localeFiles: Map<Locale, List<StringsFile>> = files
        .filter { it.qualifiers.locale != null && it.qualifiers.other.isEmpty() }
        .groupBy { it.qualifiers.locale!! }

    val locales: List<Locale> = localeFiles.keys.sorted()

    private val source: Map<ResourceKey, Located> = index(sourceFiles)
    private val translations: Map<Locale, Map<ResourceKey, Located>> = localeFiles.mapValues { (_, files) -> index(files) }

    /**
     * Rows: the source keys in file order, then keys that exist only in a translation (left behind after the
     * source key was renamed or deleted).
     */
    val keys: List<ResourceKey> = buildList {
        addAll(source.keys)
        translations.values.flatMap { it.keys }.distinct().filterNot { it in source }.sorted().forEach(::add)
    }

    fun source(key: ResourceKey): ResourceEntry? = source[key]?.entry

    fun translation(key: ResourceKey, locale: Locale): ResourceEntry? = translations[locale]?.get(key)?.entry

    /** The source file that holds [key]; a new translation goes into the file with the same name in the locale folder. */
    fun sourceFile(key: ResourceKey): StringsFile? = source[key]?.file

    fun translationFile(key: ResourceKey, locale: Locale): StringsFile? = translations[locale]?.get(key)?.file

    fun filesFor(locale: Locale): List<StringsFile> = localeFiles[locale].orEmpty()

    private class Located(val entry: ResourceEntry, val file: StringsFile)

    private fun index(files: List<StringsFile>): Map<ResourceKey, Located> {
        val map = LinkedHashMap<ResourceKey, Located>()
        for (file in files) for (entry in file.entries) map.putIfAbsent(entry.key, Located(entry, file))
        return map
    }
}
