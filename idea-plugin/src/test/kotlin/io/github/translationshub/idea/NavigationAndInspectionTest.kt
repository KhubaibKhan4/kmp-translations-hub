package io.github.translationshub.idea

import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind

class NavigationAndInspectionTest : BasePlatformTestCase() {

    private fun resources(module: String, folder: String, body: String) = myFixture.addFileToProject(
        "$module/src/commonMain/composeResources/$folder/strings.xml",
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body</resources>\n",
    ).virtualFile

    fun `test accessor under the caret`() {
        val text = "Text(stringResource(Res.string.tab_home))\nval n = pluralStringResource(Res.plurals.files, 3)"
        assertEquals(ResourceKey(ResourceKind.STRING, "tab_home"), ResourceUsages.keyAt(text, text.indexOf("tab_home") + 2)!!.first)
        assertEquals(ResourceKey(ResourceKind.PLURALS, "files"), ResourceUsages.keyAt(text, text.indexOf("files"))!!.first)
        assertNull(ResourceUsages.keyAt(text, text.indexOf("Text")))
    }

    fun `test ctrl-click goes to the default values of the nearest module`() {
        resources("core", "values", "    <string name=\"hello\">Core hello</string>\n")
        val feature = resources("feature", "values", "    <string name=\"hello\">Feature hello</string>\n")
        val kotlin = myFixture.addFileToProject("feature/src/commonMain/kotlin/Screen.kt", "fun s() = stringResource(Res.string.hello)\n")
        val offset = kotlin.text.indexOf("hello")
        val targets = ResourceGotoDeclarationHandler().getGotoDeclarationTargets(kotlin.findElementAt(offset), offset, null)!!
        val target = targets.single() as XmlAttributeValue
        assertEquals(feature, target.containingFile.virtualFile)
        assertEquals("hello", target.value)
    }

    fun `test usages are found by kind and name`() {
        resources("app", "values", "    <string name=\"hello\">Hello</string>\n")
        myFixture.addFileToProject("app/src/commonMain/kotlin/A.kt", "val a = Res.string.hello\nval b = Res.plurals.hello\nval c = Res.string.hello_world\n")
        myFixture.addFileToProject("app/src/commonMain/kotlin/B.kt", "val d = Res.string.hello\n")
        val uses = ResourceUsages.find(project, ResourceKey(ResourceKind.STRING, "hello"))
        assertEquals(listOf("A.kt", "B.kt"), uses.map { it.first.name }.sorted())
    }

    fun `test the inspection reports missing locales on the default file and problems on the locale file`() {
        val source = resources(
            "app", "values",
            "    <string name=\"hello\">Hello %1\$s</string>\n    <string name=\"bye\">Bye</string>\n",
        )
        val german = resources("app", "values-de", "    <string name=\"hello\">Hallo %1\$d</string>\n    <string name=\"old\">Alt</string>\n")
        myFixture.enableInspections(TranslationsInspection())

        myFixture.configureFromExistingVirtualFile(source)
        val onSource = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(onSource.toString(), "Missing in de" in onSource)

        myFixture.configureFromExistingVirtualFile(german)
        val onGerman = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(onGerman.toString(), onGerman.any { it.startsWith("Placeholders") })
        assertTrue(onGerman.toString(), onGerman.any { it.startsWith("Not in the default values") })

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("\"old\"") + 2)
        myFixture.launchAction(myFixture.findSingleIntention("Delete key left behind"))
        assertFalse(myFixture.editor.document.text.contains("old"))
    }

    fun `test add missing plural forms quick fix`() {
        resources("app", "values", "    <plurals name=\"files\">\n        <item quantity=\"one\">One</item>\n        <item quantity=\"other\">%d files</item>\n    </plurals>\n")
        val polish = resources("app", "values-pl", "    <plurals name=\"files\">\n        <item quantity=\"one\">Jeden</item>\n        <item quantity=\"other\">%d pliku</item>\n    </plurals>\n")
        myFixture.enableInspections(TranslationsInspection())
        myFixture.configureFromExistingVirtualFile(polish)
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("\"files\"") + 2)
        myFixture.launchAction(myFixture.findSingleIntention("Add few, many"))
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <plurals name=\"files\">\n" +
                "        <item quantity=\"one\">Jeden</item>\n        <item quantity=\"few\">%d pliku</item>\n" +
                "        <item quantity=\"many\">%d pliku</item>\n        <item quantity=\"other\">%d pliku</item>\n    </plurals>\n</resources>\n",
            myFixture.editor.document.text,
        )
    }
}
