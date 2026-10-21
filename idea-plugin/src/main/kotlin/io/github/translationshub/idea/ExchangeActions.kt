package io.github.translationshub.idea

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.translationshub.core.Exchange
import io.github.translationshub.core.FolderQualifiers
import io.github.translationshub.core.Locale
import io.github.translationshub.core.TranslationSet

/** Export and Import in the Translations tab: CSV, XLIFF 1.2 and Android `strings.xml`. */
object ExchangeActions {

    enum class Format(val label: String) {
        CSV("CSV, all locales in one file"),
        XLIFF("XLIFF 1.2, one file per locale"),
        STRINGS_XML("strings.xml, one values-xx folder per locale"),
    }

    /** File name (relative path) → content. */
    fun exportFiles(set: TranslationSet, format: Format, sourceLanguage: String = "en"): Map<String, String> = when (format) {
        Format.CSV -> mapOf("translations.csv" to Exchange.toCsv(set))
        Format.XLIFF -> set.locales.associate { "${it.tag}.xlf" to Exchange.toXliff(set, it, sourceLanguage) }
        Format.STRINGS_XML -> set.locales.associate { "${FolderQualifiers(it).folderName}/strings.xml" to Exchange.toStringsXml(set, it) }
    }

    /**
     * Imports one file; the format comes from its extension. A `strings.xml` takes its locale from its folder
     * (`values-de`), else [askLocale]. Returns locale → number of keys written, and the ids that matched no key.
     */
    fun importFile(
        controller: TranslationsController,
        file: VirtualFile,
        askLocale: () -> Locale?,
    ): Pair<Map<Locale, Int>, List<String>> {
        val text = VfsUtil.loadText(file)
        val set = controller.read()
        val byLocale: Map<Locale, Exchange.Imported> = when (file.extension?.lowercase()) {
            "csv" -> Exchange.fromCsv(text, set)
            "xlf", "xliff" -> {
                val (found, imported) = Exchange.fromXliff(text, set)
                val locale = found ?: askLocale() ?: return emptyMap<Locale, Int>() to emptyList()
                if (found == null) mapOf(locale to Exchange.fromXliff(text, set, locale).second) else mapOf(locale to imported)
            }
            "xml" -> {
                val locale = file.parent?.name?.let(FolderQualifiers::parse)?.locale ?: askLocale() ?: return emptyMap<Locale, Int>() to emptyList()
                mapOf(locale to Exchange.fromStringsXml(text, set))
            }
            else -> error("${file.name}: use a .csv, .xlf/.xliff or strings.xml file")
        }
        for ((locale, imported) in byLocale) controller.importValues(locale, imported.values)
        return byLocale.mapValues { it.value.values.size } to byLocale.values.flatMap { it.skipped }.distinct()
    }

    fun export(project: Project, set: TranslationSet) {
        val formats = Format.entries
        val message = "Export ${set.keys.size} keys in ${set.locales.size} locales as:\n\n" + formats.joinToString("\n") { "• ${it.label}" }
        val choice = Messages.showDialog(project, message, "Export Translations",
            arrayOf("CSV", "XLIFF", "strings.xml", "Cancel"), 0, Messages.getQuestionIcon())
        if (choice !in formats.indices) return
        val folder = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Export To"), project, null) ?: return
        val files = exportFiles(set, formats[choice])
        WriteAction.run<RuntimeException> {
            for ((path, content) in files) {
                val parent = path.substringBeforeLast('/', "").let { if (it.isEmpty()) folder else VfsUtil.createDirectoryIfMissing(folder, it) }
                val file = parent.findChild(path.substringAfterLast('/')) ?: parent.createChildData(this, path.substringAfterLast('/'))
                VfsUtil.saveText(file, content)
            }
        }
        Messages.showInfoMessage(project, "Exported ${files.size} ${if (files.size == 1) "file" else "files"} to ${folder.presentableUrl}.", "Export Translations")
    }

    fun import(project: Project, controller: TranslationsController) {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withTitle("Import Translations").withDescription("CSV, XLIFF 1.2 or strings.xml files")
            .withFileFilter { it.extension?.lowercase() in setOf("csv", "xlf", "xliff", "xml") }
        val files = FileChooser.chooseFiles(descriptor, project, null)
        if (files.isEmpty()) return
        val counts = mutableMapOf<Locale, Int>()
        val skipped = mutableListOf<String>()
        for (file in files) {
            val (written, notFound) = importFile(controller, file) { LocaleChooser.choose(project, emptyList()) }
            written.forEach { (locale, n) -> counts.merge(locale, n, Int::plus) }
            skipped += notFound
        }
        val summary = counts.entries.joinToString("\n") { "${it.key.tag}: ${it.value} keys" }.ifEmpty { "Nothing to import." }
        val extra = if (skipped.isEmpty()) "" else "\n\nNot in the default values, skipped: ${skipped.take(10).joinToString()}${if (skipped.size > 10) "…" else ""}"
        Messages.showInfoMessage(project, summary + extra, "Import Translations")
    }
}
