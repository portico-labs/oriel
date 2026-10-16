package dev.fmagana.oriel.protocol

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class WireTest {
    @Test fun headerRoundTrip() {
        val m = Message(Kind.PING, 1234.5, true, byteArrayOf(1, 2, 3))
        val bytes = m.serialized()
        assertEquals(17, bytes.size)
        assertEquals(Kind.PING, bytes[0].toInt())
        val back = Message.read(DataInputStream(ByteArrayInputStream(bytes)))!!
        assertEquals(Kind.PING, back.kind)
        assertEquals(1234.5, back.timestamp, 0.0)
        assertEquals(true, back.isKeyframe)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.payload)
        assertNull(Message.read(DataInputStream(ByteArrayInputStream(ByteArray(0)))))
    }

    @Test(expected = java.io.IOException::class)
    fun refusesOversizedPayload() {
        val header = Message(200, 0.0, false, ByteArray(0)).serialized()
        header[10] = 0x7F   // announces ~2 GB
        Message.read(DataInputStream(ByteArrayInputStream(header)))
    }

    @Test fun parameterSetsAndAnnexB() {
        val encoded = byteArrayOf(4, 2, 0, 2, 0x40, 0x01, 0, 1, 0x42)
        val ps = ParameterSets.decode(encoded)!!
        assertEquals(4, ps.nalUnitHeaderLength)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x42), ps.annexB())

        val au = byteArrayOf(0, 0, 0, 2, 0x26, 0x01, 0, 0, 0, 1, 0x02)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0, 0, 0, 1, 0x02), Hevc.toAnnexB(au.copyOf(), 4))
        assertNull(Hevc.toAnnexB(byteArrayOf(0, 0, 0, 9, 1), 4))
    }

    /** The shapes Swift's synthesized Codable writes, as upstream's sillclient.py sends them. */
    @Test fun jsonShapes() {
        fun j(b: ByteArray) = JSONObject(String(b))
        assertEquals("{\"window\":{\"_0\":42}}", j(Payloads.selectSource(StreamSource.Window(42))).toString())
        assertEquals("{\"desktop\":{}}", j(Payloads.selectSource(StreamSource.Desktop)).toString())
        val p = j(Payloads.input(InputEvent.Pointer(PointerAction.LEFT_DOWN, 0.25, 0.5))).getJSONObject("pointer")
        assertEquals("leftDown", p.getString("_0"))
        assertEquals(0.25, p.getDouble("x"), 0.0)
        val k = j(Payloads.input(InputEvent.Key(4, true, Modifiers.COMMAND))).getJSONObject("key")
        assertEquals(4, k.getInt("hidUsage"))
        assertEquals(1L shl 20, k.getLong("modifiers"))
        assertEquals("ab", j(Payloads.input(InputEvent.Text("ab"))).getJSONObject("text").getString("_0"))
        assertEquals("began", j(Payloads.input(InputEvent.ScrollGesture(ScrollPhase.BEGAN, 0.5, 0.5))).getJSONObject("scrollGesture").getString("_0"))
    }

    @Test fun windowList() {
        val json = """{"macName":"Mac mini","windows":[{"id":7,"title":"Notes","appName":"Notes","bundleID":"com.apple.Notes","width":800,"height":600}],"active":{"window":{"_0":7}},"hostVersion":"0.6"}"""
        val wl = WindowList.parse(json.toByteArray())!!
        assertEquals("Mac mini", wl.macName)
        assertEquals(StreamSource.Window(7), wl.active)
        assertEquals("Notes", wl.windows.single().title)
        assertEquals(StreamSource.None, WindowList.parse("""{"macName":"M","windows":[],"active":{"none":{}}}""".toByteArray())!!.active)
    }
}
