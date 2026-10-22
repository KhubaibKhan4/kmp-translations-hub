package io.github.translationshub.idea

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.translationshub.core.Locale
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceKind
import io.github.translationshub.core.TextValue
import io.github.translationshub.idea.kotlin.HardcodedComposeTextInspection
import io.github.translationshub.idea.translate.TranslateDialog
import io.github.translationshub.idea.translate.TranslationSettingsConfigurable
import java.awt.Component
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Renders the plugin's real panels for the README, the Marketplace screenshots and the banner:
 * `./gradlew :idea-plugin:test --tests '*ListingRender*'` → `idea-plugin/build/listing/`, then
 * `python3 tools/listing/make_listing.py` composes them. A fictional shop app, so no real project's strings.
 */
class ListingRenderTest : BasePlatformTestCase() {

    companion object {
        init {
            // Light tests use a placeholder icon manager that paints nothing, and the platform's icon fields are
            // created when the test application starts: switch to the real icons before that. Run this test on its
            // own (--tests '*ListingRender*') so no earlier test has started the application.
            com.intellij.ui.IconManager.activate(null)
        }
    }

    private val out = File(System.getProperty("user.dir"), "build/listing").apply { mkdirs() }

    private fun render(component: JComponent, width: Int, height: Int, name: String) {
        component.setSize(width, height)
        repeat(3) {
            com.intellij.util.ui.UIUtil.uiTraverser(component).forEach { (it as? Component)?.doLayout() }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        val image = java.awt.image.BufferedImage(width * 2, height * 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(2.0, 2.0)
        component.printAll(g)
        g.dispose()
        ImageIO.write(image, "png", File(out, "$name.png"))
    }

    private val strings = "shop/src/commonMain/composeResources"

    private fun add(folder: String, body: String): VirtualFile = myFixture.addFileToProject(
        "$strings/$folder/strings.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body</resources>\n",
    ).virtualFile

    /** The demo app: the default values, seven locales at different stages, and Kotlin code that uses the keys. */
    private fun project(): VirtualFile {
        val source = add("values", """
            |    <!-- Tab bar -->
            |    <string name="tab_home">Home</string>
            |    <string name="tab_orders">Orders</string>
            |    <string name="tab_settings">Settings</string>
            |    <string name="app_name" translatable="false">Shopwise</string>
            |    <!-- Shown above the cart total -->
            |    <string name="cart_title">Your cart</string>
            |    <plurals name="cart_items">
            |        <item quantity="one">%d item</item>
            |        <item quantity="other">%d items</item>
            |    </plurals>
            |    <string name="checkout_total">Total: %1${'$'}s</string>
            |    <string name="checkout_pay">Pay %1${'$'}s with %2${'$'}s</string>
            |    <string name="order_shipped">Your order shipped on %1${'$'}s</string>
            |    <string name="offline_title">You\'re offline</string>
            |    <string name="offline_body">Check your connection and try again.</string>
            |    <string-array name="delivery_days">
            |        <item>Today</item>
            |        <item>Tomorrow</item>
            |        <item>This week</item>
            |    </string-array>
            |    <string name="sign_out">Sign out</string>
            |""".trimMargin() + "\n")
        add("values-de", """
            |    <string name="tab_home">Start</string>
            |    <string name="tab_orders">Bestellungen</string>
            |    <string name="tab_settings">Einstellungen</string>
            |    <string name="cart_title">Dein Warenkorb</string>
            |    <plurals name="cart_items">
            |        <item quantity="one">%d Artikel</item>
            |        <item quantity="other">%d Artikel</item>
            |    </plurals>
            |    <string name="checkout_total">Gesamt: %1${'$'}d</string>
            |    <string name="checkout_pay">%1${'$'}s mit %2${'$'}s bezahlen</string>
            |    <string name="offline_title">Du bist offline</string>
            |    <string name="offline_body">Check your connection and try again.</string>
            |    <string name="sign_out">Abmelden</string>
            |""".trimMargin() + "\n")
        add("values-fr", """
            |    <string name="tab_home">Accueil</string>
            |    <string name="tab_orders">Commandes</string>
            |    <string name="tab_settings">Réglages</string>
            |    <string name="cart_title">Votre panier</string>
            |    <plurals name="cart_items">
            |        <item quantity="one">%d article</item>
            |        <item quantity="many">%d d’articles</item>
            |        <item quantity="other">%d articles</item>
            |    </plurals>
            |    <string name="checkout_total">Total : %1${'$'}s</string>
            |    <string name="checkout_pay">Payer %1${'$'}s avec %2${'$'}s</string>
            |    <string name="order_shipped">Votre commande a été expédiée le %1${'$'}s</string>
            |    <string name="offline_title">Vous êtes hors ligne</string>
            |    <string name="offline_body">Vérifiez votre connexion et réessayez.</string>
            |    <string-array name="delivery_days">
            |        <item>Aujourd’hui</item>
            |        <item>Demain</item>
            |        <item>Cette semaine</item>
            |    </string-array>
            |    <string name="sign_out">Se déconnecter</string>
            |""".trimMargin() + "\n")
        add("values-ar", """
            |    <string name="tab_home">الرئيسية</string>
            |    <string name="tab_orders">الطلبات</string>
            |    <string name="tab_settings">الإعدادات</string>
            |    <string name="cart_title">سلة التسوق</string>
            |    <string name="checkout_total">الإجمالي: %1${'$'}s</string>
            |    <string name="offline_title">أنت غير متصل</string>
            |    <string name="sign_out">تسجيل الخروج</string>
            |""".trimMargin() + "\n")
        add("values-ja", """
            |    <string name="tab_home">ホーム</string>
            |    <string name="tab_orders">注文</string>
            |    <string name="tab_settings">設定</string>
            |    <string name="cart_title">カート</string>
            |    <plurals name="cart_items">
            |        <item quantity="other">%d 個の商品</item>
            |    </plurals>
            |    <string name="checkout_total">合計：%1${'$'}s</string>
            |    <string name="sign_out">ログアウト</string>
            |""".trimMargin() + "\n")
        add("values-pl", """
            |    <string name="tab_home">Start</string>
            |    <string name="tab_orders">Zamówienia</string>
            |    <string name="tab_settings">Ustawienia</string>
            |    <string name="cart_title">Twój koszyk</string>
            |    <plurals name="cart_items">
            |        <item quantity="one">%d produkt</item>
            |        <item quantity="few">%d produkty</item>
            |        <item quantity="many">%d produktów</item>
            |        <item quantity="other">%d produktu</item>
            |    </plurals>
            |    <string name="checkout_total">Razem: %1${'$'}s</string>
            |    <string name="sign_out">Wyloguj się</string>
            |""".trimMargin() + "\n")
        add("values-ur", """
            |    <string name="tab_home">ہوم</string>
            |    <string name="tab_orders">آرڈرز</string>
            |    <string name="tab_settings">ترتیبات</string>
            |    <string name="cart_title">آپ کی ٹوکری</string>
            |    <string name="sign_out">سائن آؤٹ</string>
            |""".trimMargin() + "\n")
        add("values-es", "    <string name=\"tab_home\">Inicio</string>\n    <string name=\"tab_orders\">Pedidos</string>\n")
        myFixture.addFileToProject("shop/src/commonMain/kotlin/shop/ui/Cart.kt", """
            |package shop.ui
            |
            |import shopwise.shop.generated.resources.Res
            |
            |@Composable
            |fun CartScreen(total: String, count: Int) {
            |    Column {
            |        Text(stringResource(Res.string.cart_title))
            |        Text(pluralStringResource(Res.plurals.cart_items, count, count))
            |        Text(stringResource(Res.string.checkout_total, total))
            |        Text(stringResource(Res.string.checkout_pay, total, "Visa"))
            |        Text(stringResource(Res.string.order_shipped, "Oct 21"))
            |        Text(stringResource(Res.string.offline_title))
            |        Text(stringResource(Res.string.offline_body))
            |        Text(stringResource(Res.string.tab_home) + stringResource(Res.string.tab_orders) + stringResource(Res.string.tab_settings))
            |        Text(stringArrayResource(Res.array.delivery_days).first() + stringResource(Res.string.sign_out))
            |    }
            |}
            |""".trimMargin())
        // Stand-ins for Compose and the generated Res accessors, so the code in the renders isn't red.
        myFixture.addFileToProject("shop/src/commonMain/kotlin/stubs/Compose.kt", """
            |package androidx.compose.runtime
            |annotation class Composable
            |""".trimMargin())
        myFixture.addFileToProject("shop/src/commonMain/kotlin/stubs/Ui.kt", """
            |package shop.ui
            |fun Text(text: String) {}
            |fun Column(content: () -> Unit) {}
            |fun Button(onClick: () -> Unit, content: () -> Unit) {}
            |""".trimMargin())
        myFixture.addFileToProject("shop/src/commonMain/kotlin/stubs/Resources.kt", """
            |package org.jetbrains.compose.resources
            |class StringResource
            |fun stringResource(resource: StringResource, vararg args: Any): String = ""
            |""".trimMargin())
        myFixture.addFileToProject("shop/src/commonMain/kotlin/stubs/Res.kt", """
            |package shopwise.shop.generated.resources
            |import org.jetbrains.compose.resources.StringResource
            |object Res { object string }
            |val Res.string.nothing_here_yet: StringResource get() = StringResource()
            |val Res.string.start_shopping: StringResource get() = StringResource()
            |""".trimMargin())
        // A machine translation whose default text changed since: shows as outdated.
        val root = source.parent.parent
        val controller = TranslationsController(project, root)
        val set = controller.read()
        controller.applyTranslations(Locale("fr"), mapOf(ResourceKey(ResourceKind.STRING, "order_shipped") to set.translation(ResourceKey(ResourceKind.STRING, "order_shipped"), Locale("fr"))!!.value), "deepl")
        controller.setValue(ResourceKey(ResourceKind.STRING, "order_shipped"), null, TextValue("Your order shipped on %1\$s. Track it in Orders."))
        return source
    }

    private fun editor(source: VirtualFile) =
        (TranslationsEditorProvider().createEditor(project, source) as TranslationsEditor).also { Disposer.register(testRootDisposable, it) }

    private fun key(name: String, kind: ResourceKind = ResourceKind.STRING) = ResourceKey(kind, name)

    /** What a shown window does on its own: the table's column header, the toolbar's buttons, scrolled to the start. */
    private fun showLikeAWindow(editor: TranslationsEditor) {
        // Off screen the platform's header renderer paints black boxes; a plain one in the same colors stands in.
        editor.table.tableHeader.defaultRenderer = javax.swing.table.TableCellRenderer { _, value, _, _, _, column ->
            com.intellij.ui.components.JBLabel(value?.toString() ?: "").apply {
                font = com.intellij.util.ui.JBUI.Fonts.label().asBold()
                border = com.intellij.util.ui.JBUI.Borders.compound(
                    com.intellij.util.ui.JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 0, if (column == 0) 0 else 1, 1, 0),
                    com.intellij.util.ui.JBUI.Borders.empty(5, 8),
                )
                isOpaque = true
                background = com.intellij.util.ui.UIUtil.getPanelBackground()
            }
        }
        (editor.table.parent?.parent as? javax.swing.JScrollPane)?.apply {
            setColumnHeaderView(editor.table.tableHeader)
            viewport.viewPosition = java.awt.Point(0, 0)
        }
        editor.toolbar.updateActionsImmediately()
    }

    private fun renderAll(source: VirtualFile, suffix: String) {
        val editor = editor(source)
        editor.reload()
        val shown = { showLikeAWindow(editor) }
        editor.select(Cell(key("checkout_total"), Locale("de")))
        shown()
        render(editor.component, 1600, 480, "table$suffix")

        editor.select(Cell(key("cart_items", ResourceKind.PLURALS), Locale("pl")))
        shown()
        render(editor.component, 1600, 480, "plurals$suffix")

        editor.filter.item = TranslationsEditor.Filter.MISSING
        editor.select(Cell(key("order_shipped"), Locale("de")))
        shown()
        render(editor.component, 1600, 480, "missing$suffix")
        editor.filter.item = TranslationsEditor.Filter.ALL

        val dialog = TranslateDialog(project, editor.controller)
        Disposer.register(testRootDisposable, dialog.disposable)
        render(dialog.form().apply { border = com.intellij.util.ui.JBUI.Borders.empty(16) }, 720, 350, "translate-dialog$suffix")

        render(TranslationSettingsConfigurable().createComponent(), 880, 560, "settings$suffix")

        val addLocales = AddLocalesDialog(project, editor.model.set.locales)
        Disposer.register(testRootDisposable, addLocales.disposable)
        addLocales.tick(LocaleChooser.TOP)
        addLocales.setOtherTags("sr-Latn, es-MX")
        render(addLocales.form().apply { border = com.intellij.util.ui.JBUI.Borders.empty(16) }, 520, 520, "add-locales$suffix")

        // The XML warnings in a locale file.
        myFixture.enableInspections(TranslationsInspection())
        myFixture.configureFromExistingVirtualFile(source.parent.parent.findChild("values-de")!!.findChild("strings.xml")!!)
        myFixture.doHighlighting()
        (myFixture.editor as EditorEx).apply { settings.isLineNumbersShown = true }
        render(myFixture.editor.component, 1100, 370, "inspection$suffix")

        // Extraction, before and after.
        myFixture.enableInspections(HardcodedComposeTextInspection())
        val code = "package shop.ui\n\nimport androidx.compose.runtime.Composable\nimport shopwise.shop.generated.resources.Res\n\n@Composable\nfun Empty(name: String) {\n    Column {\n        Text(\"Nothing here yet, \$name\")\n        Button(onClick = {}) { Text(\"Start shopping\") }\n    }\n}\n"
        val kt = myFixture.addFileToProject("shop/src/commonMain/kotlin/shop/ui/Empty$suffix.kt", code)
        myFixture.configureFromExistingVirtualFile(kt.virtualFile)
        myFixture.doHighlighting()
        render(myFixture.editor.component, 1000, 280, "extract-before$suffix")
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Nothing") + 1)
        myFixture.launchAction(myFixture.findSingleIntention("Extract to composeResources"))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Start shopping") + 1)
        myFixture.launchAction(myFixture.findSingleIntention("Extract to composeResources"))
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.editor.scrollingModel.scrollVertically(0)
        render(myFixture.editor.component, 1000, 340, "extract-after$suffix")
    }

    fun `test render listing panels`() {
        // Marketing images only; not worth CI time or memory.
        if (System.getenv("CI") != null) return
        val icon = com.intellij.openapi.util.IconLoader.getIcon("/META-INF/pluginIcon.svg", TranslationsEditor::class.java.classLoader)
        val big = com.intellij.util.IconUtil.scale(icon, null, 512f / icon.iconWidth)
        val iconImage = java.awt.image.BufferedImage(big.iconWidth, big.iconHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        iconImage.createGraphics().also { big.paintIcon(null, it, 0, 0); it.dispose() }
        ImageIO.write(iconImage, "png", File(out, "plugin-icon.png"))

        val source = project()
        renderAll(source, "")
        val laf = LafManager.getInstance()
        val dark = laf.installedThemes.firstOrNull { it.isDark }
        if (dark != null) {
            laf.setCurrentLookAndFeel(dark, false)
            laf.updateUI()
            // Undo the extraction so the dark pass starts from the same files.
            renderAll(source, "-dark")
        }
    }
}
