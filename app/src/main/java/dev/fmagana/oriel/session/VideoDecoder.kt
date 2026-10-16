package dev.fmagana.oriel.session

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import dev.fmagana.oriel.protocol.Hevc
import dev.fmagana.oriel.protocol.ParameterSets

/**
 * HEVC from the Mac (Main profile, no B-frames, length-prefixed NAL units) decoded in hardware
 * straight onto a Surface. Every frame is shown the moment it decodes: the host already drops
 * frames rather than queue them, so nothing here waits for a presentation time.
 */
class VideoDecoder(
    private val onVideoSize: (Int, Int) -> Unit,
    private val onFrameShown: () -> Unit,
) {
    private val thread = HandlerThread("oriel-decoder").apply { start() }
    private val handler = Handler(thread.looper)

    // Everything below is touched on `thread` only.
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var params: ParameterSets? = null
    private var needKeyframe = true
    private val pending = ArrayDeque<Pair<ByteArray, Boolean>>()
    private val freeInputs = ArrayDeque<Int>()
    private var pts = 0L

    fun setSurface(s: Surface?) = handler.post {
        if (s == surface) return@post
        stopCodec()
        surface = s
        startCodec()
    }

    fun setParameterSets(ps: ParameterSets) = handler.post {
        if (ps == params && codec != null) return@post
        params = ps
        stopCodec()
        startCodec()
    }

    fun submit(au: ByteArray, keyframe: Boolean) = handler.post {
        if (codec == null) return@post
        if (needKeyframe && !keyframe) return@post
        // Far behind: drop what waits and pick up again at the next keyframe.
        if (pending.size >= MAX_PENDING) {
            pending.clear()
            needKeyframe = true
            if (!keyframe) return@post
        }
        needKeyframe = false
        pending.addLast(au to keyframe)
        feed()
    }

    fun release() {
        handler.post {
            stopCodec()
            thread.quitSafely()
        }
    }

    private fun startCodec() {
        val ps = params ?: return
        val s = surface ?: return
        val sps = ps.sets.firstOrNull { it.size > 2 && ((it[0].toInt() ushr 1) and 0x3F) == 33 }
        val (w, h) = sps?.let { Hevc.spsSize(it) } ?: (1920 to 1080)
        onVideoSize(w, h)
        try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(ps.annexB()))
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)   // realtime
            }
            c.setCallback(callback, handler)
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            needKeyframe = true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start the HEVC decoder for ${w}x$h", e)
            codec = null
        }
    }

    private fun stopCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        pending.clear()
        freeInputs.clear()
    }

    private fun feed() {
        val c = codec ?: return
        val nalLen = params?.nalUnitHeaderLength ?: 4
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val (au, key) = pending.removeFirst()
            val index = freeInputs.removeFirst()
            val annexB = Hevc.toAnnexB(au, nalLen)
            val buffer = c.getInputBuffer(index)
            if (annexB == null || buffer == null || annexB.size > buffer.capacity()) {
                freeInputs.addFirst(index)
                needKeyframe = true
                pending.clear()
                return
            }
            buffer.clear()
            buffer.put(annexB)
            pts += 16_667
            c.queueInputBuffer(index, 0, annexB.size, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c !== codec) return
            freeInputs.addLast(index)
            feed()
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            // With a Surface, Codec2 reports a size of 0 for picture buffers: render all but end of stream.
            val render = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
            c.releaseOutputBuffer(index, render)
            if (render) onFrameShown()
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            if (c !== codec) return
            val w = if (format.containsKey("crop-right")) format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            else format.getInteger(MediaFormat.KEY_WIDTH)
            val h = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
            else format.getInteger(MediaFormat.KEY_HEIGHT)
            if (w > 0 && h > 0) onVideoSize(w, h)
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Decoder error", e)
            if (c !== codec) return
            stopCodec()
            startCodec()
        }
    }

    companion object {
        private const val TAG = "OrielDecoder"
        private const val MAX_PENDING = 8
    }
}
