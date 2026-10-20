package io.github.translationshub.idea

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.StringsXmlParser
import io.github.translationshub.core.TextValue

/** Phase 0: edits change only the tag they touch, and Undo restores the file exactly. */
class StringsXmlWriterTest : BasePlatformTestCase() {

    private val original = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<resources>
        |    <!-- Home -->
        |    <string name="tab_home">Home</string>
        |    <string   name="tab_khata"  >Khata</string>
        |
        |    <!-- Errors -->
        |    <string name="shop_qr_desc">It appears on each customer\'s statement.</string>
        |    <plurals name="files">
        |        <item quantity="one">One file</item>
        |        <item quantity="other">%d files</item>
        |    </plurals>
        |    <string-array name="days">
        |        <item>Mon</item>
        |        <item>Tue</item>
        |    </string-array>
        |</resources>
        |""".trimMargin()

    private lateinit var file: XmlFile

    override fun setUp() {
        super.setUp()
        file = myFixture.configureByText("strings.xml", original) as XmlFile
    }

    private fun edit(block: () -> Unit) = TranslationsWriteCommand.run(project, "Edit translation", listOf(file), block)

    private val text get() = myFixture.editor.document.text

    fun `test changing a string touches only its line`() {
        edit { StringsXmlWriter.setText(file, "tab_khata", "Khata & Ledger <3") }
        assertEquals(original.replace(">Khata</string>", ">Khata &amp; Ledger &lt;3</string>"), text)
        val parsed = StringsXmlParser.parse(text).first { it.name == "tab_khata" }
        assertEquals(TextValue("Khata & Ledger <3"), parsed.value)
    }

    fun `test Compose escapes and inline markup pass through`() {
        edit { StringsXmlWriter.setText(file, "tab_home", "It\\'s <b>home</b>") }
        assertTrue(text, text.contains("<string name=\"tab_home\">It\\'s <b>home</b></string>"))
    }

    fun `test a new string goes after the key it follows in the source`() {
        edit { StringsXmlWriter.setText(file, "tab_shop", "Shop", after = ResourceKey(ResourceKind.STRING, "tab_khata")) }
        assertEquals(
            original.replace(
                "    <string   name=\"tab_khata\"  >Khata</string>\n",
                "    <string   name=\"tab_khata\"  >Khata</string>\n    <string name=\"tab_shop\">Shop</string>\n",
            ),
            text,
        )
    }

    fun `test a missing plural form is inserted in CLDR order`() {
        edit { StringsXmlWriter.setPluralItem(file, "files", PluralCategory.FEW, "%d pliki") }
        assertEquals(
            original.replace(
                "        <item quantity=\"one\">One file</item>\n",
                "        <item quantity=\"one\">One file</item>\n        <item quantity=\"few\">%d pliki</item>\n",
            ),
            text,
        )
    }

    fun `test array items are replaced in place`() {
        edit { StringsXmlWriter.setArrayItems(file, "days", listOf("Mo")) }
        assertEquals(original.replace("        <item>Mon</item>\n        <item>Tue</item>\n", "        <item>Mo</item>\n"), text)
    }

    fun `test delete leaves no blank line and rename keeps the value`() {
        edit {
            StringsXmlWriter.delete(file, ResourceKey(ResourceKind.STRING, "tab_home"))
            StringsXmlWriter.rename(file, ResourceKey(ResourceKind.STRING, "tab_khata"), "tab_ledger")
        }
        assertEquals(
            original.replace("    <string name=\"tab_home\">Home</string>\n", "")
                .replace("name=\"tab_khata\"", "name=\"tab_ledger\""),
            text,
        )
    }

    fun `test several edits are one undo step that restores the file exactly`() {
        edit {
            StringsXmlWriter.setText(file, "tab_home", "Start")
            StringsXmlWriter.setPluralItem(file, "files", PluralCategory.MANY, "%d plików")
            StringsXmlWriter.setText(file, "brand_new", "New")
        }
        val edited = text
        assertFalse(edited == original)
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(original, text)
        // The fixture's own setup is on the stack too, so check Redo rather than that nothing is left to undo.
        undo.redo(editor)
        assertEquals(edited, text)
    }
}
