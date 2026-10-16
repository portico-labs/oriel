package dev.fmagana.oriel.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON payloads, shaped exactly as Swift's synthesized Codable writes them upstream: an enum
 * with associated values is `{"case": {"_0": first unlabeled value, "label": value}}`, and nil
 * optionals are left out.
 */

sealed interface StreamSource {
    data object None : StreamSource
    data object Desktop : StreamSource
    data class Window(val id: Long) : StreamSource

    fun toJson(): JSONObject = when (this) {
        None -> JSONObject().put("none", JSONObject())
        Desktop -> JSONObject().put("desktop", JSONObject())
        is Window -> JSONObject().put("window", JSONObject().put("_0", id))
    }

    companion object {
        fun parse(o: JSONObject?): StreamSource = when {
            o == null -> None
            o.has("desktop") -> Desktop
            o.has("window") -> o.optJSONObject("window")?.optLong("_0", -1)?.takeIf { it >= 0 }?.let { Window(it) } ?: None
            else -> None
        }
    }
}

data class WindowInfo(val id: Long, val title: String, val appName: String, val bundleID: String, val width: Int, val height: Int)

data class AppInfo(val name: String, val bundleID: String)

data class WindowList(
    val macName: String,
    val windows: List<WindowInfo>,
    val active: StreamSource,
    val hostVersion: String?,
) {
    companion object {
        fun parse(bytes: ByteArray): WindowList? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = o.optJSONArray("windows") ?: JSONArray()
            val windows = (0 until arr.length()).map { i ->
                val w = arr.getJSONObject(i)
                WindowInfo(
                    id = w.getLong("id"), title = w.optString("title"), appName = w.optString("appName"),
                    bundleID = w.optString("bundleID"), width = w.optInt("width"), height = w.optInt("height"),
                )
            }
            WindowList(
                macName = o.optString("macName", "Mac"), windows = windows,
                active = StreamSource.parse(o.optJSONObject("active")),
                hostVersion = o.optString("hostVersion").ifEmpty { null },
            )
        }.getOrNull()
    }
}

fun parseAppList(bytes: ByteArray): List<AppInfo> = runCatching {
    val arr = JSONArray(String(bytes, Charsets.UTF_8))
    (0 until arr.length()).map { i ->
        val a = arr.getJSONObject(i)
        AppInfo(a.optString("name"), a.optString("bundleID"))
    }
}.getOrDefault(emptyList())

enum class PointerAction(val wire: String) { MOVE("move"), LEFT_DOWN("leftDown"), LEFT_UP("leftUp"), RIGHT_DOWN("rightDown"), RIGHT_UP("rightUp") }

enum class ScrollPhase(val wire: String) { BEGAN("began"), ENDED("ended"), MOMENTUM_BEGAN("momentumBegan"), MOMENTUM_ENDED("momentumEnded") }

/** Kind 8. Positions are fractions (0…1) of the streamed video frame. */
sealed interface InputEvent {
    data class Pointer(val action: PointerAction, val x: Double, val y: Double) : InputEvent
    /** Natural-scrolling sign: positive dy moves the content down. Deltas are fractions of the frame. */
    data class Scroll(val x: Double, val y: Double, val dx: Double, val dy: Double) : InputEvent
    /** "\n", "\t" and "\u0008" (backspace) arrive as their keys; the rest is typed as Unicode. */
    data class Text(val text: String) : InputEvent
    data class ScrollGesture(val phase: ScrollPhase, val x: Double, val y: Double) : InputEvent
    /** A USB HID usage with UIKeyModifierFlags bits ([Modifiers]). */
    data class Key(val hidUsage: Int, val down: Boolean, val modifiers: Long) : InputEvent

    fun toJson(): JSONObject = when (this) {
        is Pointer -> JSONObject().put("pointer", JSONObject().put("_0", action.wire).put("x", x).put("y", y))
        is Scroll -> JSONObject().put("scroll", JSONObject().put("x", x).put("y", y).put("dx", dx).put("dy", dy))
        is Text -> JSONObject().put("text", JSONObject().put("_0", text))
        is ScrollGesture -> JSONObject().put("scrollGesture", JSONObject().put("_0", phase.wire).put("x", x).put("y", y))
        is Key -> JSONObject().put("key", JSONObject().put("hidUsage", hidUsage).put("down", down).put("modifiers", modifiers))
    }
}

/** UIKeyModifierFlags bits, the same positions as CGEventFlags. */
object Modifiers {
    const val SHIFT = 1L shl 17
    const val CONTROL = 1L shl 18
    const val OPTION = 1L shl 19
    const val COMMAND = 1L shl 20
}

object Payloads {
    private fun bytes(o: JSONObject) = o.toString().toByteArray(Charsets.UTF_8)

    fun hello(appVersion: String, build: String, device: String) = bytes(
        JSONObject().put("appVersion", appVersion).put("build", build).put("protocol", 1).put("device", device),
    )

    fun selectSource(s: StreamSource) = bytes(s.toJson())

    fun input(e: InputEvent) = bytes(e.toJson())

    /** Kind 9: the stream panel in points (dp here); scale left out ("leave the Mac window alone"). */
    fun viewport(width: Double, height: Double, fps: Int, scale: Double? = null) = bytes(
        JSONObject().put("width", width).put("height", height).put("fps", fps).apply { if (scale != null) put("scale", scale) },
    )

    fun clientStats(fps: Int, frameAgeMs: Int, rttMs: Int, device: String, frameAgeMaxMs: Int, rttMaxMs: Int) = bytes(
        JSONObject().put("fps", fps).put("frameAgeMs", frameAgeMs).put("rttMs", rttMs).put("device", device)
            .put("frameAgeMaxMs", frameAgeMaxMs).put("rttMaxMs", rttMaxMs),
    )

    fun launchApp(bundleID: String) = bytes(JSONObject().put("bundleID", bundleID))

    fun pairRequest(method: String, proof: String, name: String, model: String) = bytes(
        JSONObject().put("v", 1).put("method", method).put("proof", proof).put("name", name).put("model", model),
    )
}

/** Kind 20. */
data class PairResult(
    val ok: Boolean,
    val proof: String?,
    val macID: String?,
    val name: String?,
    val recognitionKey: String?,
    val reason: String?,
    val triesLeft: Int?,
    val message: String?,
) {
    companion object {
        fun parse(bytes: ByteArray): PairResult? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
            PairResult(
                ok = o.optBoolean("ok"), proof = str("proof"), macID = str("macID"), name = str("name"),
                recognitionKey = str("recognitionKey"), reason = str("reason"),
                triesLeft = if (o.has("triesLeft")) o.optInt("triesLeft") else null, message = str("message"),
            )
        }.getOrNull()
    }
}

/** Kind 22. */
data class Goodbye(val reason: String, val message: String?, val reconnect: Boolean?) {
    companion object {
        fun parse(bytes: ByteArray): Goodbye? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            Goodbye(
                o.optString("reason"),
                o.optString("message").ifEmpty { null },
                if (o.has("reconnect")) o.optBoolean("reconnect") else null,
            )
        }.getOrNull()
    }

    /** The words to show: the Mac's own when it sent them, else Oriel's for the reasons known so far. */
    fun text(macName: String): String = message ?: when (reason) {
        "removed" -> "$macName removed this device. Pair again to connect."
        "quit" -> "Sill on $macName quit."
        "busy" -> "$macName is busy with another device."
        "pairingRequired" -> "$macName now requires pairing."
        "remoteOff" -> "Remote Access is off on $macName."
        "internetOff" -> "$macName no longer allows connections from the internet."
        "update" -> "$macName needs a newer version of this app."
        else -> "$macName closed the connection ($reason)."
    }
}

/** Kind 26: where the Mac's pointer is while this device is not moving it. */
data class MacPointer(val x: Double?, val y: Double?, val inside: Boolean) {
    companion object {
        fun parse(bytes: ByteArray): MacPointer? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            MacPointer(
                if (o.has("x")) o.getDouble("x") else null,
                if (o.has("y")) o.getDouble("y") else null,
                o.optBoolean("inside"),
            )
        }.getOrNull()
    }
}

/** The part of kind 16 Oriel shows: what the Mac is streaming right now. */
data class RunningStream(val width: Int, val height: Int, val fps: Int, val mbps: Int) {
    companion object {
        fun parseFromHostSettings(bytes: ByteArray): RunningStream? = runCatching {
            val s = JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("stream") ?: return null
            RunningStream(s.optInt("width"), s.optInt("height"), s.optInt("fps"), s.optInt("mbps"))
        }.getOrNull()
    }
}
