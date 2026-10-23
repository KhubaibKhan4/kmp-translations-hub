package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Origin
import io.github.translationshub.core.ResourceFormat
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.core.TranslationState
import io.github.translationshub.idea.translate.TranslateChanges

/** An Android app's `res` folder: found like `composeResources`, with Android's rules for quotes and references. */
class AndroidResTest : BasePlatformTestCase() {

    private val de = Locale("de")
    private fun key(name: String) = ResourceKey(ResourceKind.STRING, name)
    private lateinit var strings: VirtualFile
    private lateinit var german: VirtualFile
    private lateinit var controller: TranslationsController

    override fun setUp() {
        super.setUp()
        fun add(path: String, body: String) = myFixture.addFileToProject(
            "app/src/main/res/$path", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body</resources>\n",
        ).virtualFile
        strings = add("values/strings.xml",
            "    <string name=\"app_name\">Shopwise</string>\n    <string name=\"title\">@string/app_name</string>\n" +
                "    <string name=\"offline\">You\\'re offline</string>\n    <string name=\"bye\">Bye</string>\n")
        add("values/colors.xml", "    <color name=\"primary\">#6200EE</color>\n")
        add("values-night/colors.xml", "    <color name=\"primary\">#BB86FC</color>\n")
        german = add("values-de/strings.xml", "    <string name=\"bye\">Tschüss</string>\n")
        controller = TranslationsController(project, strings.parent.parent)
    }

    private fun text(file: VirtualFile) = FileDocumentManager.getInstance().getDocument(file)!!.text

    fun `test res folders are found, and only strings files get the Translations tab`() {
        val root = ResourceFolders.rootOf(strings)!!
        assertEquals("res", root.name)
        assertEquals(ResourceFormat.ANDROID, ResourceFolders.format(root))
        val provider = TranslationsEditorProvider()
        assertTrue(provider.accept(project, strings))
        assertTrue(provider.accept(project, german))
        assertFalse(provider.accept(project, root.findFileByRelativePath("values/colors.xml")!!))
        val set = controller.read()
        assertEquals(listOf(de), set.locales)
        assertEquals(ResourceFormat.ANDROID, set.format)
        // values-night holds only colors: a variant folder, not a column.
        assertEquals(listOf("values-night"), set.variantFiles.map { it.qualifiers.folderName })
    }

    fun `test a string pointing to another one isn't translated`() {
        val set = controller.read()
        assertFalse(set.source(key("title"))!!.translatable)
        assertFalse(key("title") in TranslateChanges.pending(controller, false).getValue(de))
    }

    fun `test machine translations and edits are escaped, and the lock records the escaped text`() {
        controller.applyTranslations(de, mapOf(key("offline") to TextValue("Du bist 'offline'")), "deepl")
        assertTrue(text(german).contains("<string name=\"offline\">Du bist \\'offline\\'</string>"))
        val lock = controller.lock()
        assertEquals(TranslationState.UP_TO_DATE, lock.status(controller.read(), key("offline"), de).state)
        assertEquals(Origin.Machine("deepl"), lock.status(controller.read(), key("offline"), de).origin)

        controller.setValue(key("bye"), de, TextValue("@bientôt, \"bis bald\""))
        assertTrue(text(german).contains("<string name=\"bye\">\\@bientôt, \\\"bis bald\\\"</string>"))
    }

    fun `test compose resources keep their text as written`() {
        val compose = myFixture.addFileToProject("shared/src/commonMain/composeResources/values/strings.xml",
            "<resources>\n    <string name=\"offline\">You're offline</string>\n</resources>\n").virtualFile
        val shared = TranslationsController(project, compose.parent.parent)
        shared.applyTranslations(de, mapOf(key("offline") to TextValue("Du bist 'offline'")), "deepl")
        val file = compose.parent.parent.findFileByRelativePath("values-de/strings.xml")!!
        assertTrue(text(file).contains("Du bist 'offline'"))
    }

    fun `test an unescaped quote is an error with a fix`() {
        myFixture.enableInspections(TranslationsInspection())
        val bad = myFixture.addFileToProject("app/src/main/res/values-fr/strings.xml",
            "<resources>\n    <string name=\"bye\">À l'heure</string>\n</resources>\n").virtualFile
        myFixture.configureFromExistingVirtualFile(bad)
        val error = myFixture.doHighlighting().single { "unescaped '" in (it.description ?: "") }
        assertEquals(com.intellij.lang.annotation.HighlightSeverity.ERROR, error.severity)
        myFixture.launchAction(myFixture.getAllQuickFixes().single { it.familyName == "Escape quotes for Android" })
        assertTrue(myFixture.editor.document.text.contains("À l\\'heure"))
    }
}
