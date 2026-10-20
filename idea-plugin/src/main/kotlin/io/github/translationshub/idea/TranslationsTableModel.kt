package io.github.translationshub.idea

import io.github.translationshub.core.ArrayValue
import io.github.translationshub.core.Locale
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.PluralsValue
import io.github.translationshub.core.ResourceEntry
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.core.TranslationSet
import javax.swing.table.AbstractTableModel

/**
 * Keys down, `Key | Default | <locale>…` across. Phase 0 edits `<string>` cells; plurals and arrays are shown as a
 * summary and are edited in Phase 1's side panel.
 */
class TranslationsTableModel(set: TranslationSet, private val onEdit: (ResourceKey, Locale?, String) -> Unit) :
    AbstractTableModel() {

    var set: TranslationSet = set
        private set

    fun update(newSet: TranslationSet) {
        val columnsChanged = newSet.locales != set.locales
        set = newSet
        if (columnsChanged) fireTableStructureChanged() else fireTableDataChanged()
    }

    fun key(row: Int): ResourceKey = set.keys[row]

    /** Null for the Key and Default columns. */
    fun locale(column: Int): Locale? = set.locales.getOrNull(column - FIRST_LOCALE_COLUMN)

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
