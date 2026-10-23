package io.github.translationshub.idea

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.Checks
import io.github.translationshub.core.Problem
import io.github.translationshub.core.ResourceFormat
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind

/** R.string in an Android module: extraction, usages from Kotlin, Java and XML, the unused-key check. */
class AndroidRStringTest : BasePlatformTestCase() {

    private val strings = "app/src/main/res/values/strings.xml"

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("app/build.gradle.kts", "android {\n    namespace = \"com.example.shop\"\n}\n")
        myFixture.addFileToProject(strings, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" +
            "    <string name=\"title\">Shop</string>\n    <string name=\"cart\">Cart</string>\n    <string name=\"pay\">Pay</string>\n" +
            "    <string name=\"orders\">Orders</string>\n    <string name=\"unused\">Nobody</string>\n</resources>\n")
    }

    private fun xml() = FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(strings))!!.text

    private fun key(name: String) = ResourceKey(ResourceKind.STRING, name)

    fun `test extracting in an android module writes R string, escaped, with the imports`() {
        val file = myFixture.addFileToProject("app/src/main/java/com/example/shop/ui/Sale.kt",
            "package com.example.shop.ui\n\n@Composable\nfun Sale(name: String) {\n    Text(\"Don't miss it, \$name: 50% off\")\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("miss"))
        myFixture.launchAction(myFixture.findSingleIntention("Extract to res/values/strings.xml"))

        // Android formats strings with arguments: the literal % doubles; the apostrophe is escaped.
        assertTrue(xml(), xml().contains("<string name=\"don_t_miss_it\">Don\\'t miss it, %1\$s: 50%% off</string>"))
        val code = myFixture.editor.document.text
        assertTrue(code, code.contains("Text(stringResource(R.string.don_t_miss_it, name))"))
        assertTrue(code, code.contains("import androidx.compose.ui.res.stringResource"))
        assertTrue(code, code.contains("import com.example.shop.R"))
    }

    fun `test no R import in the namespace's own package`() {
        val file = myFixture.addFileToProject("app/src/main/java/com/example/shop/Home.kt",
            "package com.example.shop\n\n@Composable\nfun Home() {\n    Text(\"Welcome home\")\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Welcome"))
        myFixture.launchAction(myFixture.findSingleIntention("Extract to res/values/strings.xml"))
        val code = myFixture.editor.document.text
        assertTrue(code, code.contains("stringResource(R.string.welcome_home)"))
        assertFalse(code, code.contains("import com.example.shop.R"))
    }

    fun `test a KMP module with composeResources keeps using Res from androidMain`() {
        myFixture.addFileToProject("shared/src/commonMain/composeResources/values/strings.xml", "<resources>\n</resources>\n")
        myFixture.addFileToProject("shared/src/androidMain/res/values/strings.xml", "<resources>\n</resources>\n")
        val file = myFixture.addFileToProject("shared/src/androidMain/kotlin/Widget.kt",
            "@Composable\nfun Widget() {\n    Text(\"Daily summary\")\n}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Daily"))
        assertNotNull(myFixture.getAvailableIntention("Extract to composeResources"))
        assertNull(myFixture.getAvailableIntention("Extract to res/values/strings.xml"))
    }

    private fun addUses() {
        myFixture.addFileToProject("app/src/main/java/com/example/shop/Cart.kt",
            "package com.example.shop\nfun cart(c: Context) = c.getString(R.string.cart) + c.getString(android.R.string.ok)\n")
        myFixture.addFileToProject("app/src/main/java/com/example/shop/Pay.java",
            "package com.example.shop;\nclass Pay { int id = R.string.pay; }\n")
        myFixture.addFileToProject("app/src/main/res/layout/main.xml",
            "<TextView xmlns:android=\"http://schemas.android.com/apk/res/android\" android:text=\"@string/title\"/>\n")
        myFixture.addFileToProject("app/src/main/res/menu/main.xml",
            "<menu xmlns:android=\"http://schemas.android.com/apk/res/android\"><item android:title=\"@string/orders\"/></menu>\n")
    }

    fun `test uses are found in Kotlin, Java and XML, not android R`() {
        addUses()
        val find = { name: String -> ResourceUsages.find(project, key(name), ResourceFormat.ANDROID).map { it.first.name } }
        assertEquals(listOf("Cart.kt"), find("cart"))
        assertEquals(listOf("Pay.java"), find("pay"))
        assertEquals(listOf("main.xml"), find("title"))
        assertEmpty(find("unused"))
        assertEquals(setOf("cart", "pay", "title", "orders"), UsedKeys.all(project, ResourceFormat.ANDROID).map { it.name }.toSet())
        // android.R.string.ok is the framework's.
        assertFalse(key("ok") in UsedKeys.all(project, ResourceFormat.ANDROID))
    }

    fun `test the unused-key check flags only the key nothing uses`() {
        addUses()
        val root = myFixture.findFileInTempDir(strings).parent.parent
        val unused = Checks.run(ResourceFolders.read(root), CheckSettings.options(project, root)).filterIsInstance<Problem.Unused>()
        assertEquals(listOf("unused"), unused.map { it.key.name })
    }

    fun `test the gutter icon on an android key finds R string uses`() {
        addUses()
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(strings))
        val markers = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        assertTrue(markers.toString(), "Find uses of R.string.cart" in markers)
    }
}
