package io.github.translationshub.idea

import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList

/** A combo box or list renderer that shows [text] of each item; `SimpleListCellRenderer.create` is deprecated. */
internal fun <T> listRenderer(text: (T?) -> String): SimpleListCellRenderer<T> = object : SimpleListCellRenderer<T>() {
    override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        this.text = text(value)
    }
}
