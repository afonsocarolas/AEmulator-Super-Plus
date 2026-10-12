/* AEmulator Super Plus, 2026-10-12: AIemulator - the tools the assistant may use. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.aemu.AppPrefs
import app.aemu.BuildConfig
import app.aemu.core.GuestLog
import app.aemu.core.HostInfo
import app.aemu.core.ImageStore
import app.aemu.core.LiveVmStatus
import app.aemu.core.VmPaths
import app.aemu.core.VmSettings
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A change the assistant wants to make; nothing is written until the user approves it in the chat. */
data class ChangeRequest(
    val title: String,
    val reason: String,
    /** setting -> (current value, proposed value), as shown on the approval card */
    val diff: List<Triple<String, String, String>>,
    val apply: () -> String,
)

class AiTools(private val ctx: Context, private val approve: suspend (ChangeRequest) -> Boolean) {

    /** Settings the assistant may change. Identity, passwords, raw qemu options and tracing stay with the user. */
    private val editable = mapOf(
        "gpu" to "bool", "hwui" to "bool", "jit" to "bool", "fullDexopt" to "bool", "fbHz" to "int", "touchHz" to "int",
        "netProxy" to "bool", "lowRam" to "bool", "showFrame" to "bool", "showNavBar" to "bool", "hideMenuButton" to "bool",
        "keepScreenOn" to "bool", "vibration" to "bool", "hostBattery" to "bool", "camera" to "bool", "bluetooth" to "bool",
        "microphone" to "bool", "motionSensors" to "bool", "hostResolution" to "bool", "skipSetupWizard" to "bool",
        "disableGoogleApps" to "bool", "legacyEngine" to "bool", "ramMb" to "int", "radio" to "bool", "networkType" to "int",
        "wifi" to "bool", "wifiSsid" to "string", "wifiSignal" to "int", "wifiNeighbors" to "bool",
        "width" to "int", "height" to "int", "density" to "int",
    )
    private val ranges = mapOf(
        "fbHz" to 10..120, "touchHz" to 10..240, "ramMb" to 0..16384, "networkType" to 0..20, "wifiSignal" to 1..4,
        "width" to 240..4096, "height" to 240..4096, "density" to 80..800,
    )

    val definitions: List<Tool> = listOf(
        tool("list_systems", "List the Android systems (imported firmwares) in AEmulator Super Plus: id, name, Android version, " +
            "device, engine, whether it is running now, boot count and import warnings. Call this first to find a system_id.", emptyMap()),
        tool("get_system_settings", "Read every setting of one system (display, GPU, JIT, network, Wi-Fi, SIM, sensors, RAM...). " +
            "Passwords are hidden.", mapOf("system_id" to str("id from list_systems")), listOf("system_id")),
        tool("read_system_log", "Read the end of a system's log to troubleshoot. log=\"host\" is the emulator's own log " +
            "(engine start, services, crashes); log=\"guest\" is the Android logcat from inside the system (E/W/I lines with tags).",
            mapOf(
                "system_id" to str("id from list_systems"),
                "log" to mapOf("type" to "string", "enum" to listOf("host", "guest")),
                "max_lines" to mapOf("type" to "integer", "description" to "how many lines from the end, 20-600, default 200"),
                "filter" to str("optional text; only lines containing it (case-insensitive) are returned"),
            ), listOf("system_id", "log")),
        tool("get_host_info", "Information about the user's phone that runs the emulator: model, Android version, CPU ABIs, GPU " +
            "driver, RAM, and the app version.", emptyMap()),
        tool("change_system_settings", "Propose changes to one system's settings. The user sees each old -> new value and must tap " +
            "Approve; the result tells you whether they did. Changes apply at the next full boot of that system. " +
            "Editable keys: ${editable.keys.joinToString()}.",
            mapOf(
                "system_id" to str("id from list_systems"),
                "changes" to mapOf("type" to "object", "description" to "setting key -> new value, e.g. {\"gpu\": false, \"jit\": false}"),
                "reason" to str("one short sentence for the user: why this should help"),
            ), listOf("system_id", "changes", "reason")),
        tool("change_app_appearance", "Propose changes to how the AEmulator Super Plus app looks. The user must approve. " +
            "Only include fields you want to change.",
            mapOf(
                "theme" to mapOf("type" to "string", "enum" to listOf("system", "light", "dark")),
                "color_theme" to mapOf("type" to "string", "enum" to listOf("sunset", "android")),
                "pure_black" to mapOf("type" to "boolean"),
                "corners" to mapOf("type" to "string", "enum" to listOf("sharp", "soft", "round", "extra_round")),
                "font" to mapOf("type" to "string", "enum" to listOf("default", "serif", "mono")),
                "text_size_percent" to mapOf("type" to "integer", "description" to "85-130"),
                "expressive_animations" to mapOf("type" to "boolean"),
                "reason" to str("one short sentence for the user"),
            ), listOf("reason")),
    )

    /** Runs one tool call. Returns (result text, isError). Bad input comes back as an error the model can correct. */
    suspend fun run(name: String, input: JSONObject): Pair<String, Boolean> = try {
        when (name) {
            "list_systems" -> listSystems() to false
            "get_system_settings" -> systemSettings(input.getString("system_id")) to false
            "read_system_log" -> readLog(input.getString("system_id"), input.getString("log"),
                input.optInt("max_lines", 200).coerceIn(20, 600), input.optString("filter", "")) to false
            "get_host_info" -> hostInfo() to false
            "change_system_settings" -> changeSettings(input.getString("system_id"), input.getJSONObject("changes"), input.getString("reason")) to false
            "change_app_appearance" -> changeAppearance(input) to false
            else -> "Unknown tool $name" to true
        }
    } catch (e: Exception) {
        (e.message ?: e.javaClass.simpleName) to true
    }

    private fun running(): String? = LiveVmStatus(ctx.filesDir).activeId()

    private fun listSystems(): String {
        val live = running()
        val all = ImageStore.list(ctx)
        if (all.isEmpty()) return "No systems imported yet. The user adds one with the Import button on the library screen."
        return JSONArray(all.map { img ->
            JSONObject().put("system_id", img.id).put("name", img.name).put("android", img.release).put("api", img.api)
                .put("device", "${img.brand} ${img.model}".trim()).put("engine", img.engine.title).put("abi", img.abi)
                .put("running", img.id == live).put("boot_count", img.bootCount).put("last_boot_ms", img.lastBootMs)
                .put("container_of", img.baseId.ifEmpty { null }).put("warnings", JSONArray(img.warnings))
        }).toString(1)
    }

    private fun image(id: String) = ImageStore.get(ctx, id) ?: throw IllegalArgumentException("No system with id $id; call list_systems")

    private fun systemSettings(id: String): String {
        val json = image(id).settings.toJson()
        if (json.optString("wifiPassword").isNotEmpty()) json.put("wifiPassword", "(set, hidden)")
        return json.toString(1)
    }

    private fun readLog(id: String, log: String, maxLines: Int, filter: String): String {
        val paths = VmPaths(ctx, image(id).id)
        val lines = when (log) {
            "host" -> File(paths.bin, "aemu.log").takeIf { it.isFile }?.let { tailLines(it, 512 * 1024) } ?: emptyList()
            "guest" -> GuestLog.tail(paths.root).map { "${it.prio}/${it.tag}: ${it.msg}" }
            else -> throw IllegalArgumentException("log must be \"host\" or \"guest\"")
        }
        val picked = lines.filter { filter.isEmpty() || it.contains(filter, ignoreCase = true) }.takeLast(maxLines)
        if (picked.isEmpty()) return if (lines.isEmpty()) "The $log log is empty: the system has not been started since it was last cleared."
            else "No $log log lines contain \"$filter\"."
        // a phone-sized cap on what goes back to the model
        return picked.joinToString("\n") { it.take(400) }.takeLast(48_000)
    }

    private fun tailLines(f: File, bytes: Int): List<String> {
        val start = (f.length() - bytes).coerceAtLeast(0)
        val text = java.io.RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            ByteArray((f.length() - start).toInt()).also { raf.readFully(it) }.toString(Charsets.UTF_8)
        }
        return text.lines().let { if (start > 0) it.drop(1) else it }.filter { it.isNotBlank() }
    }

    private fun hostInfo(): String {
        val mem = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        return JSONObject()
            .put("phone", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("gpu_driver", HostInfo.gpu())
            .put("ram_total_mb", mem.totalMem shr 20).put("ram_free_mb", mem.availMem shr 20)
            .put("app", "AEmulator Super Plus ${BuildConfig.VERSION_NAME}").toString(1)
    }

    private suspend fun changeSettings(id: String, changes: JSONObject, reason: String): String {
        val img = image(id)
        val current = img.settings.toJson()
        val next = JSONObject(current.toString())
        val diff = mutableListOf<Triple<String, String, String>>()
        for (key in changes.keys()) {
            val type = editable[key] ?: throw IllegalArgumentException("\"$key\" cannot be changed by AIemulator. Editable: ${editable.keys.joinToString()}")
            val value: Any = when (type) {
                "bool" -> changes.get(key) as? Boolean ?: throw IllegalArgumentException("$key must be true or false")
                "int" -> (changes.get(key) as? Number)?.toInt()?.also { v ->
                    ranges[key]?.let { r -> require(v in r) { "$key must be in ${r.first}..${r.last}" } }
                } ?: throw IllegalArgumentException("$key must be a whole number")
                else -> (changes.get(key) as? String)?.take(32) ?: throw IllegalArgumentException("$key must be text")
            }
            if (current.opt(key) != value) diff += Triple(key, current.opt(key).toString(), value.toString())
            next.put(key, value)
        }
        if (diff.isEmpty()) return "Nothing to change: those values are already set."
        val ok = approve(ChangeRequest(img.name, reason, diff) {
            val settings = VmSettings.fromJson(next)
            ImageStore.save(ctx, (ImageStore.get(ctx, id) ?: img).copy(settings = settings))
            "saved"
        })
        if (!ok) return "The user declined. Nothing was changed."
        val live = running() == id
        return "Applied to ${img.name}: " + diff.joinToString { "${it.first} ${it.second} -> ${it.third}" } +
            if (live) ". The system is running now; the changes apply after the user restarts it." else ". They apply at its next boot."
    }

    private suspend fun changeAppearance(input: JSONObject): String {
        val reason = input.getString("reason")
        var style = AppPrefs.uiStyle(ctx)
        var theme = AppPrefs.theme(ctx)
        var palette = AppPrefs.palette(ctx)
        val diff = mutableListOf<Triple<String, String, String>>()
        fun <T> note(label: String, old: T, new: T) { if (old != new) diff += Triple(label, old.toString(), new.toString()) }
        val themes = listOf("system", "light", "dark")
        val corners = listOf("sharp", "soft", "round", "extra_round")
        val fonts = listOf("default", "serif", "mono")
        input.optString("theme").takeIf { it.isNotEmpty() }?.let { v ->
            val i = themes.indexOf(v).also { require(it >= 0) { "theme: system, light or dark" } }
            note("theme", themes[theme], v); theme = i
        }
        input.optString("color_theme").takeIf { it.isNotEmpty() }?.let { v ->
            val i = listOf("sunset", "android").indexOf(v).also { require(it >= 0) { "color_theme: sunset or android" } }
            note("color theme", if (palette == AppPrefs.PALETTE_ANDROID) "android" else "sunset", v); palette = i
        }
        if (input.has("pure_black")) { val v = input.getBoolean("pure_black"); note("pure black", style.pureBlack, v); style = style.copy(pureBlack = v) }
        input.optString("corners").takeIf { it.isNotEmpty() }?.let { v ->
            val i = corners.indexOf(v).also { require(it >= 0) { "corners: ${corners.joinToString()}" } }
            note("corners", corners[style.corners], v); style = style.copy(corners = i)
        }
        input.optString("font").takeIf { it.isNotEmpty() }?.let { v ->
            val i = fonts.indexOf(v).also { require(it >= 0) { "font: ${fonts.joinToString()}" } }
            note("font", fonts[style.font], v); style = style.copy(font = i)
        }
        if (input.has("text_size_percent")) {
            val v = input.getInt("text_size_percent").coerceIn(85, 130)
            note("text size", "${(style.textScale * 100).toInt()}%", "$v%"); style = style.copy(textScale = v / 100f)
        }
        if (input.has("expressive_animations")) {
            val v = input.getBoolean("expressive_animations"); note("expressive animations", style.expressiveMotion, v); style = style.copy(expressiveMotion = v)
        }
        if (diff.isEmpty()) return "Nothing to change: the app already looks like that."
        val (t, p, s) = Triple(theme, palette, style)
        val ok = approve(ChangeRequest("AEmulator Super Plus", reason, diff) {
            AppPrefs.setTheme(ctx, t); AppPrefs.setPalette(ctx, p); AppPrefs.setUiStyle(ctx, s); "saved"
        })
        return if (ok) "Applied: " + diff.joinToString { "${it.first} -> ${it.third}" } + ". The app restyles right away."
            else "The user declined. Nothing was changed."
    }

    private fun str(description: String) = mapOf("type" to "string", "description" to description)

    private fun tool(name: String, description: String, props: Map<String, Any>, required: List<String> = emptyList()): Tool =
        Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(Tool.InputSchema.builder()
                .properties(Tool.InputSchema.Properties.builder().apply {
                    props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                }.build())
                .required(required)
                .build())
            // inputs stream as they are generated; AiAgent validates each one before running it
            .eagerInputStreaming(true)
            .build()
}
