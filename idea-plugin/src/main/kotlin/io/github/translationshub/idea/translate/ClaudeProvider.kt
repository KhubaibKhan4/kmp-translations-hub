package io.github.translationshub.idea.translate

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import io.github.translationshub.core.Locale
import io.github.translationshub.core.Protection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Claude through Anthropic's official Java SDK, with your own API key. Answers come back as JSON (a schema-checked
 * output format), and a declined request goes to Anthropic's recommended fallback model on the server.
 */
class ClaudeProvider(
    private val key: () -> String?,
    private val model: () -> String,
    /** For tests: a local stand-in for the API. */
    private val baseUrl: String? = null,
) : TranslationProvider {
    override val id = "claude"
    override val displayName = "Claude (Anthropic)"
    override val protection: Protection.Style? = null
    override val maxBatch = 60
    override val maxChars = 20_000
    override val concurrency = 4

    override fun unavailableReason() = when {
        key().isNullOrBlank() -> "Add an Anthropic API key in Settings → Tools → KMP Translations Hub"
        model().isBlank() -> "Choose a Claude model in Settings → Tools → KMP Translations Hub"
        else -> null
    }

    @Volatile private var client: Pair<String, AnthropicClient>? = null

    private fun client(apiKey: String): AnthropicClient = client?.takeIf { it.first == apiKey }?.second
        ?: AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(4).timeout(Duration.ofMinutes(5))
            .apply { baseUrl?.let(::baseUrl) }.build().also { client = apiKey to it }

    override suspend fun translate(requests: List<TranslationRequest>, from: String, to: Locale): List<String?> = withContext(Dispatchers.IO) {
        val apiKey = key() ?: throw ProviderException("No Anthropic API key")
        val message = try {
            client(apiKey).beta().messages().create(params(requests, from, to))
        } catch (e: UnauthorizedException) {
            throw ProviderException("Claude: the Anthropic API key isn't valid")
        } catch (e: PermissionDeniedException) {
            throw ProviderException("Claude: this API key can't use ${model()}")
        } catch (e: NotFoundException) {
            throw ProviderException("Claude: model ${model()} wasn't found; check it in Settings")
        } catch (e: RateLimitException) {
            throw ProviderException("Claude: rate limited after several retries; try again in a minute")
        } catch (e: BadRequestException) {
            throw ProviderException("Claude: ${e.message}")
        } catch (e: AnthropicServiceException) {
            throw ProviderException("Claude: HTTP ${e.statusCode()} ${e.message}")
        } catch (e: AnthropicIoException) {
            throw ProviderException("Claude: ${e.message ?: "network error"}")
        }
        answer(message, requests.size)
    }

    internal fun params(requests: List<TranslationRequest>, from: String, to: Locale): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model())
            .maxTokens(16_000L)
            .system(LlmPrompt.system(from, to))
            .addUserMessage(LlmPrompt.user(requests))
            .outputConfig(
                BetaOutputConfig.builder()
                    // UI strings are short and many: medium effort, set explicitly (Claude Opus 5.5's default).
                    .effort(BetaOutputConfig.Effort.MEDIUM)
                    .format(BetaJsonOutputFormat.builder().schema(schema()).build())
                    .build(),
            )
        if (model() in SERVER_FALLBACK_MODELS) {
            // A declined request is re-run on Anthropic's recommended fallback model, chosen by why it was declined.
            builder.addBeta("server-side-fallback-2026-07-01").putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    internal fun answer(message: BetaMessage, count: Int): List<String?> {
        val stop = message.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL) throw ProviderException("Claude declined to translate these strings")
        if (stop == BetaStopReason.MAX_TOKENS) throw ProviderException("Claude's answer was cut off; try fewer strings at once")
        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        return LlmPrompt.parse(text, count)
    }

    private fun schema(): BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
        .putAllAdditionalProperties(LlmPrompt.schema.mapValues { JsonValue.from(it.value) })
        .build()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** Models that take `fallbacks: "default"` on the Claude API. */
        private val SERVER_FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}
