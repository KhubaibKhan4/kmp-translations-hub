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
        val results = arrayOfNulls<String>(plan.segments.size)
        val reasons = mutableMapOf<Int, String>()
        var done = 0
        var firstError: ProviderException? = null

        /** Sends [indices] with [style], filling [results] or [reasons]; failed ones are returned for a retry. */
        suspend fun pass(indices: List<Int>, style: Protection.Style?, countProgress: Boolean): List<Int> {
            val protected = indices.associateWith { i -> style?.let { Protection.protect(plan.segments[i].text, it) } }
            val requests = indices.associateWith { i ->
                val segment = plan.segments[i]
                TranslationRequest(protected[i]?.text ?: segment.text, segment.context, segment.key.name)
            }
            val retry = mutableListOf<Int>()
            batches(indices, requests).map { batch ->
                async {
                    val translated = try {
                        permits.withPermit { provider.translate(batch.map(requests::getValue), sourceLanguage, plan.locale) }
                    } catch (e: ProviderException) {
                        synchronized(reasons) {
                            firstError = firstError ?: e
                            batch.forEach { reasons[it] = e.message ?: "Failed" }
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
                                else -> Protection.restore(raw, protected.getValue(index)!!, style)
                            }
                            when {
                                raw == null -> reasons[index] = "No translation returned"
                                restored == null -> { reasons[index] = "${provider.displayName} lost a placeholder"; retry += index }
                                !placeholdersMatch(segment, restored) -> { reasons[index] = "${provider.displayName} changed a placeholder"; retry += index }
                                !Placeholders.addsNoMarkup(segment.text, restored) -> { reasons[index] = "${provider.displayName} added markup"; retry += index }
                                else -> { results[index] = Placeholders.keepEdgeWhitespace(segment.text, restored); reasons.remove(index) }
                            }
                        }
                        if (countProgress) {
                            done += batch.size
                            onProgress(done)
                        }
                    }
                }
            }.awaitAll()
            return retry
        }

        val failed = pass(plan.segments.indices.toList(), provider.protection, countProgress = true)
        // Services handle placeholders differently: give the ones that came back broken one more try the other way.
        val retryStyle = provider.retryProtection
        if (failed.isNotEmpty() && retryStyle != null && retryStyle != provider.protection) pass(failed, retryStyle, countProgress = false)

        // Nothing at all came back: surface the service's error (bad key, quota) rather than a list of failed keys.
        if (results.all { it == null } && firstError != null) throw firstError!!
        val values = plan.assemble(results.toList())
        val byKey = reasons.entries.groupBy({ plan.segments[it.key].key }, { it.value }).mapValues { it.value.first() }
        Result(values, byKey.filterKeys { it !in values })
    }

    /** A plural form may drop the number ("One file"), but must not add or change one. */
    private fun placeholdersMatch(segment: TranslationPlan.Segment, translation: String): Boolean {
        val expected = Placeholders.signature(segment.text)
        val actual = Placeholders.signature(translation)
        return if (segment.part is TranslationPlan.Part.Plural) actual.all { (i, c) -> expected[i] == c } else expected == actual
    }

    private fun batches(indices: List<Int>, requests: Map<Int, TranslationRequest>): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var chars = 0
        for (i in indices) {
            val request = requests.getValue(i)
            val length = request.text.length + (request.context?.length ?: 0)
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
