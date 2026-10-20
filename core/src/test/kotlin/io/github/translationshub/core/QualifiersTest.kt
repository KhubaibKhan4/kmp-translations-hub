package io.github.translationshub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QualifiersTest {

    @Test
    fun `default folder has no locale`() {
        assertEquals(FolderQualifiers(null), FolderQualifiers.parse("values"))
    }

    @Test
    fun `language, region and BCP 47 folders`() {
        assertEquals(Locale("ur"), FolderQualifiers.parse("values-ur")!!.locale)
        assertEquals(Locale("pt", region = "BR"), FolderQualifiers.parse("values-pt-rBR")!!.locale)
        assertEquals(Locale("sr", script = "Latn"), FolderQualifiers.parse("values-b+sr+Latn")!!.locale)
        assertEquals(Locale("es", region = "419"), FolderQualifiers.parse("values-b+es+419")!!.locale)
    }

    @Test
    fun `other qualifiers are kept apart from the locale`() {
        assertEquals(FolderQualifiers(Locale("ur"), listOf("dark")), FolderQualifiers.parse("values-ur-dark"))
        assertEquals(FolderQualifiers(null, listOf("night")), FolderQualifiers.parse("values-night"))
        assertEquals(FolderQualifiers(null, listOf("xhdpi")), FolderQualifiers.parse("values-xhdpi"))
    }

    @Test
    fun `folder names and tags round-trip`() {
        for (name in listOf("values", "values-ur", "values-pt-rBR", "values-b+sr+Latn", "values-ar-dark")) {
            assertEquals(name, FolderQualifiers.parse(name)!!.folderName)
        }
        assertEquals("pt-BR", Locale("pt", region = "BR").tag)
        assertEquals(Locale("sr", "Latn", "RS"), Locale.fromTag("sr-Latn-RS"))
    }

    @Test
    fun `non-values folders are ignored`() {
        assertNull(FolderQualifiers.parse("drawable"))
        assertNull(FolderQualifiers.parse("values-"))
    }
}
