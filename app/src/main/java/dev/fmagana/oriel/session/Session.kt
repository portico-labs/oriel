package dev.fmagana.oriel.session

import android.graphics.BitmapFactory
import android.util.Log
import android.view.Surface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.fmagana.oriel.net.DeviceIdentity
import dev.fmagana.oriel.net.Link
import dev.fmagana.oriel.net.PinMismatchException
import dev.fmagana.oriel.protocol.AppInfo
import dev.fmagana.oriel.protocol.Blobs
import dev.fmagana.oriel.protocol.Goodbye
import dev.fmagana.oriel.protocol.InputEvent
import dev.fmagana.oriel.protocol.Kind
import dev.fmagana.oriel.protocol.Message
import dev.fmagana.oriel.protocol.ParameterSets
import dev.fmagana.oriel.protocol.Payloads
import dev.fmagana.oriel.protocol.RunningStream
import dev.fmagana.oriel.protocol.StreamSource
import dev.fmagana.oriel.protocol.WindowInfo
import dev.fmagana.oriel.protocol.WindowList
import dev.fmagana.oriel.protocol.parseAppList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

/** Where and how to dial a Mac for a session. */
data class SessionTarget(
    val name: String,
    val host: String,
    val port: Int,
    /** False: a development host's plain door. */
    val tls: Boolean,
    /** The Mac's pinned key; null at an open door. */
    val pin: ByteArray?,
)

data class SessionState(
    val phase: Phase = Phase.CONNECTING,
    val macName: String = "",
    val windows: List<WindowInfo> = emptyList(),
    val active: StreamSource = StreamSource.None,
    val thumbnails: Map<Long, ImageBitmap> = emptyMap(),
    val icons: Map<String, ImageBitmap> = emptyMap(),
    val apps: List<AppInfo> = emptyList(),
    val videoSize: Pair<Int, Int>? = null,
    val stream: RunningStream? = null,
    val rttMs: Int? = null,
    val fps: Int = 0,
    /** Why the session ended, in words for the person. */
    val ended: String? = null,
) {
    enum class Phase { CONNECTING, LIVE, ENDED }
}

/**
 * One session with a Mac: the connection, its reader and writer, the pings and stats, the decoder,
 * and everything the stream screen shows.
 */
class Session(
    private val target: SessionTarget,
    private val identity: DeviceIdentity,
    private val hello: ByteArray,
    private val deviceLabel: String,
) {
    private val _state = MutableStateFlow(SessionState(macName = target.name))
    val state: StateFlow<SessionState> = _state

    private val outbox = LinkedBlockingQueue<Message>()
    private var link: Link? = null
    @Volatile private var closed = false
    private var timers: ScheduledExecutorService? = null
    private var askedForDesktop = false
    @Volatile private var viewport: ByteArray? = null

    // Stats for the second in progress (kind 12), touched by several threads.
    private val shownThisSecond = java.util.concurrent.atomic.AtomicInteger()
    private val frameAges = java.util.Collections.synchronizedList(ArrayList<Int>())
    private val rtts = java.util.Collections.synchronizedList(ArrayList<Int>())

    val decoder = VideoDecoder(
        onVideoSize = { w, h -> _state.update { it.copy(videoSize = w to h) } },
        onFrameShown = { shownThisSecond.incrementAndGet() },
    )

    fun start() {
        thread(name = "oriel-session") { run() }
    }

    fun close() {
        if (closed) return
        closed = true
        timers?.shutdownNow()
        outbox.offer(POISON)
        // Closing TLS writes an alert: never on the main thread.
        link?.let { l -> thread(name = "oriel-close") { l.close() } }
        decoder.release()
        _state.update { if (it.phase == SessionState.Phase.ENDED) it else it.copy(phase = SessionState.Phase.ENDED) }
    }

    fun setSurface(surface: Surface?) = decoder.setSurface(surface)

    fun select(source: StreamSource) = send(Kind.SELECT_SOURCE, Payloads.selectSource(source))

    fun launch(app: AppInfo) = send(Kind.LAUNCH_APP, Payloads.launchApp(app.bundleID))

    fun input(e: InputEvent) = send(Kind.INPUT, Payloads.input(e))

    /** The stream panel's size in dp and the display's refresh rate; resent whenever they change. */
    fun setViewport(widthDp: Double, heightDp: Double, fps: Int) {
        val v = Payloads.viewport(widthDp, heightDp, fps)
        if (v.contentEquals(viewport)) return
        viewport = v
        if (state.value.phase == SessionState.Phase.LIVE) send(Kind.VIEWPORT, v)
    }

    private fun send(kind: Int, payload: ByteArray) {
        if (!closed) outbox.offer(Message.of(kind, payload))
    }

    private fun run() {
        val l = try {
            if (target.tls) Link.tls(target.host, target.port, Link.SESSION_ALPN, identity, target.pin)
            else Link.plain(target.host, target.port)
        } catch (e: Exception) {
            end(connectFailure(e))
            return
        }
        if (closed) { l.close(); return }
        link = l
        // The hello is the first message of every session, so the host can judge this device first.
        l.send(Message.of(Kind.HELLO, hello))
        thread(name = "oriel-writer") { writeLoop(l) }
        startTimers()
        try {
            while (!closed) {
                val m = l.read() ?: break
                handle(m)
            }
            end("${state.value.macName.ifEmpty { target.name }} closed the connection.")
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "Read failed", e)
            end("Lost the connection to ${state.value.macName.ifEmpty { target.name }}.")
        }
    }

    private fun connectFailure(e: Exception): String = when {
        e is PinMismatchException || e.cause is PinMismatchException ->
            "${target.name} presented a different key than the one paired. Forget it and pair again."
        e is SSLException && target.pin != null ->
            "${target.name} refused this device. If it was removed on the Mac, forget it here and pair again."
        e is SSLException -> "The secure connection to ${target.name} failed: ${e.message}"
        else -> "Could not reach ${target.name} at ${target.host}:${target.port}. ${e.message ?: ""}".trim()
    }

    private fun end(words: String) {
        if (state.value.phase != SessionState.Phase.ENDED || state.value.ended == null) {
            _state.update { it.copy(phase = SessionState.Phase.ENDED, ended = it.ended ?: words) }
        }
        close()
    }

    private fun writeLoop(l: Link) {
        try {
            while (!closed) {
                val m = outbox.take()
                if (m === POISON) break
                l.send(m)
            }
        } catch (e: Exception) {
            if (!closed) end("Lost the connection to ${state.value.macName.ifEmpty { target.name }}.")
        }
    }

    private fun startTimers() {
        val t = Executors.newSingleThreadScheduledExecutor()
        timers = t
        t.scheduleWithFixedDelay({
            send(Kind.PING, ByteBuffer.allocate(8).putDouble(Message.now()).array())
        }, 0, 250, TimeUnit.MILLISECONDS)
        t.scheduleWithFixedDelay({ reportSecond() }, 1, 1, TimeUnit.SECONDS)
    }

    private fun reportSecond() {
        val fps = shownThisSecond.getAndSet(0)
        val ages = synchronized(frameAges) { frameAges.toList().also { frameAges.clear() } }
        val trips = synchronized(rtts) { rtts.toList().also { rtts.clear() } }
        fun median(v: List<Int>) = if (v.isEmpty()) -1 else v.sorted()[v.size / 2]
        val rtt = median(trips)
        _state.update { it.copy(fps = fps, rttMs = if (rtt >= 0) rtt else it.rttMs) }
        send(Kind.CLIENT_STATS, Payloads.clientStats(fps, median(ages), rtt, deviceLabel, ages.maxOrNull() ?: -1, trips.maxOrNull() ?: -1))
    }

    private fun handle(m: Message) {
        when (m.kind) {
            Kind.PARAMETER_SETS -> ParameterSets.decode(m.payload)?.let { decoder.setParameterSets(it) }
            Kind.FRAME -> {
                frameAges.add(((Message.now() - m.timestamp) * 1000).toInt())
                decoder.submit(m.payload, m.isKeyframe)
            }
            Kind.WINDOW_LIST -> WindowList.parse(m.payload)?.let { list ->
                val first = state.value.phase == SessionState.Phase.CONNECTING
                _state.update {
                    it.copy(
                        phase = SessionState.Phase.LIVE, macName = list.macName, windows = list.windows, active = list.active,
                        thumbnails = it.thumbnails.filterKeys { id -> list.windows.any { w -> w.id == id } },
                    )
                }
                if (first) viewport?.let { send(Kind.VIEWPORT, it) }
                // A device asks for the Desktop only when nothing streams yet.
                if (!askedForDesktop && list.active == StreamSource.None) {
                    askedForDesktop = true
                    select(StreamSource.Desktop)
                }
            }
            Kind.THUMBNAIL -> Blobs.thumbnail(m.payload)?.let { (id, jpeg) ->
                image(jpeg)?.let { bmp -> _state.update { it.copy(thumbnails = it.thumbnails + (id to bmp)) } }
            }
            Kind.APP_ICON -> Blobs.appIcon(m.payload)?.let { (bundle, png) ->
                image(png)?.let { bmp -> _state.update { it.copy(icons = it.icons + (bundle to bmp)) } }
            }
            Kind.APP_LIST -> _state.update { it.copy(apps = parseAppList(m.payload).sortedBy { a -> a.name.lowercase() }) }
            Kind.PONG -> if (m.payload.size == 8) {
                rtts.add(((Message.now() - ByteBuffer.wrap(m.payload).double) * 1000).toInt())
            }
            Kind.HOST_SETTINGS -> RunningStream.parseFromHostSettings(m.payload).let { s -> _state.update { it.copy(stream = s) } }
            Kind.GOODBYE -> Goodbye.parse(m.payload)?.let { g ->
                _state.update { it.copy(ended = g.text(it.macName.ifEmpty { target.name })) }
            }
            else -> Unit   // ticks, cursor shapes, the Mac's pointer and menus, and kinds this build does not know
        }
    }

    private fun image(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

    companion object {
        private const val TAG = "OrielSession"
        private val POISON = Message(255, 0.0, false, ByteArray(0))
    }
}
