package io.github.translationshub.core

/** CLDR plural categories, as the `quantity` attribute of a `<plurals>` item. */
enum class PluralCategory(val xmlName: String) {
    ZERO("zero"), ONE("one"), TWO("two"), FEW("few"), MANY("many"), OTHER("other");

    companion object {
        fun fromXml(name: String): PluralCategory? = entries.firstOrNull { it.xmlName == name }
    }
}

enum class ResourceKind(val xmlTag: String) {
    STRING("string"), PLURALS("plurals"), ARRAY("string-array");

    companion object {
        fun fromXml(tag: String): ResourceKind? = entries.firstOrNull { it.xmlTag == tag }
    }
}

/**
 * Compose Multiplatform generates `Res.string.x`, `Res.plurals.x` and `Res.array.x` separately, so a key is a name
 * within its kind.
 */
data class ResourceKey(val kind: ResourceKind, val name: String) : Comparable<ResourceKey> {
    override fun compareTo(other: ResourceKey): Int = compareValuesBy(this, other, { it.name }, { it.kind })
    override fun toString(): String = if (kind == ResourceKind.STRING) name else "$name (${kind.xmlTag})"
}

/**
 * Text is kept as written between the tags, with XML entities decoded but the Compose escapes (`\'`, `\n`, `\"`)
 * and any inline markup left as they are, so editing a cell never changes what the user didn't touch.
 */
sealed interface ResourceValue {
    val kind: ResourceKind

    /** Every piece of text in the value, in order: the string, each plural item, each array item. */
    val texts: List<String>
}

data class TextValue(val text: String) : ResourceValue {
    override val kind get() = ResourceKind.STRING
    override val texts get() = listOf(text)
}

data class PluralsValue(val items: Map<PluralCategory, String>) : ResourceValue {
    override val kind get() = ResourceKind.PLURALS
    override val texts get() = PluralCategory.entries.mapNotNull { items[it] }
}

data class ArrayValue(val items: List<String>) : ResourceValue {
    override val kind get() = ResourceKind.ARRAY
    override val texts get() = items
}

data class ResourceEntry(
    val name: String,
    val value: ResourceValue,
    /** `translatable="false"`: lives in the default file only and is never flagged or machine-translated. */
    val translatable: Boolean = true,
    /** The XML comment right above the entry: context for translators and AI translation. */
    val comment: String? = null,
) {
    val key: ResourceKey get() = ResourceKey(value.kind, name)
}

/** One XML file in a `values…` folder. A folder can hold several (`strings.xml`, `strings_errors.xml`). */
data class StringsFile(val path: String, val qualifiers: FolderQualifiers, val entries: List<ResourceEntry>) {
    val fileName: String get() = path.substringAfterLast('/')
}
