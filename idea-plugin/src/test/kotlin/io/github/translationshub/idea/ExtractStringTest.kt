package io.github.translationshub.idea

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.idea.kotlin.HardcodedComposeTextInspection

class ExtractStringTest : BasePlatformTestCase() {

    private val strings = "app/src/commonMain/composeResources/values/strings.xml"
    private val original = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"hello\">Taken</string>\n</resources>\n"

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(strings, original)
        // Another file of the module tells which package the generated Res class is in.
        myFixture.addFileToProject("app/src/commonMain/kotlin/Other.kt", "import myapp.app.generated.resources.Res\nimport myapp.app.generated.resources.hello\n")
    }

    private fun xml() = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(strings))!!.text

    private fun configure(code: String) {
        val file = myFixture.addFileToProject("app/src/commonMain/kotlin/Screen.kt", code)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    fun `test extracting a template from a composable`() {
        configure("package ui\n\n@Composable\nfun Greeting(name: String, count: Int) {\n    Text(\"Hello, \$name! You have \${count + 1} new\")\n}\n")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Hello") + 1)
        myFixture.launchAction(myFixture.findSingleIntention("Extract to composeResources"))

        // "hello" is taken, so the suggested name goes on until it is unique.
        assertTrue(xml(), xml().contains("<string name=\"hello_you_have_new\">Hello, %1\$s! You have %2\$s new</string>"))
        val code = myFixture.editor.document.text
        assertTrue(code, code.contains("Text(stringResource(Res.string.hello_you_have_new, name, count + 1))"))
        assertTrue(code, code.contains("import org.jetbrains.compose.resources.stringResource"))
        assertTrue(code, code.contains("import myapp.app.generated.resources.Res"))
        assertTrue(code, code.contains("import myapp.app.generated.resources.hello_you_have_new"))
    }

    fun `test one undo restores the Kotlin file and strings xml`() {
        val code = "@Composable\nfun A() {\n    Text(\"Save changes\")\n}\n"
        configure(code)
        myFixture.editor.caretModel.moveToOffset(code.indexOf("Save") + 1)
        myFixture.launchAction(myFixture.findSingleIntention("Extract to composeResources"))
        assertTrue(xml().contains("save_changes"))

        // The Undo touches strings.xml too, so the IDE asks first, as it does after a refactoring.
        TestDialogManager.setTestDialog(TestDialog.OK, testRootDisposable)
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        assertEquals(code, myFixture.editor.document.text)
        assertEquals(original, xml())
    }

    fun `test not offered outside Compose UI or for text without words`() {
        configure("fun log() {\n    println(\"Saving changes\")\n}\n@Composable\nfun B() {\n    Text(\"42\")\n}\n")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Saving") + 1)
        assertEmpty(myFixture.filterAvailableIntentions("Extract to composeResources"))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("42") + 1)
        assertEmpty(myFixture.filterAvailableIntentions("Extract to composeResources"))
    }

    fun `test the inspection finds text passed to Compose calls`() {
        myFixture.enableInspections(HardcodedComposeTextInspection())
        configure(
            "@Composable\nfun C() {\n    Column {\n        Text(\"Welcome back\")\n        TextField(value = v, label = \"Your name\", onValueChange = {})\n" +
                "        Box(Modifier.testTag(\"login_box\"))\n    }\n}\n",
        )
        val found = myFixture.doHighlighting().filter { it.description == "Hardcoded text in Compose UI" }.map { myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset)) }
        assertEquals(listOf("\"Welcome back\"", "\"Your name\""), found)
    }

    fun `test Alt+Enter lists the fix once when the inspection is on`() {
        myFixture.enableInspections(HardcodedComposeTextInspection())
        configure("@Composable\nfun A() {\n    Text(\"Save changes\")\n}\n")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Save") + 1)
        myFixture.doHighlighting()
        assertEquals(1, myFixture.filterAvailableIntentions("Extract to composeResources").size)
    }
}
