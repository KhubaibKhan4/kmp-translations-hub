package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.ArrayValue
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Origin
import io.github.translationshub.core.PluralCategory
import io.github.translationshub.core.PluralsValue
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.core.TranslationLock
import io.github.translationshub.core.TranslationState

class TranslationsControllerTest : BasePlatformTestCase() {

    private val de = Locale("de")
    private val hello = ResourceKey(ResourceKind.STRING, "hello")
    private val files = ResourceKey(ResourceKind.PLURALS, "files")
    private lateinit var root: VirtualFile
    private lateinit var controller: TranslationsController

    override fun setUp() {
        super.setUp()
        fun add(folder: String, body: String) = myFixture.addFileToProject(
            "shared/src/commonMain/composeResources/$folder/strings.xml",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body</resources>\n",
        ).virtualFile
        add(
            "values",
            "    <string name=\"hello\">Hello</string>\n" +
                "    <plurals name=\"files\">\n        <item quantity=\"one\">One file</item>\n        <item quantity=\"other\">%d files</item>\n    </plurals>\n" +
                "    <string name=\"bye\">Bye</string>\n",
        )
        root = add("values-de", "    <string name=\"hello\">Hallo</string>\n").parent.parent
        controller = TranslationsController(project, root)
    }

    private fun text(folder: String) =
        FileDocumentManager.getInstance().getDocument(root.findChild(folder)!!.findChild("strings.xml")!!)!!.text

    fun `test plurals are written in order and dropped forms removed`() {
        controller.setValue(files, de, PluralsValue(mapOf(PluralCategory.OTHER to "%d Dateien", PluralCategory.ONE to "Eine Datei")))
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"hello\">Hallo</string>\n" +
                "    <plurals name=\"files\">\n        <item quantity=\"one\">Eine Datei</item>\n        <item quantity=\"other\">%d Dateien</item>\n    </plurals>\n" +
                "</resources>\n",
            text("values-de"),
        )
        controller.setValue(files, de, PluralsValue(mapOf(PluralCategory.OTHER to "%d Dateien")))
        assertFalse(text("values-de").contains("Eine Datei"))
    }

    fun `test arrays and new keys`() {
        controller.addKey("days", ArrayValue(listOf("Mon", "Tue")))
        controller.setValue(ResourceKey(ResourceKind.ARRAY, "days"), de, ArrayValue(listOf("Mo", "Di")))
        assertEquals(ArrayValue(listOf("Mo", "Di")), controller.read().translation(ResourceKey(ResourceKind.ARRAY, "days"), de)!!.value)
    }

    fun `test rename and delete reach every locale`() {
        controller.renameKey(hello, "greeting")
        val greeting = hello.copy(name = "greeting")
        val set = controller.read()
        assertNull(set.source(hello))
        assertEquals(TextValue("Hallo"), set.translation(greeting, de)!!.value)

        controller.deleteKey(greeting)
        assertFalse(text("values").contains("greeting"))
        assertFalse(text("values-de").contains("greeting"))
    }

    fun `test add locale creates the folder and file`() {
        val file = controller.addLocale(Locale("pt", region = "BR"))
        assertEquals("values-pt-rBR", file.parent.name)
        assertEquals(listOf(de, Locale("pt", region = "BR")), controller.read().locales)
        controller.setValue(hello, Locale("pt", region = "BR"), TextValue("Olá"))
        assertTrue(text("values-pt-rBR").contains("<string name=\"hello\">Olá</string>"))
    }

    fun `test several locales are added at once`() {
        val files = controller.addLocales(listOf(Locale("fr"), Locale("pt", region = "BR"), Locale("sr", script = "Latn"), Locale("fr")))
        assertEquals(listOf("values-fr", "values-pt-rBR", "values-b+sr+Latn"), files.map { it.parent.name })
        assertEquals(listOf(de, Locale("fr"), Locale("pt", region = "BR"), Locale("sr", script = "Latn")), controller.read().locales)
        val error = runCatching { controller.addLocales(listOf(Locale("it"), de)) }.exceptionOrNull()
        assertEquals("de already exists", error?.message)
        // Refused before anything was written: no half-added batch.
        assertFalse(Locale("it") in controller.read().locales)
    }

    fun `test person edits don't create a lock file, machine translations do and are tracked`() {
        controller.setValue(hello, de, TextValue("Servus"))
        assertNull(LockFiles.find(root))

        controller.applyTranslations(de, mapOf(ResourceKey(ResourceKind.STRING, "bye") to TextValue("Tschüss")), "deepl")
        val lockFile = assertNotNull(LockFiles.find(root))
        assertEquals("translations.lock.json", lockFile.name)
        assertEquals(root.parent, lockFile.parent)

        val lock = controller.lock()
        val bye = ResourceKey(ResourceKind.STRING, "bye")
        assertEquals(Origin.Machine("deepl"), lock.status(controller.read(), bye, de).origin)
        assertEquals(TranslationState.UP_TO_DATE, lock.status(controller.read(), hello, de).state)

        // Changing the source makes the machine translation pending again; the person's one only needs review.
        controller.setValue(bye, null, TextValue("Goodbye"))
        controller.setValue(hello, null, TextValue("Hello there"))
        val after = controller.lock()
        // files has no German translation at all, so it is pending too.
        assertEquals(setOf(files, bye), after.pending(controller.read(), de).toSet())
        assertTrue(after.status(controller.read(), hello, de).needsReview)
    }

    fun `test renaming keeps the lock record`() {
        val bye = ResourceKey(ResourceKind.STRING, "bye")
        controller.applyTranslations(de, mapOf(bye to TextValue("Tschüss")), "apple")
        controller.renameKey(bye, "farewell")
        val lock = TranslationLock.fromJson(String(LockFiles.find(root)!!.let { FileDocumentManager.getInstance().getDocument(it)!!.text }.toByteArray()))
        assertNotNull(lock.record(bye.copy(name = "farewell"), de))
        assertNull(lock.record(bye, de))
    }

    fun `test import a CSV and an Android strings xml`() {
        val csv = ExchangeActions.exportFiles(controller.read(), ExchangeActions.Format.CSV).getValue("translations.csv")
            .replace("bye,Bye,", "bye,Bye,Tschüss")
        val csvFile = myFixture.addFileToProject("exchange/translations.csv", csv).virtualFile
        val (written, skipped) = ExchangeActions.importFile(controller, csvFile) { null }
        assertEquals(mapOf(de to 2), written) // hello (unchanged) and bye
        assertEquals(TextValue("Tschüss"), controller.read().translation(ResourceKey(ResourceKind.STRING, "bye"), de)!!.value)
        assertTrue(skipped.isEmpty())

        val android = myFixture.addFileToProject(
            "android/res/values-fr/strings.xml",
            "<resources><string name=\"hello\">Bonjour</string><string name=\"legacy\">Old</string></resources>",
        ).virtualFile
        val (fr, notFound) = ExchangeActions.importFile(controller, android) { null }
        assertEquals(mapOf(Locale("fr") to 1), fr)
        assertEquals(listOf("legacy"), notFound)
        // The locale didn't exist: importing created values-fr.
        assertEquals(TextValue("Bonjour"), controller.read().translation(hello, Locale("fr"))!!.value)
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        assertTrue(value != null)
        return value!!
    }
}
