package io.github.translationshub.idea.translate

import io.github.translationshub.core.Placeholders
import io.github.translationshub.core.Protection
import io.github.translationshub.core.ResourceKey
import io.github.translationshub.core.ResourceValue
import io.github.translationshub.core.TranslationPlan
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Translates plans with one provider: segments are batched to the provider's limits, batches of every locale run
 * in parallel up to its concurrency, placeholders are protected, and a result whose placeholders don't match the
 * source is dropped instead of saved.
 */
class TranslationRunner(private val provider: TranslationProvider, private val sourceLanguage: String) {

    /** Shared by every locale of a run, so "4 at a time" means 4 requests to the service, not 4 per language. */
    private val permits = Semaphore(provider.concurrency)

    data class Result(
        val values: Map<ResourceKey, ResourceValue>,
        /** Keys that weren't translated, with why. */
        val failed: Map<ResourceKey, String>,
    )

    suspend fun run(plan: TranslationPlan, onProgress: (done: Int) -> Unit = {}): Result = coroutineScope {
        val style = provider.protection
        val protected = plan.segments.map { segment -> style?.let { Protection.protect(segment.text, it) } }
        val requests = plan.segments.mapIndexed { i, segment ->
            TranslationRequest(protected[i]?.text ?: segment.text, segment.context, segment.key.name)
        }
        val batches = batches(requests.indices.toList(), requests)
        val results = arrayOfNulls<String>(requests.size)
        val reasons = mutableMapOf<ResourceKey, String>()
        var done = 0
        var firstError: ProviderException? = null

        batches.map { batch ->
            async {
                val translated = try {
                    permits.withPermit { provider.translate(batch.map(requests::get), sourceLanguage, plan.locale) }
                } catch (e: ProviderException) {
                    synchronized(reasons) {
                        firstError = firstError ?: e
                        batch.forEach { reasons[plan.segments[it].key] = e.message ?: "Failed" }
                    }
                    return@async
                }
                synchronized(reasons) {
                    batch.forEachIndexed { j, index ->
                        val segment = plan.segments[index]
                        val raw = translated.getOrNull(j)
                        val restored = when {
                            raw == null -> null
                            style == null -> raw
                            else -> Protection.restore(raw, protected[index]!!, style)
                        }
                        when {
                            restored == null -> reasons[segment.key] = if (raw == null) "No translation returned" else "Placeholders were lost"
                            !placeholdersMatch(segment, restored) -> reasons[segment.key] = "Placeholders don't match the default text"
                            else -> results[index] = restored
                        }
                    }
                    done += batch.size
                    onProgress(done)
                }
            }
        }.awaitAll()

        // Nothing at all came back: surface the service's error (bad key, quota) rather than a list of failed keys.
        if (results.all { it == null } && firstError != null) throw firstError!!
        val values = plan.assemble(results.toList())
        Result(values, reasons.filterKeys { it !in values })
    }

    /** A plural form may drop the number ("One file"), but must not add or change one. */
    private fun placeholdersMatch(segment: TranslationPlan.Segment, translation: String): Boolean {
        val expected = Placeholders.signature(segment.text)
        val actual = Placeholders.signature(translation)
        return if (segment.part is TranslationPlan.Part.Plural) actual.all { (i, c) -> expected[i] == c } else expected == actual
    }

    private fun batches(indices: List<Int>, requests: List<TranslationRequest>): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var chars = 0
        for (i in indices) {
            val length = requests[i].text.length + (requests[i].context?.length ?: 0)
            if (current.isNotEmpty() && (current.size >= provider.maxBatch || chars + length > provider.maxChars)) {
                result += current
                current = mutableListOf()
                chars = 0
            }
            current += i
            chars += length
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}
