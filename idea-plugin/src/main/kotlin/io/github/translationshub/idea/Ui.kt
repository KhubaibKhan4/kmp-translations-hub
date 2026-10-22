package io.github.translationshub.idea

import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList

/**
 * Text that wraps at [width] (unscaled px) instead of asking for one long line: a form with an unwrapped hint wider
 * than the window squeezes every text field next to it down to nothing.
 */
internal fun wrapped(text: String, width: Int = 440) =
    "<html><div style=\"width:${com.intellij.util.ui.JBUI.scale(width)}px\">$text</div></html>"

/** Keeps a text field at its normal width when its form is short of space. */
internal fun <T : javax.swing.JComponent> T.keepWidth(): T = apply { minimumSize = preferredSize }

/** A combo box or list renderer that shows [text] of each item; `SimpleListCellRenderer.create` is deprecated. */
internal fun <T> listRenderer(text: (T?) -> String): SimpleListCellRenderer<T> = object : SimpleListCellRenderer<T>() {
    override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        this.text = text(value)
    }
}
