package io.github.translationshub.core

import java.io.File

/**
 * Finds every `composeResources` folder under a project directory and reads its strings. The IDE plugin finds the
 * folders through the project model instead; this is for tests and a future command-line check.
 */
object ResourceScanner {

    private val skippedDirs = setOf("build", ".gradle", ".git", ".idea", ".kotlin", "node_modules", ".intellijPlatform")

    fun scan(projectDir: File): List<TranslationSet> =
        findResourceRoots(projectDir).map(::read).filter { it.sourceFiles.isNotEmpty() || it.locales.isNotEmpty() }

    fun findResourceRoots(projectDir: File): List<File> = projectDir.walkTopDown()
        .onEnter { it.name !in skippedDirs }
        .filter { it.isDirectory && it.name == "composeResources" }
        .sortedBy { it.path }
        .toList()

    fun read(resourceRoot: File): TranslationSet {
        val files = resourceRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .flatMap { dir ->
                val qualifiers = FolderQualifiers.parse(dir.name) ?: return@flatMap emptyList()
                dir.listFiles { f -> f.isFile && f.extension == "xml" }.orEmpty().sortedBy { it.name }.map { file ->
                    StringsFile(file.path, qualifiers, file.reader(Charsets.UTF_8).use(StringsXmlParser::parse))
                }
            }
        return TranslationSet(resourceRoot.path, files)
    }

    /** Where the lock file of a `composeResources` folder lives: beside it, outside what the resource compiler reads. */
    fun lockFile(resourceRoot: File): File = File(resourceRoot.parentFile, TranslationLock.FILE_NAME)
}
