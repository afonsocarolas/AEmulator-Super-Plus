/* AEmulator Super Plus, 2026-10-12: AIemulator - the conversation with Claude and its tool loop. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration

/** What the chat screen shows while a turn runs. */
interface AiEvents {
    /** Called on the network thread while the answer streams in. */
    fun text(delta: String)
    fun toolStarted(name: String)
    /** The turn ended without a usable answer; history was rolled back so the user can try again. */
    suspend fun failed(kind: AiFailure, detail: String)
}

enum class AiFailure { NOT_CONFIGURED, ACCESS_CODE, RATE_LIMIT, NETWORK, REFUSED, TOO_LONG, SERVER }

/**
 * One AIemulator conversation. Requests go to the owner's proxy server ([serverUrl]), which holds the Anthropic API key,
 * fixes the model and limits, and checks [accessCode]. The history is append-only: a failed turn is cut off at its start,
 * earlier turns (and their thinking blocks) are never edited.
 */
class AiAgent(serverUrl: String, accessCode: String, private val tools: AiTools) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .baseUrl(serverUrl.trimEnd('/'))
        // the proxy reads the access code from x-api-key; the real key never reaches the phone
        .apiKey(accessCode.ifBlank { "none" })
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()

    private val history = mutableListOf<MessageParam>()
    // a stopped turn may still be cleaning up its history when the next one starts
    private val lock = Mutex()

    suspend fun send(userText: String, events: AiEvents) = lock.withLock { turn(userText, events) }

    private suspend fun turn(userText: String, events: AiEvents) {
        val start = history.size
        history += MessageParam.builder().role(MessageParam.Role.USER).content(userText).build()
        try {
            while (true) {
                val acc = MessageAccumulator.create()
                client.messages().createStreaming(params()).use { response ->
                    val stream = response.stream().iterator()
                    while (stream.hasNext()) {
                        currentCoroutineContext().ensureActive()
                        val event = acc.accumulate(stream.next())
                        event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { events.text(it.text()) }
                        event.contentBlockStart().flatMap { it.contentBlock().toolUse() }.ifPresent { events.toolStarted(it.name()) }
                    }
                }
                val message = acc.message()
                val toolUses = message.content().mapNotNull { it.toolUse().orElse(null) }
                when (message.stopReason().orElse(null)) {
                    // a decline can arrive after some text: drop the partial answer along with the turn
                    StopReason.REFUSAL -> { cut(start); events.failed(AiFailure.REFUSED, message.stopDetails().flatMap { it.explanation() }.orElse("")); return }
                    StopReason.MAX_TOKENS -> {
                        if (toolUses.isNotEmpty()) { cut(start); events.failed(AiFailure.TOO_LONG, ""); return }
                        history += message.toParam(); events.failed(AiFailure.TOO_LONG, ""); return
                    }
                    StopReason.TOOL_USE -> {
                        history += message.toParam()
                        // every result goes back in one user message, so parallel calls stay parallel
                        val results = toolUses.map { use ->
                            val input = runCatching { JSONObject(use._input().convert(Map::class.java)) }.getOrNull()
                            val (text, isError) = if (input == null) "Tool input was not valid JSON; send the call again." to true
                                else tools.run(use.name(), input)
                            ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id()).content(text).isError(isError).build())
                        }
                        history += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build()
                    }
                    StopReason.PAUSE_TURN -> history += message.toParam()
                    else -> { history += message.toParam(); return }
                }
            }
        } catch (e: UnauthorizedException) { cut(start); events.failed(AiFailure.ACCESS_CODE, "")
        } catch (e: PermissionDeniedException) { cut(start); events.failed(AiFailure.ACCESS_CODE, "")
        } catch (e: RateLimitException) { cut(start); events.failed(AiFailure.RATE_LIMIT, "")
        } catch (e: AnthropicServiceException) { cut(start); events.failed(AiFailure.SERVER, "${e.statusCode()} ${e.errorType().map { it.toString() }.orElse("")}".trim())
        } catch (e: AnthropicIoException) { cut(start); events.failed(AiFailure.NETWORK, e.message ?: "")
        } catch (e: Throwable) { cut(start); throw e }
    }

    private fun cut(size: Int) { while (history.size > size) history.removeAt(history.lastIndex) }

    private fun params(): MessageCreateParams = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(64_000L)
        .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(SYSTEM).build()))
        .apply { tools.definitions.forEach { addTool(it) } }
        .messages(history)
        // tools and system prompt never change during a chat: cache them and the growing history
        .cacheControl(CacheControlEphemeral.builder().build())
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
        .build()

    companion object {
        /** The proxy enforces its own model; this is what the app asks for. */
        const val MODEL = "claude-opus-5-5"

        private val SYSTEM = """
            You are AIemulator, the assistant built into AEmulator Super Plus, an Android app that runs old Android
            firmwares (Android 2.x to 8.x) as "systems" on the user's phone. Each system is a firmware the user imported
            (from a ROM, a Sony FTF/SIN, a zip, the built-in catalog...). The app runs the firmware's own userspace with a
            qemu-based engine and bridges the phone's screen, GPU, touch, sound, battery, camera, microphone, Wi-Fi,
            Bluetooth and sensors into it. There is no root and the phone's own system is never changed.

            You help people get their systems booting and running well, explain settings, and answer questions about the
            app. Use your tools to look at the real state (list_systems, get_system_settings, read_system_log,
            get_host_info) instead of guessing, and quote the log lines that matter when you explain a problem.

            Useful background:
            - GPU (gpu) draws the guest through the phone's GPU; turning it off falls back to the firmware's software
              renderer: slower, but it fixes black or garbled screens on some phones. legacyEngine is an older path for
              2.x firmwares (GLES 1.x only). hwui is hardware UI drawing inside Android 4.x apps.
            - JIT (jit) speeds up Dalvik (Android 2.x-4.x); off is slower but more stable. fullDexopt (ART, 5.0+)
              compiles apps fully at first boot: faster later, much slower first boot.
            - The first boot of a system is slow because Android optimizes its apps; a boot that keeps restarting or
              stalls usually shows why near the end of the host log or in E/ lines of the guest log.
            - lowRam and ramMb limit guest memory; netProxy gives old browsers modern TLS; radio emulates a modem (off
              for tablet firmwares); wifi emulates a connected Wi-Fi network; hostResolution uses the phone's resolution.
            - Settings changes apply at the next full boot of that system.

            To change anything, use change_system_settings or change_app_appearance. The user approves each change on a
            card in the chat; never say something was changed unless the tool result says it was applied. If the user
            declines, accept it and suggest what else could help.

            Answers are read on a phone: keep them short and practical, use plain words, and give steps the user can
            follow in the app. The chat shows plain text, so write without Markdown (no **, #, or tables); short
            numbered steps are fine. Reply in the language the user writes in.
        """.trimIndent()
    }
}
