package io.github.translationshub.core

import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reads a real Compose Multiplatform project when one is given (`-Ptranslationshub.realProject=…`), and a generated
 * one of 2,000 keys × 15 locales always: every key must be read back, and fast enough to rebuild on each change.
 */
class RealProjectTest {

    @Test
    fun `real project`() {
        val dir = System.getProperty("translationshub.realProject")?.let(::File) ?: return
        lateinit var sets: List<TranslationSet>
        val millis = measureTimeMillis { sets = ResourceScanner.scan(dir) }
        assertTrue(sets.isNotEmpty(), "No composeResources folders under $dir")
        for (set in sets) {
            val problems = Checks.run(set)
            val lock = TranslationLock().apply { adoptUntracked(set) }
            println(
                "${set.root.removePrefix(dir.path)}: ${set.keys.size} keys, locales ${set.locales}, " +
                    "${problems.size} problems ${problems.groupingBy { it::class.simpleName }.eachCount()}, " +
                    "pending after adopt ${set.locales.sumOf { lock.pending(set, it).size }}",
            )
            problems.take(5).forEach { println("    ${it.key} [${it.locale}] ${it.message}") }
        }
        println("Read ${sets.size} folders in $millis ms")
    }

    @Test
    fun `2000 keys by 15 locales`() {
        val root = File(System.getProperty("java.io.tmpdir"), "translationshub-perf").apply { deleteRecursively() }
        val locales = listOf("ar", "de", "es", "fr", "hi", "it", "ja", "ko", "nl", "pl", "pt-rBR", "ru", "tr", "uk", "ur")
        fun write(folder: String, suffix: String) {
            val text = buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
                for (i in 0 until 1800) append("    <string name=\"key_$i\">Text $i for %1\$s $suffix</string>\n")
                for (i in 0 until 150) append("    <plurals name=\"plural_$i\">\n        <item quantity=\"one\">One $suffix</item>\n        <item quantity=\"other\">%d $suffix</item>\n    </plurals>\n")
                for (i in 0 until 50) append("    <string-array name=\"array_$i\">\n        <item>A $suffix</item>\n        <item>B $suffix</item>\n    </string-array>\n")
                append("</resources>\n")
            }
            File(root, "$folder/strings.xml").apply { parentFile.mkdirs() }.writeText(text)
        }
        write("values", "en")
        locales.forEach { write("values-$it", it) }

        ResourceScanner.read(root) // warm up the XML parser and JIT
        lateinit var set: TranslationSet
        val read = measureTimeMillis { set = ResourceScanner.read(root) }
        lateinit var problems: List<Problem>
        val check = measureTimeMillis { problems = Checks.run(set) }
        val lock = TranslationLock()
        val adopt = measureTimeMillis { lock.adoptUntracked(set) }
        val pending = measureTimeMillis { set.locales.forEach { lock.pending(set, it) } }
        val json = measureTimeMillis { TranslationLock.fromJson(lock.toJson()) }
        println("2000 x 15: read $read ms, checks $check ms (${problems.size} problems), adopt $adopt ms, pending $pending ms, lock JSON round-trip $json ms")

        assertTrue(set.keys.size == 2000 && set.locales.size == 15)
        // Plural forms only: Arabic, Polish, Russian, Ukrainian and the one/many/other languages lack categories.
        assertTrue(problems.all { it is Problem.MissingPluralCategories })
        assertTrue(read < 2000, "Reading took $read ms")
        root.deleteRecursively()
    }
}
