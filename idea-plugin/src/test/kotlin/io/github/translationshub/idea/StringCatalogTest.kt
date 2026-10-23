package io.github.translationshub.idea

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Origin
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.PluralsValue
import io.github.translationshub.core.ResourceFormat
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.core.TranslationState
import io.github.translationshub.idea.translate.TranslateChanges

/** An Xcode String Catalog in the Translations tab: every change goes through the catalog and leaves the rest as it was. */
class StringCatalogTest : BasePlatformTestCase() {

    private val de = Locale("de")
    private val fr = Locale("fr")
    private fun key(name: String) = ResourceKey(ResourceKind.STRING, name)
    // The core module's sample, in Xcode's layout (tests run from idea-plugin/).
    private val original = java.io.File(System.getProperty("user.dir"), "../core/src/test/resources/Sample.xcstrings").readText()
    private lateinit var catalog: VirtualFile
    private lateinit var controller: TranslationsController

    override fun setUp() {
        super.setUp()
        catalog = myFixture.addFileToProject("iosApp/iosApp/Localizable.xcstrings", original).virtualFile
        controller = TranslationsController(project, catalog)
    }

    private fun text() = FileDocumentManager.getInstance().getDocument(catalog)!!.text

    fun `test a catalog is its own root and gets the Translations tab`() {
        assertEquals(catalog, ResourceFolders.rootOf(catalog))
        assertEquals(ResourceFormat.IOS, ResourceFolders.format(catalog))
        assertTrue(TranslationsEditorProvider().accept(project, catalog))
        assertEquals(listOf(de, fr), controller.read().locales)
    }

    fun `test machine translations are written as translated, with a lock file of the catalog's own`() {
        assertTrue(key("Hello, %@!") in TranslateChanges.pending(controller, false).getValue(fr))
        controller.applyTranslations(fr, mapOf(key("Hello, %@!") to TextValue("Bonjour, %@ !")), "deepl")
        assertTrue(text(), text().contains("\"state\" : \"translated\",\n            \"value\" : \"Bonjour, %@ !\""))
        assertNotNull(catalog.parent.findChild("Localizable.translations.lock.json"))
        assertNull(catalog.parent.findChild("translations.lock.json"))
        val status = controller.lock().status(controller.read(), key("Hello, %@!"), fr)
        assertEquals(TranslationState.UP_TO_DATE, status.state)
        assertEquals(Origin.Machine("deepl"), status.origin)
        // Only that localization changed: same number of lines as before.
        assertEquals(original.lines().size, text().lines().size)
    }

    fun `test plurals, renames, deletes and a new language`() {
        val plural = ResourceKey(ResourceKind.PLURALS, "%lld new posts")
        controller.setValue(plural, fr, PluralsValue(mapOf(PluralCategory.ONE to "%lld nouveau post", PluralCategory.MANY to "%lld nouveaux posts", PluralCategory.OTHER to "%lld nouveaux posts")))
        assertEquals("%lld nouveau post", (controller.read().translation(plural, fr)!!.value as PluralsValue).items[PluralCategory.ONE])

        controller.renameKey(key("Sign out"), "Log out")
        controller.deleteKey(key("🙂"))
        controller.addLocales(listOf(Locale("ja")))
        val set = controller.read()
        assertNotNull(set.source(key("Log out")))
        assertNull(set.source(key("🙂")))
        assertTrue(Locale("ja") in set.locales)
        assertNull(set.translation(key("Hello, %@!"), Locale("ja")))
        assertTrue(key("Hello, %@!") in TranslateChanges.pending(controller, false).getValue(Locale("ja")))
    }

    fun `test keys can be any text`() {
        controller.addKey("Tap to retry", TextValue("Tap to retry"))
        assertEquals(TextValue("Tap to retry"), controller.read().source(key("Tap to retry"))!!.value)
        assertTrue(TranslationsController.isValidKeyName("Hello, %@!", ResourceFormat.IOS))
        assertFalse(TranslationsController.isValidKeyName("Hello, %@!", ResourceFormat.COMPOSE))
    }

    fun `test one undo restores the catalog byte for byte`() {
        myFixture.configureFromExistingVirtualFile(catalog)
        controller.setValue(key("Hello, %@!"), de, TextValue("Servus, %@!"))
        assertTrue(text().contains("Servus"))
        TestDialogManager.setTestDialog(TestDialog.OK, testRootDisposable)
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        assertEquals(original, text())
    }

    fun `test sync isn't offered for catalogs yet`() {
        var shown: String? = null
        TestDialogManager.setTestDialog({ shown = it; 0 }, testRootDisposable)
        io.github.translationshub.idea.sync.SyncRunner.start(project, controller) {}
        assertTrue(shown.orEmpty(), shown.orEmpty().contains("String Catalogs aren't supported yet"))
    }
}
