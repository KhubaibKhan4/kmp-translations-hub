package io.github.translationshub.idea

import io.github.translationshub.core.ArrayValue
import io.github.translationshub.core.CheckOptions
import io.github.translationshub.core.Checks
import io.github.translationshub.core.Locale
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.PluralsValue
import io.github.translationshub.core.Problem
import io.github.translationshub.core.ResourceEntry
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.core.TranslationLock
import io.github.translationshub.core.TranslationSet
import io.github.translationshub.core.TranslationState
import io.github.translationshub.core.TranslationStatus
import javax.swing.table.AbstractTableModel

/** One cell: the default column has a null [locale]. */
data class Cell(val key: ResourceKey, val locale: Locale?)

/**
 * Keys down, `Key | Default | <locale>…` across, with each cell's problems and lock status. `<string>` cells are
 * edited in place; plurals and arrays in the side panel.
 */
class TranslationsTableModel(
    set: TranslationSet,
    lock: TranslationLock,
    private val options: () -> CheckOptions = { CheckOptions() },
    private val onEdit: (ResourceKey, Locale?, String) -> Unit,
) : AbstractTableModel() {

    var set: TranslationSet = set
        private set
    var problems: List<Problem> = emptyList()
        private set
    private var problemsByCell: Map<Cell, List<Problem>> = emptyMap()
    private var statuses: Map<Cell, TranslationStatus> = emptyMap()

    init {
        index(lock)
    }

    fun update(newSet: TranslationSet, lock: TranslationLock) {
        val columnsChanged = newSet.locales != set.locales
        set = newSet
        index(lock)
        if (columnsChanged) fireTableStructureChanged() else fireTableDataChanged()
    }

    private fun index(lock: TranslationLock) {
        problems = Checks.run(set, options())
        // A source-level problem (e.g. a <plurals> without `other`) belongs to the default column.
        problemsByCell = problems.groupBy { Cell(it.key, it.locale) }
        statuses = buildMap {
            for (key in set.keys) for (locale in set.locales) {
                if (set.translation(key, locale) != null) put(Cell(key, locale), lock.status(set, key, locale))
            }
        }
    }

    fun key(row: Int): ResourceKey = set.keys[row]

    /** Null for the Key and Default columns. */
    fun locale(column: Int): Locale? = set.locales.getOrNull(column - FIRST_LOCALE_COLUMN)

    fun cell(row: Int, column: Int) = Cell(key(row), locale(column))

    fun problems(cell: Cell): List<Problem> = problemsByCell[cell].orEmpty()

    fun status(cell: Cell): TranslationStatus? = statuses[cell]

    fun rowHasProblems(row: Int): Boolean {
        val key = key(row)
        return problemsByCell.keys.any { it.key == key }
    }

    fun rowIsMissing(row: Int): Boolean {
        val key = key(row)
        return set.source(key)?.translatable != false && set.locales.any { set.translation(key, it) == null }
    }

    fun rowIsStale(row: Int): Boolean {
        val key = key(row)
        return set.locales.any { statuses[Cell(key, it)]?.state == TranslationState.STALE }
    }

    /** Text of the key and every cell, for search. */
    fun searchText(row: Int): String {
        val key = key(row)
        return buildString {
            append(key.name)
            set.source(key)?.value?.texts?.forEach { append('\n').append(it) }
            for (locale in set.locales) set.translation(key, locale)?.value?.texts?.forEach { append('\n').append(it) }
        }
    }

    override fun getRowCount() = set.keys.size

    override fun getColumnCount() = FIRST_LOCALE_COLUMN + set.locales.size

    override fun getColumnName(column: Int) = when (column) {
        KEY_COLUMN -> "Key"
        SOURCE_COLUMN -> "Default"
        else -> locale(column)!!.tag
    }

    override fun getValueAt(row: Int, column: Int): Any? {
        val key = key(row)
        return when (column) {
            KEY_COLUMN -> key
            SOURCE_COLUMN -> set.source(key)
            else -> set.translation(key, locale(column)!!)
        }
    }

    override fun isCellEditable(row: Int, column: Int): Boolean {
        if (column == KEY_COLUMN) return false
        val key = key(row)
        if (key.kind != ResourceKind.STRING) return false
        return column == SOURCE_COLUMN || set.source(key)?.translatable != false
    }

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        val text = value as? String ?: return
        val current = (getValueAt(row, column) as? ResourceEntry)?.value as? TextValue
        if (current?.text == text || (current == null && text.isEmpty())) return
        onEdit(key(row), locale(column), text)
    }

    companion object {
        const val KEY_COLUMN = 0
        const val SOURCE_COLUMN = 1
        const val FIRST_LOCALE_COLUMN = 2

        /** One-line text for a cell. */
        fun display(entry: ResourceEntry?): String = when (val value = entry?.value) {
            null -> ""
            is TextValue -> value.text
            is PluralsValue -> value.items[PluralCategory.OTHER] ?: value.items.values.firstOrNull().orEmpty()
            is ArrayValue -> value.items.joinToString("  ·  ")
        }
    }
}
