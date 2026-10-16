package dev.fmagana.oriel.net

import android.content.Context
import dev.fmagana.oriel.protocol.Base64Url
import dev.fmagana.oriel.protocol.Kind
import dev.fmagana.oriel.protocol.MacId
import dev.fmagana.oriel.protocol.Message
import dev.fmagana.oriel.protocol.PairResult
import dev.fmagana.oriel.protocol.PairingProof
import dev.fmagana.oriel.protocol.Payloads
import dev.fmagana.oriel.protocol.RecognitionTag
import org.json.JSONArray
import org.json.JSONObject

/** A Mac this device paired with: its pinned key, and where it was last reached. */
data class SavedMac(
    val macID: String,
    val name: String,
    val fingerprint: ByteArray,
    val recognitionKey: ByteArray?,
    val host: String,
    val port: Int,
) {
    /** Whether a Bonjour TXT record's `r` was made by this Mac. */
    fun recognises(tag: String?): Boolean = tag != null && recognitionKey != null && RecognitionTag.matches(tag, recognitionKey)

    fun toJson(): JSONObject = JSONObject().put("macID", macID).put("name", name).put("fingerprint", Base64Url.encode(fingerprint))
        .put("recognitionKey", recognitionKey?.let { Base64Url.encode(it) }).put("host", host).put("port", port)

    override fun equals(other: Any?) = other is SavedMac && other.macID == macID && other.host == host && other.port == port && other.name == name
    override fun hashCode() = macID.hashCode()

    companion object {
        fun fromJson(o: JSONObject): SavedMac? = runCatching {
            SavedMac(
                o.getString("macID"), o.getString("name"), Base64Url.decode(o.getString("fingerprint"))!!,
                o.optString("recognitionKey").ifEmpty { null }?.let { Base64Url.decode(it) },
                o.getString("host"), o.getInt("port"),
            )
        }.getOrNull()
    }
}

class SavedMacStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved-macs", Context.MODE_PRIVATE)

    fun all(): List<SavedMac> = runCatching {
        val arr = JSONArray(prefs.getString("macs", "[]"))
        (0 until arr.length()).mapNotNull { SavedMac.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(mac: SavedMac) = write(all().filter { it.macID != mac.macID } + mac)

    fun forget(macID: String) = write(all().filter { it.macID != macID })

    /** Addresses typed in by hand: host, port and whether the door is encrypted. */
    fun addresses(): List<Triple<String, Int, Boolean>> = runCatching {
        val arr = JSONArray(prefs.getString("addresses", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("host"), o.getInt("port"), o.getBoolean("tls"))
        }
    }.getOrDefault(emptyList())

    fun saveAddresses(list: List<Triple<String, Int, Boolean>>) {
        val arr = JSONArray()
        list.forEach { (h, p, t) -> arr.put(JSONObject().put("host", h).put("port", p).put("tls", t)) }
        prefs.edit().putString("addresses", arr.toString()).apply()
    }

    private fun write(list: List<SavedMac>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("macs", arr.toString()).apply()
    }
}

/** Pairing at the home door (docs/home-pairing-plan.md upstream): ask for a code, then prove it. */
class Pairing(private val identity: DeviceIdentity, private val deviceName: String, private val model: String) {

    sealed interface AskOutcome {
        /** The Mac shows a code now; type it. The Mac's key as this connection saw it. */
        data class Shown(val macFingerprint: ByteArray) : AskOutcome
        /** The Mac showed none by itself: choose Pair iPhone or iPad… in Sill's menu on the Mac. */
        data class OpenOnMac(val macFingerprint: ByteArray) : AskOutcome
        data class Refused(val words: String) : AskOutcome
    }

    sealed interface PairOutcome {
        data class Paired(val mac: SavedMac) : PairOutcome
        data class Refused(val words: String) : PairOutcome
    }

    /** One pairing connection: kind 19 out, kind 20 back. */
    private fun exchange(link: Link, method: String, proof: String): PairResult? = try {
        link.send(Message.of(Kind.PAIR_REQUEST, Payloads.pairRequest(method, proof, deviceName, model)))
        link.setReadTimeout(15_000)
        val reply = generateSequence { link.read() }.firstOrNull { it.kind == Kind.PAIR_RESULT }
        reply?.let { PairResult.parse(it.payload) }
    } finally {
        link.close()
    }

    fun ask(host: String, port: Int): AskOutcome {
        val link = Link.tls(host, port, Link.PAIRING_ALPN, identity, null)
        val fp = link.peerFingerprint ?: return AskOutcome.Refused("The Mac presented no key.")
        val r = exchange(link, "ask", "") ?: return AskOutcome.Refused("The Mac did not answer.")
        return when {
            r.ok -> AskOutcome.Refused("The Mac paired without a code, which Oriel does not accept.")
            r.reason == "shown" -> AskOutcome.Shown(fp)
            r.reason == "openOnMac" -> AskOutcome.OpenOnMac(fp)
            else -> AskOutcome.Refused(words(r))
        }
    }

    /**
     * Proves the typed code. `pin` is the key the ask saw, when there was one; without it any key is
     * taken, since the proofs bind both keys and a relay's would not check.
     */
    fun pairWithCode(host: String, port: Int, code: String, pin: ByteArray?): PairOutcome {
        var key = pin?.let { PairingProof.codeKey(code, it) }
        val link = Link.tls(host, port, Link.PAIRING_ALPN, identity, pin)
        val fpMac = link.peerFingerprint ?: run { link.close(); return PairOutcome.Refused("The Mac presented no key.") }
        if (key == null) key = PairingProof.codeKey(code, fpMac)
        val proof = PairingProof.deviceProof(key, identity.fingerprint, fpMac)
        val r = exchange(link, "code", Base64Url.encode(proof)) ?: return PairOutcome.Refused("The Mac did not answer.")
        if (!r.ok) return PairOutcome.Refused(words(r))
        val macProof = r.proof?.let { Base64Url.decode(it) }
        if (macProof == null || !PairingProof.isValidMacProof(macProof, key, fpMac, identity.fingerprint)) {
            return PairOutcome.Refused("The Mac's proof did not check. Nothing was saved.")
        }
        if (r.macID != MacId.make(fpMac)) return PairOutcome.Refused("The Mac named another key. Nothing was saved.")
        val name = r.name?.takeIf { it.isNotBlank() } ?: "Mac"
        return PairOutcome.Paired(
            SavedMac(r.macID, name, fpMac, r.recognitionKey?.let { Base64Url.decode(it) }?.takeIf { it.size == 32 }, host, port),
        )
    }

    private fun words(r: PairResult): String = when (r.reason) {
        "code" -> "That code is not the one on the Mac." + (r.triesLeft?.let { " $it tries left." } ?: "")
        "closed" -> "The Mac is not pairing right now. Choose Pair iPhone or iPad… in Sill's menu on the Mac."
        "expired" -> "The code expired. Ask the Mac for a new one."
        "stopped" -> "Pairing was stopped on the Mac."
        "busy" -> "The Mac is busy. Try again in a moment."
        "locked" -> "The Mac is locked. Unlock it, then try again."
        else -> r.message ?: "The Mac refused to pair (${r.reason ?: "no reason"})."
    }
}
