package io.github.translationshub.idea

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.Locale
import io.github.translationshub.core.ResourceEntry
import io.github.translationshub.core.TextValue
import java.awt.image.BufferedImage
import kotlin.system.measureTimeMillis

class TranslationsEditorTest : BasePlatformTestCase() {

    private fun resources(vararg files: Pair<String, String>): VirtualFile =
        files.map { (folder, body) ->
            myFixture.addFileToProject(
                "app/src/commonMain/composeResources/$folder/strings.xml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body</resources>\n",
            ).virtualFile
        }.first()

    private fun open(file: VirtualFile): TranslationsEditor {
        assertTrue(TranslationsEditorProvider().accept(project, file))
        return (TranslationsEditorProvider().createEditor(project, file) as TranslationsEditor).also {
            Disposer.register(testRootDisposable, it)
        }
    }

    private fun text(file: VirtualFile) = FileDocumentManager.getInstance().getDocument(file)!!.text

    fun `test the table shows every key against every locale`() {
        val source = resources(
            "values" to "    <string name=\"hello\">Hello</string>\n    <plurals name=\"files\"><item quantity=\"other\">%d files</item></plurals>\n",
            "values-ur" to "    <string name=\"hello\">ہیلو</string>\n",
            "values-de" to "",
        )
        val model = open(source).model
        assertEquals(listOf("Key", "Default", "de", "ur"), (0 until model.columnCount).map(model::getColumnName))
        assertEquals(2, model.rowCount)
        assertEquals(TextValue("ہیلو"), (model.getValueAt(0, 3) as ResourceEntry).value)
        assertNull(model.getValueAt(0, 2))
        assertFalse("plurals are edited in Phase 1's side panel", model.isCellEditable(1, 2))
    }

    fun `test the accept check only takes values files in composeResources`() {
        val other = myFixture.addFileToProject("app/src/main/res/values/strings.xml", "<resources/>").virtualFile
        assertFalse(TranslationsEditorProvider().accept(project, other))
    }

    fun `test editing a missing cell writes it in source order, and Undo in the table reverts it`() {
        val source = resources(
            "values" to "    <string name=\"a\">A</string>\n    <string name=\"b\">B</string>\n    <string name=\"c\">C</string>\n",
            "values-de" to "    <!-- keep me -->\n    <string name=\"a\">A-de</string>\n    <string name=\"c\">C-de</string>\n",
        )
        val german = source.parent.parent.findChild("values-de")!!.findChild("strings.xml")!!
        val before = text(german)
        val editor = open(source)

        editor.model.setValueAt("B-de", 1, 2)
        assertEquals(before.replace("A-de</string>\n", "A-de</string>\n    <string name=\"b\">B-de</string>\n"), text(german))
        assertEquals(TextValue("B-de"), editor.model.set.translation(editor.model.key(1), Locale("de"))!!.value)

        val undo = UndoManager.getInstance(project)
        assertTrue("Undo should be available in the Translations tab", undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(before, text(german))
    }

    fun `test 2000 keys by 15 locales`() {
        val locales = listOf("ar", "de", "es", "fr", "hi", "it", "ja", "ko", "nl", "pl", "pt-rBR", "ru", "tr", "uk", "ur")
        fun body(suffix: String) = buildString {
            for (i in 0 until 1800) append("    <string name=\"key_$i\">Text $i for %1\$s $suffix</string>\n")
            for (i in 0 until 150) append("    <plurals name=\"plural_$i\">\n        <item quantity=\"one\">One $suffix</item>\n        <item quantity=\"other\">%d $suffix</item>\n    </plurals>\n")
            for (i in 0 until 50) append("    <string-array name=\"array_$i\">\n        <item>A $suffix</item>\n        <item>B $suffix</item>\n    </string-array>\n")
        }
        val source = resources("values" to body("en"), *locales.map { "values-$it" to body(it) }.toTypedArray())

        lateinit var editor: TranslationsEditor
        val open = measureTimeMillis { editor = open(source) }
        assertEquals(2000, editor.model.rowCount)
        assertEquals(17, editor.model.columnCount)

        val component = editor.component.apply { setSize(1600, 1000); doLayout() }
        val image = BufferedImage(1600, 1000, BufferedImage.TYPE_INT_ARGB)
        val paint = measureTimeMillis { repeat(10) { component.paint(image.graphics) } } / 10

        val edit = measureTimeMillis { editor.model.setValueAt("Neu", 1000, 3) }
        val reload = measureTimeMillis { editor.reload() }
        println("Editor 2000 x 15: open $open ms, paint $paint ms per frame, edit + reload $edit ms, reload $reload ms")
        assertEquals(TextValue("Neu"), editor.model.set.translation(editor.model.key(1000), Locale("de"))!!.value)
        assertTrue("Reloading took $reload ms", reload < 1000)
    }

    fun `test search and filters`() {
        val source = resources(
            "values" to "    <string name=\"hello\">Hello</string>\n    <string name=\"bye\">Bye %1\$s</string>\n    <string name=\"brand\" translatable=\"false\">Acme</string>\n",
            "values-de" to "    <string name=\"hello\">Hallo</string>\n    <string name=\"bye\">Tschüss %1\$d</string>\n",
            "values-fr" to "    <string name=\"hello\">Bonjour</string>\n",
        )
        val editor = open(source)
        fun shown() = (0 until editor.table.rowCount).map { editor.model.key(editor.table.convertRowIndexToModel(it)).name }

        editor.search.text = "bonj"
        assertEquals(listOf("hello"), shown())
        editor.search.text = ""
        editor.filter.item = TranslationsEditor.Filter.MISSING
        assertEquals(listOf("bye"), shown())
        editor.filter.item = TranslationsEditor.Filter.PROBLEMS
        assertEquals(listOf("bye"), shown())
        editor.filter.item = TranslationsEditor.Filter.ALL
        assertEquals(3, shown().size)
        assertTrue(editor.model.problems(Cell(editor.model.key(1), Locale("de"))).any { it is io.github.translationshub.core.Problem.PlaceholderMismatch })
    }

    fun `test the side panel edits plural forms the language needs`() {
        val source = resources(
            "values" to "    <plurals name=\"files\">\n        <item quantity=\"one\">One file</item>\n        <item quantity=\"other\">%d files</item>\n    </plurals>\n",
            "values-pl" to "",
        )
        val editor = open(source)
        val pl = Locale("pl")
        editor.select(Cell(editor.model.key(0), pl))
        assertEquals(Cell(editor.model.key(0), pl), editor.detail.shownCell)
        // Polish uses one, few, many and other: all four get a field, empty ones aren't written except `other`.
        val value = editor.detail.editedValue() as io.github.translationshub.core.PluralsValue
        assertEquals(mapOf(io.github.translationshub.core.PluralCategory.OTHER to ""), value.items)

        editor.controller.setValue(editor.model.key(0), pl, io.github.translationshub.core.PluralsValue(mapOf(
            io.github.translationshub.core.PluralCategory.ONE to "1 plik",
            io.github.translationshub.core.PluralCategory.FEW to "%d pliki",
            io.github.translationshub.core.PluralCategory.MANY to "%d plików",
            io.github.translationshub.core.PluralCategory.OTHER to "%d pliku",
        )))
        editor.reload()
        assertTrue(editor.model.problems.isEmpty())
        assertEquals(4, (editor.detail.editedValue() as io.github.translationshub.core.PluralsValue).items.size)
    }
}
