package dev.fmagana.oriel.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Sill's wire format (Sources/StreamProtocol/StreamMessage.swift upstream).
 * Header, 14 bytes big endian: kind(1) timestamp(8, Double) isKeyframe(1) payloadLength(4).
 */
object Kind {
    const val PARAMETER_SETS = 0
    const val FRAME = 1
    const val WINDOW_LIST = 2
    const val THUMBNAIL = 3
    const val APP_ICON = 4
    const val APP_LIST = 5
    const val SELECT_SOURCE = 6
    const val LAUNCH_APP = 7
    const val INPUT = 8
    const val VIEWPORT = 9
    const val PING = 10
    const val PONG = 11
    const val CLIENT_STATS = 12
    const val TICK = 13
    const val CURSOR_SHAPE = 14
    const val WINDOW_COMMAND = 15
    const val HOST_SETTINGS = 16
    const val CHANGE_SETTINGS = 17
    const val MAC_INFO = 18
    const val PAIR_REQUEST = 19
    const val PAIR_RESULT = 20
    const val PAIRING_WANTED = 21
    const val GOODBYE = 22
    const val HELLO = 23
    const val MAC_POINTER = 26
    const val GESTURE = 28
    const val CLIPBOARD = 32
}

class Message(val kind: Int, val timestamp: Double, val isKeyframe: Boolean, val payload: ByteArray) {
    fun serialized(): ByteArray {
        val b = ByteBuffer.allocate(HEADER_LENGTH + payload.size)
        b.put(kind.toByte())
        b.putDouble(timestamp)
        b.put(if (isKeyframe) 1 else 0)
        b.putInt(payload.size)
        b.put(payload)
        return b.array()
    }

    companion object {
        const val HEADER_LENGTH = 14
        /** Caps on what a header may announce, as the iOS client applies them. */
        const val MAX_FRAME_PAYLOAD = 32 shl 20
        const val MAX_OTHER_HOST_PAYLOAD = 4 shl 20

        fun now(): Double = System.currentTimeMillis() / 1000.0

        fun of(kind: Int, payload: ByteArray = ByteArray(0), isKeyframe: Boolean = false) =
            Message(kind, now(), isKeyframe, payload)

        /** Reads one message; null at a clean end of stream. Throws on a cap overrun or a cut-off message. */
        fun read(input: DataInputStream): Message? {
            val header = ByteArray(HEADER_LENGTH)
            try {
                input.readFully(header)
            } catch (_: EOFException) {
                return null
            }
            val b = ByteBuffer.wrap(header)
            val kind = b.get().toInt() and 0xFF
            val ts = b.getDouble()
            val key = b.get().toInt() == 1
            val len = b.getInt().toLong() and 0xFFFFFFFFL
            val cap = if (kind == Kind.FRAME) MAX_FRAME_PAYLOAD else MAX_OTHER_HOST_PAYLOAD
            if (len > cap) throw IOException("Message of kind $kind announces $len bytes")
            val payload = ByteArray(len.toInt())
            input.readFully(payload)
            return Message(kind, ts, key, payload)
        }
    }
}

/** HEVC VPS/SPS/PPS: nalLen(1) count(1), then per set length(2) bytes. */
class ParameterSets(val nalUnitHeaderLength: Int, val sets: List<ByteArray>) {
    /** The sets with Annex B start codes, as MediaCodec's csd-0 takes them. */
    fun annexB(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (s in sets) {
            out.write(START_CODE)
            out.write(s)
        }
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is ParameterSets && other.nalUnitHeaderLength == nalUnitHeaderLength &&
            other.sets.size == sets.size && sets.indices.all { sets[it].contentEquals(other.sets[it]) }

    override fun hashCode(): Int = sets.fold(nalUnitHeaderLength) { h, s -> h * 31 + s.contentHashCode() }

    companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)

        fun decode(d: ByteArray): ParameterSets? {
            if (d.size < 2) return null
            val nalLen = d[0].toInt() and 0xFF
            val count = d[1].toInt() and 0xFF
            var offset = 2
            val sets = ArrayList<ByteArray>(count)
            repeat(count) {
                if (d.size < offset + 2) return null
                val len = ((d[offset].toInt() and 0xFF) shl 8) or (d[offset + 1].toInt() and 0xFF)
                offset += 2
                if (d.size < offset + len) return null
                sets.add(d.copyOfRange(offset, offset + len))
                offset += len
            }
            return ParameterSets(nalLen, sets)
        }
    }
}

object Hevc {
    /**
     * Rewrites a length-prefixed access unit (hvcC style) to Annex B in place when the prefix is
     * 4 bytes (the normal case: a start code is the same size). Other prefix sizes are copied out.
     * Returns null when the lengths do not add up.
     */
    fun toAnnexB(au: ByteArray, nalLen: Int): ByteArray? {
        if (nalLen == 4) {
            var i = 0
            while (i + 4 <= au.size) {
                val len = ((au[i].toInt() and 0xFF) shl 24) or ((au[i + 1].toInt() and 0xFF) shl 16) or
                    ((au[i + 2].toInt() and 0xFF) shl 8) or (au[i + 3].toInt() and 0xFF)
                if (len < 0 || i + 4 + len > au.size) return null
                au[i] = 0; au[i + 1] = 0; au[i + 2] = 0; au[i + 3] = 1
                i += 4 + len
            }
            return if (i == au.size) au else null
        }
        if (nalLen !in 1..4) return null
        val out = java.io.ByteArrayOutputStream(au.size + 64)
        var i = 0
        while (i + nalLen <= au.size) {
            var len = 0
            for (k in 0 until nalLen) len = (len shl 8) or (au[i + k].toInt() and 0xFF)
            i += nalLen
            if (i + len > au.size) return null
            out.write(ParameterSets.START_CODE)
            out.write(au, i, len)
            i += len
        }
        return if (i == au.size) out.toByteArray() else null
    }

    /** The picture size an HEVC SPS (no start code, emulation bytes included) declares, cropped. */
    fun spsSize(sps: ByteArray): Pair<Int, Int>? = try {
        val r = BitReader(unescape(sps))
        r.skip(16)                       // NAL unit header
        r.skip(4)                        // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3)
        r.skip(1)                        // temporal_id_nesting
        r.skip(88)                       // general profile (space, tier, idc, flags)
        r.skip(8)                        // general_level_idc
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = r.bits(1) == 1
            levelPresent[i] = r.bits(1) == 1
        }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) r.skip(88)
            if (levelPresent[i]) r.skip(8)
        }
        r.ue()                           // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        var w = r.ue()
        var h = r.ue()
        if (r.bits(1) == 1) {
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            w -= subW * (left + right)
            h -= subH * (top + bottom)
        }
        if (w > 0 && h > 0) w to h else null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun unescape(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var zeros = 0
        for (x in b) {
            val v = x.toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(val d: ByteArray) {
        var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = d[pos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte ushr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun skip(n: Int) {
            pos += n
            if (pos > d.size * 8) throw IndexOutOfBoundsException()
        }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) { zeros++; if (zeros > 31) throw IndexOutOfBoundsException() }
            return ((1 shl zeros) - 1) + if (zeros > 0) bits(zeros) else 0
        }
    }
}

object Blobs {
    private fun u16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    /** Kind 3: windowID(4, BE) + JPEG. */
    fun thumbnail(d: ByteArray): Pair<Long, ByteArray>? {
        if (d.size <= 4) return null
        val id = ByteBuffer.wrap(d, 0, 4).int.toLong() and 0xFFFFFFFFL
        return id to d.copyOfRange(4, d.size)
    }

    /** Kind 4: bundleIDLength(2, BE) + bundleID UTF-8 + PNG. */
    fun appIcon(d: ByteArray): Pair<String, ByteArray>? {
        if (d.size < 2) return null
        val len = u16(d, 0)
        if (d.size <= 2 + len) return null
        return String(d, 2, len, Charsets.UTF_8) to d.copyOfRange(2 + len, d.size)
    }

    class CursorShape(val hotX: Float, val hotY: Float, val width: Float, val height: Float, val png: ByteArray)

    /** Kind 14: hotX, hotY, width, height as Float32 BE (Mac points), then PNG. */
    fun cursorShape(d: ByteArray): CursorShape? {
        if (d.size <= 16) return null
        val b = ByteBuffer.wrap(d)
        return CursorShape(b.float, b.float, b.float, b.float, d.copyOfRange(16, d.size))
    }
}
