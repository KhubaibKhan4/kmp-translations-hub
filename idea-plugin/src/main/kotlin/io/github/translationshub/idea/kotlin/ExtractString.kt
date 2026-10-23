package io.github.translationshub.idea.kotlin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScopes
import com.intellij.psi.util.PsiTreeUtil
import io.github.translationshub.core.ResourceFormat
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.idea.ResourceFolders
import io.github.translationshub.idea.ResourceRoots
import io.github.translationshub.idea.TranslationsController
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Turns `Text("Hello, $name")` into `Text(stringResource(Res.string.hello_name, name))`: the text goes into the
 * module's default `strings.xml` (`Hello, %1$s`), the literal is replaced, and the imports are added, as one Undo.
 * A module with no `composeResources` but an Android `res` folder gets `stringResource(R.string.hello_name, name)`
 * instead. Works on the PSI only (no analysis), so it is the same in K1 and K2 mode.
 */
object ExtractString {

    /** A Kotlin string literal that's worth extracting from Compose UI code, and where it would go. */
    class Candidate(val literal: KtStringTemplateExpression, val root: VirtualFile, val text: String, val arguments: List<String>) {
        val format get() = ResourceFolders.format(root)

        /** "Extract to composeResources", or to the Android strings file. */
        val actionText get() = if (format == ResourceFormat.ANDROID) "Extract to res/values/strings.xml" else "Extract to composeResources"
    }

    fun candidate(element: PsiElement): Candidate? {
        val literal = PsiTreeUtil.getParentOfType(element, KtStringTemplateExpression::class.java, false) ?: return null
        if (literal.entries.isEmpty() || literal.text.startsWith("\"\"\"")) return null
        // Inside a string that is already a resource accessor's argument, or an annotation: leave it.
        if (PsiTreeUtil.getParentOfType(literal, org.jetbrains.kotlin.psi.KtAnnotationEntry::class.java) != null) return null
        if (!isInComposeUi(literal)) return null
        val file = literal.containingFile?.virtualFile ?: return null
        val root = rootFor(literal.project, file) ?: return null
        val (text, arguments) = template(literal, android = ResourceFolders.format(root) == ResourceFormat.ANDROID) ?: return null
        if (text.count(Char::isLetter) < 2) return null
        return Candidate(literal, root, text, arguments)
    }

    /**
     * Writes the string and replaces the literal. [name] defaults to one made from the text, made unique in that
     * `composeResources` folder.
     */
    fun extract(project: Project, candidate: Candidate, name: String = suggestName(candidate)): String {
        val controller = TranslationsController(project, candidate.root)
        val file = candidate.literal.containingKtFile
        WriteCommandAction.writeCommandAction(project, file).withName("Extract string $name").run<RuntimeException> {
            // Nested in this command, so the XML and the Kotlin change are one Undo.
            controller.addKey(name, TextValue(candidate.text))
            val factory = KtPsiFactory(project)
            val android = candidate.format == ResourceFormat.ANDROID
            val call = buildString {
                append(if (android) "stringResource(R.string." else "stringResource(Res.string.").append(name)
                candidate.arguments.forEach { append(", ").append(it) }
                append(')')
            }
            candidate.literal.replace(factory.createExpression(call))
            if (android) {
                addImport(file, "androidx.compose.ui.res.stringResource")
                androidNamespace(project, candidate.root, file)?.takeIf { it != file.packageFqName.asString() }?.let { addImport(file, "$it.R") }
            } else {
                addImport(file, "org.jetbrains.compose.resources.stringResource")
                resourcesPackage(project, file)?.let { pkg ->
                    addImport(file, "$pkg.Res")
                    addImport(file, "$pkg.$name")
                }
            }
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(
                PsiDocumentManager.getInstance(project).getDocument(file) ?: return@run,
            )
        }
        return name
    }

    /** `hello_name` from "Hello, $name", unique among the folder's string keys. */
    fun suggestName(candidate: Candidate): String {
        val words = candidate.text.replace(Regex("%\\d+\\$[sd]"), " ").lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' || c in '0'..'9' } }.take(4)
        val base = words.joinToString("_").ifEmpty { "text" }.let { if (it.first().isDigit()) "text_$it" else it }
        val taken = ResourceFolders.read(candidate.root).keys.filter { it.kind == ResourceKind.STRING }.map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in taken }
    }

    fun isTaken(candidate: Candidate, name: String) =
        ResourceFolders.read(candidate.root).source(ResourceKey(ResourceKind.STRING, name)) != null

    /**
     * Compose UI code: inside a `@Composable` function or property getter, or a lambda passed to a call whose
     * name starts with a capital letter (`Column { … }`, `Button(onClick) { … }`), the Compose naming convention.
     */
    fun isInComposeUi(element: PsiElement): Boolean {
        var current: PsiElement? = element.parent
        while (current != null && current !is KtFile) {
            when (current) {
                is KtNamedFunction, is KtPropertyAccessor -> return (current as KtAnnotated).annotationEntries.any { it.shortName?.asString() == "Composable" }
                is KtLambdaExpression -> {
                    val call = PsiTreeUtil.getParentOfType(current, KtCallExpression::class.java)
                    if (call != null && call.calleeExpression?.text?.firstOrNull()?.isUpperCase() == true) return true
                }
            }
            current = current.parent
        }
        return false
    }

    /** Whether the literal is passed straight to a Compose call (`Text("…")`, `Button(…, label = "…")`). */
    fun isComposeCallArgument(literal: KtStringTemplateExpression): Boolean {
        val argument = literal.parent as? KtValueArgument ?: return false
        val call = PsiTreeUtil.getParentOfType(argument, KtCallExpression::class.java) ?: return false
        val callee = call.calleeExpression?.text ?: return false
        if (!callee.first().isUpperCase()) return false
        val parameter = argument.getArgumentName()?.asName?.asString()
        return parameter == null || parameter in UI_TEXT_PARAMETERS
    }

    private val UI_TEXT_PARAMETERS = setOf("text", "label", "title", "contentDescription", "placeholder", "message", "subtitle", "description", "hint", "supportingText", "confirmText", "dismissText")

    /**
     * Template → resource text and arguments: `"Hi $name, ${n + 1} left"` → `Hi %1$s, %2$s left`, `[name, n + 1]`.
     * [android]: Android formats the whole string when it has arguments, so a literal `%` there becomes `%%`.
     */
    private fun template(literal: KtStringTemplateExpression, android: Boolean): Pair<String, List<String>>? {
        val text = StringBuilder()
        val arguments = mutableListOf<String>()
        val formatted = android && literal.entries.any { it is KtSimpleNameStringTemplateEntry || it is KtBlockStringTemplateEntry }
        for (entry in literal.entries) when (entry) {
            // Compose only formats %1$s / %1$d, so a literal % stays as it is.
            is KtLiteralStringTemplateEntry -> text.append(if (formatted) entry.text.replace("%", "%%") else entry.text)
            // Kept as written: \n, \t, \" and \' mean the same in strings.xml; \$ becomes a plain dollar.
            is KtEscapeStringTemplateEntry -> text.append(if (entry.text == "\\$") "$" else entry.text)
            is KtSimpleNameStringTemplateEntry, is KtBlockStringTemplateEntry -> {
                val expression = entry.expression ?: return null
                arguments += expression.text
                text.append('%').append(arguments.size).append("\$s")
            }
            else -> return null
        }
        return text.toString() to arguments
    }

    /**
     * Where [file]'s strings go: its module's `composeResources` folder, else its module's Android `res` folder. A KMP
     * module keeps using `Res` from `androidMain` too; an Android-only module gets `R`.
     */
    private fun rootFor(project: Project, file: VirtualFile): VirtualFile? =
        sequenceOf(ResourceFormat.COMPOSE, ResourceFormat.ANDROID).firstNotNullOfOrNull { format ->
            ResourceRoots.nearest(project, file, format).firstOrNull { root ->
                // composeResources and res both sit at module/src/<sourceSet>/<root>.
                val module = root.parent?.parent?.parent ?: return@firstOrNull false
                VfsUtilCore.isAncestor(module, file, true) && (format == ResourceFormat.COMPOSE || root.findFileByRelativePath("values") != null)
            }
        }

    private val rImport = Regex("""^import\s+([\w.]+)\.R\s*$""", RegexOption.MULTILINE)
    private val namespace = Regex("""\bnamespace\s*=?\s*["']([\w.]+)["']""")
    private val manifestPackage = Regex("""<manifest\b[^>]*\bpackage\s*=\s*"([\w.]+)"""")

    /**
     * The package of the module's `R` class: from this file's import of it, the module's `namespace` in
     * build.gradle(.kts), the manifest's `package`, or another Kotlin file's import; null if none says.
     */
    private fun androidNamespace(project: Project, root: VirtualFile, file: KtFile): String? {
        rImport.find(file.text)?.let { return it.groupValues[1] }
        val sourceSet = root.parent ?: return null
        val module = sourceSet.parent?.parent ?: return null
        val documents = FileDocumentManager.getInstance()
        fun read(f: VirtualFile?) = f?.let { documents.getCachedDocument(it)?.text ?: runCatching { String(it.contentsToByteArray(), it.charset) }.getOrNull() }
        for (build in listOf("build.gradle.kts", "build.gradle")) read(module.findChild(build))?.let { namespace.find(it) }?.let { return it.groupValues[1] }
        read(sourceSet.findChild("AndroidManifest.xml"))?.let { manifestPackage.find(it) }?.let { return it.groupValues[1] }
        for (other in FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScopes.directoryScope(project, module, true))) {
            read(other)?.let { rImport.find(it) }?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * The package of the generated `Res` class: from this file's imports, else another Kotlin file of the module,
     * else null (Compose's default depends on the Gradle group, which the plugin can't read reliably).
     */
    private fun resourcesPackage(project: Project, file: KtFile): String? {
        fromImports(file.text)?.let { return it }
        val module = file.virtualFile?.let { rootFor(project, it) }?.parent?.parent?.parent ?: return null
        val documents = FileDocumentManager.getInstance()
        for (other in FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScopes.directoryScope(project, module, true))) {
            val text = documents.getCachedDocument(other)?.text ?: runCatching { String(other.contentsToByteArray(), other.charset) }.getOrNull() ?: continue
            fromImports(text)?.let { return it }
        }
        return null
    }

    private val resourcesImport = Regex("""^import\s+([\w.]+\.generated\.resources)\.\w+""", RegexOption.MULTILINE)

    private fun fromImports(text: String) = resourcesImport.find(text)?.groupValues?.get(1)

    private fun addImport(file: KtFile, fqName: String) {
        val imports = file.importList ?: return
        val fq = FqName(fqName)
        if (imports.imports.any { it.importedFqName == fq || it.importedFqName == fq.parent() && it.isAllUnder }) return
        imports.add(KtPsiFactory(file.project).createImportDirective(ImportPath(fq, false)))
    }
}
