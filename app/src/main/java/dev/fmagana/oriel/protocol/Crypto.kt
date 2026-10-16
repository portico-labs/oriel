package dev.fmagana.oriel.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Ports of Sources/StreamProtocol/RemoteIdentity.swift and Pairing.swift. */

object Base64Url {
    fun encode(d: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(d)

    fun decode(text: String): ByteArray? {
        if (!text.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } || text.length % 4 == 1) return null
        return runCatching { java.util.Base64.getUrlDecoder().decode(text) }.getOrNull()?.takeIf { encode(it) == text }
    }
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    parts.forEach { mac.update(it) }
    return mac.doFinal()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Trust is the key itself, pinned as SHA-256 of its SubjectPublicKeyInfo. */
object Spki {
    /** The 65-byte uncompressed X9.63 point of a P-256 key; null for any other key. */
    fun point(key: java.security.PublicKey): ByteArray? {
        val ec = key as? ECPublicKey ?: return null
        if (ec.params.curve.field.fieldSize != 256) return null
        fun fixed(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return when {
                b.size == 32 -> b
                b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
                b.size < 32 -> ByteArray(32 - b.size) + b
                else -> return ByteArray(0)
            }
        }
        val x = fixed(ec.w.affineX)
        val y = fixed(ec.w.affineY)
        if (x.size != 32 || y.size != 32) return null
        return byteArrayOf(4) + x + y
    }

    fun der(point: ByteArray): ByteArray =
        Der.sequence(Der.sequence(Der.oid(Der.EC_PUBLIC_KEY), Der.oid(Der.PRIME256V1)), Der.bitString(point))

    fun fingerprint(point: ByteArray): ByteArray = sha256(der(point))

    fun fingerprint(key: java.security.PublicKey): ByteArray? = point(key)?.let { fingerprint(it) }
}

/** 16 Crockford base32 characters of fp[0..<10]. */
object MacId {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun make(fingerprint: ByteArray): String {
        if (fingerprint.size < 10) return ""
        val n = BigInteger(1, fingerprint.copyOfRange(0, 10))
        return (0 until 16).map { i -> ALPHABET[n.shiftRight(75 - 5 * i).toInt() and 31] }.joinToString("")
    }
}

/** The 12-digit typed code: 11 digits and a Damm check digit. */
object PairingCode {
    private val TABLE = arrayOf(
        intArrayOf(0, 3, 1, 7, 5, 9, 8, 6, 4, 2), intArrayOf(7, 0, 9, 2, 1, 5, 4, 8, 6, 3),
        intArrayOf(4, 2, 0, 6, 8, 7, 1, 3, 5, 9), intArrayOf(1, 7, 5, 0, 9, 8, 3, 4, 2, 6),
        intArrayOf(6, 1, 2, 3, 0, 4, 5, 9, 7, 8), intArrayOf(3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
        intArrayOf(5, 8, 6, 9, 7, 2, 0, 1, 3, 4), intArrayOf(8, 9, 4, 5, 3, 6, 2, 0, 1, 7),
        intArrayOf(9, 4, 3, 8, 6, 1, 7, 2, 0, 5), intArrayOf(2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
    )
    const val LENGTH = 12

    fun checkDigit(digits: List<Int>): Int = digits.fold(0) { i, d -> TABLE[i][d] }

    sealed interface Check {
        data class Ok(val code: String) : Check
        data object Length : Check
        data object Typo : Check
    }

    fun check(input: String): Check {
        val kept = input.filter { it != ' ' && it != '-' && it != ' ' }
        if (kept.length != LENGTH || !kept.all { it in '0'..'9' }) return Check.Length
        if (checkDigit(kept.map { it - '0' }) != 0) return Check.Typo
        return Check.Ok(kept)
    }
}

object PairingProof {
    const val ROUNDS = 600_000
    private const val LABEL = "sill-pair-v1"

    /** K = PBKDF2-HMAC-SHA256(code, "sill-pair-v1" ‖ fp_mac, 600,000 rounds, 32 bytes). */
    fun codeKey(code: String, macFingerprint: ByteArray): ByteArray {
        require(code.length == PairingCode.LENGTH && code.all { it in '0'..'9' })
        val salt = LABEL.toByteArray() + macFingerprint
        val spec = PBEKeySpec(code.toCharArray(), salt, ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun deviceProof(key: ByteArray, deviceFingerprint: ByteArray, macFingerprint: ByteArray): ByteArray =
        hmacSha256(key, "$LABEL device".toByteArray(), byteArrayOf(0), deviceFingerprint, macFingerprint)

    fun macProof(key: ByteArray, macFingerprint: ByteArray, deviceFingerprint: ByteArray): ByteArray =
        hmacSha256(key, "$LABEL mac".toByteArray(), byteArrayOf(0), macFingerprint, deviceFingerprint)

    fun isValidMacProof(proof: ByteArray, key: ByteArray, macFingerprint: ByteArray, deviceFingerprint: ByteArray): Boolean =
        MessageDigest.isEqual(proof, macProof(key, macFingerprint, deviceFingerprint))
}

/** The Bonjour TXT record's `r`: recognises a saved Mac whatever its Bonjour name. */
object RecognitionTag {
    const val TXT_KEY = "r"
    private const val LABEL = "sill-tag-v1"

    fun make(recognitionKey: ByteArray, nonce: ByteArray): String =
        Base64Url.encode(nonce + hmacSha256(recognitionKey, LABEL.toByteArray(), nonce).copyOfRange(0, 6))

    fun matches(tag: String, recognitionKey: ByteArray): Boolean {
        val bytes = Base64Url.decode(tag) ?: return false
        if (bytes.size != 12) return false
        val expected = hmacSha256(recognitionKey, LABEL.toByteArray(), bytes.copyOfRange(0, 6)).copyOfRange(0, 6)
        return MessageDigest.isEqual(expected, bytes.copyOfRange(6, 12))
    }
}

/** The Bonjour TXT record's `p`: how the home door speaks. */
enum class HomeDoor {
    /** No `p`: plain TCP, no pairing (a development host). */
    PLAIN,
    /** `p=1` (or anything but "0"): TLS, paired devices only. */
    PAIRING_REQUIRED,
    /** `p=0`: TLS, any device. */
    OPEN;

    companion object {
        fun of(txt: Map<String, ByteArray?>): HomeDoor {
            val key = txt.keys.firstOrNull { it.equals("p", ignoreCase = true) } ?: return PLAIN
            val v = txt[key]?.toString(Charsets.UTF_8)
            return if (v == "0") OPEN else PAIRING_REQUIRED
        }
    }
}

object Der {
    val EC_PUBLIC_KEY = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)
    val PRIME256V1 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)
    val ECDSA_WITH_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    private fun length(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bytes = ArrayList<Byte>()
        var v = n
        while (v > 0) { bytes.add(0, (v and 0xFF).toByte()); v = v ushr 8 }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    fun tlv(tag: Int, content: ByteArray) = byteArrayOf(tag.toByte()) + length(content.size) + content
    fun sequence(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun oid(bytes: ByteArray) = tlv(0x06, bytes)
    fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)
    fun integer(raw: ByteArray): ByteArray {
        var b = if (raw.isEmpty()) byteArrayOf(0) else raw
        while (b.size > 1 && b[0] == 0.toByte() && (b[1].toInt() and 0x80) == 0) b = b.copyOfRange(1, b.size)
        if ((b[0].toInt() and 0x80) != 0) b = byteArrayOf(0) + b
        return tlv(0x02, b)
    }
}

/** X.509 v3, no extensions, random CN and serial: the certificate Sill's own builds present. */
object SelfSignedCertificate {
    fun make(privateKey: PrivateKey, point: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val cn = ByteArray(8).also { random.nextBytes(it) }
        val serial = ByteArray(16).also { random.nextBytes(it) }
        serial[0] = ((serial[0].toInt() and 0x7F) or 0x01).toByte()
        val name = Der.sequence(Der.set(Der.sequence(Der.oid(Der.COMMON_NAME), Der.tlv(0x0C, cn.toHex().toByteArray()))))
        val algorithm = Der.sequence(Der.oid(Der.ECDSA_WITH_SHA256))
        val tbs = Der.sequence(
            Der.tlv(0xA0, Der.integer(byteArrayOf(2))),
            Der.integer(serial),
            algorithm,
            name,
            Der.sequence(Der.tlv(0x17, "260101000000Z".toByteArray()), Der.tlv(0x18, "99991231235959Z".toByteArray())),
            name,
            Spki.der(point),
        )
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }
        return Der.sequence(tbs, algorithm, Der.bitString(sig))
    }
}
