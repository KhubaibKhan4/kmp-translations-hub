package io.github.translationshub.idea

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType
import com.intellij.ui.awt.RelativePoint
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceFormat
import io.github.translationshub.core.ResourceKind
import java.awt.event.MouseEvent

/**
 * ⌘/Ctrl+click on `Res.string.hello` in Kotlin goes to `<string name="hello">` in the default values file,
 * instead of the generated accessor in `build/`. Works on the text, so it needs no Kotlin plugin APIs.
 */
class ResourceGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val file = sourceElement?.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (virtualFile.extension != "kt") return null
        val (key, _) = ResourceUsages.keyAt(file.viewProvider.contents, offset) ?: return null
        return ResourceDeclarations.find(file.project, key, near = virtualFile).toTypedArray<PsiElement>().takeIf { it.isNotEmpty() }
    }
}

object ResourceDeclarations {

    /** The `name="…"` values declaring [key] in the default values files, nearest module first. */
    fun find(project: Project, key: ResourceKey, near: VirtualFile): List<XmlAttributeValue> {
        val psiManager = PsiManager.getInstance(project)
        for (root in ResourceRoots.nearest(project, near)) {
            val set = ResourceFolders.read(root)
            val path = set.sourceFile(key)?.path ?: continue
            val file = root.fileSystem.findFileByPath(path)?.let(psiManager::findFile) as? XmlFile ?: continue
            StringsXmlWriter.findTag(file, key)?.getAttribute("name")?.valueElement?.let { return listOf(it) }
        }
        return emptyList()
    }
}

/**
 * A gutter icon on each key of a default values file: click it for the Kotlin code that uses the key. The search
 * runs on click, so marking 2,000 keys costs nothing.
 */
class ResourceUsagesLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is XmlToken || element.tokenType != XmlTokenType.XML_NAME) return null
        val tag = element.parent as? XmlTag ?: return null
        if (tag.parentTag?.name != "resources" || element != tag.firstChild?.nextSibling) return null
        val kind = ResourceKind.fromXml(tag.name) ?: return null
        val name = tag.getAttributeValue("name") ?: return null
        val file = tag.containingFile.virtualFile ?: return null
        val root = ResourceFolders.rootOf(file) ?: return null
        if (file.parent.name != "values") return null
        val key = ResourceKey(kind, name)
        val format = ResourceFolders.format(root)
        return LineMarkerInfo(
            element, element.textRange, AllIcons.Gutter.ImplementedMethod, { "Find uses of ${accessor(format, key)}" },
            UsagesNavigation(key, root, format), GutterIconRenderer.Alignment.LEFT, { "Uses of $name" },
        )
    }

    private class UsagesNavigation(private val key: ResourceKey, private val root: VirtualFile, private val format: ResourceFormat) :
        GutterIconNavigationHandler<PsiElement> {
        override fun navigate(event: MouseEvent, element: PsiElement) {
            val project = element.project
            val uses = ResourceUsages.find(project, key, format)
            val point = RelativePoint(event)
            when (uses.size) {
                0 -> JBPopupFactory.getInstance().createMessage("Nothing uses ${accessor(format, key)}").show(point)
                1 -> open(project, uses.single())
                else -> JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<Pair<VirtualFile, Int>>("Uses of ${key.name}", uses) {
                    override fun getTextFor(value: Pair<VirtualFile, Int>): String {
                        val document = PsiManager.getInstance(project).findFile(value.first)?.let(PsiDocumentManager.getInstance(project)::getDocument)
                        val line = document?.getLineNumber(value.second)?.plus(1)
                        val where = VfsUtilCore.getRelativePath(value.first, root.parent?.parent?.parent ?: value.first.parent) ?: value.first.name
                        return "$where${line?.let { ":$it" } ?: ""}"
                    }
                    override fun onChosen(selectedValue: Pair<VirtualFile, Int>, finalChoice: Boolean): PopupStep<*>? {
                        open(project, selectedValue)
                        return FINAL_CHOICE
                    }
                }).show(point)
            }
        }

        private fun open(project: Project, use: Pair<VirtualFile, Int>) = OpenFileDescriptor(project, use.first, use.second).navigate(true)
    }
}

/** How code refers to [key]: `Res.string.hello`, or `R.string.hello` in an Android `res` folder. */
internal fun accessor(format: ResourceFormat, key: ResourceKey) =
    "${if (format == ResourceFormat.ANDROID) "R" else "Res"}.${accessorKind(key.kind)}.${key.name}"

internal fun accessorKind(kind: ResourceKind) = when (kind) {
    ResourceKind.STRING -> "string"
    ResourceKind.PLURALS -> "plurals"
    ResourceKind.ARRAY -> "array"
}
