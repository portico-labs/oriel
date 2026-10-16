package dev.fmagana.oriel.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/** The vectors are upstream's (docs/remote-access-plan.md §3.4–3.6), computed there in Python. */
class CryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val fpMac = sha256("mac".toByteArray())
    private val fpDevice = sha256("device".toByteArray())

    @Test fun fingerprintsAreTheStandIns() {
        assertEquals("348a629f5ceed032c3e8706ec47d9bfafb00fb4250b018dd965435ca50cb836e", fpMac.toHex())
        assertEquals("263a4dbe41488fb87214b0032339dbb9f0c8da14c16dfcf13084bf3c2552eca5", fpDevice.toHex())
    }

    @Test fun macId() {
        assertEquals("A3C5HR4RBV67YR21", MacId.make(sha256("example".toByteArray())))
    }

    @Test fun recognitionTag() {
        val key = ByteArray(32) { 0x11 }
        val tag = RecognitionTag.make(key, hex("a1a2a3a4a5a6"))
        assertEquals("oaKjpKWmL4V47Ctm", tag)
        assertTrue(RecognitionTag.matches(tag, key))
        assertFalse(RecognitionTag.matches(tag, ByteArray(32) { 0x12 }))
    }

    @Test fun typedCodePath() {
        val code = "482913557208"
        assertEquals(PairingCode.Check.Ok(code), PairingCode.check("4829 1355 7208"))
        val k = PairingProof.codeKey(code, fpMac)
        assertEquals("e9baf879a1cc4ac8b21554df480b6c1621671319107c91e7cb05d154f649d31d", k.toHex())
        assertEquals("dk7PZj4vHsAxj--1u9Vg7ehSn8a1tWc0RCG46YpFVO8", Base64Url.encode(PairingProof.deviceProof(k, fpDevice, fpMac)))
        val macProof = Base64Url.decode("6BmGWC9XT8J8OY8phhq9bASgoSnoWpRxBeqZtvRrWmk")!!
        assertTrue(PairingProof.isValidMacProof(macProof, k, fpMac, fpDevice))
        assertFalse(PairingProof.isValidMacProof(macProof, k, fpDevice, fpMac))
    }

    @Test fun qrPath() {
        val secret = ByteArray(16) { 0x22 }
        assertEquals("AyZAJqNTU5nSKEaHx6RZOf0K5dOeygR_8JSrCV_gNOo", Base64Url.encode(PairingProof.deviceProof(secret, fpDevice, fpMac)))
        assertEquals("XAX-uZPsq_Ax8fvI_sV7Ex0a-ROi19rI-FW-ykPh9TE", Base64Url.encode(PairingProof.macProof(secret, fpMac, fpDevice)))
    }

    @Test fun codeChecks() {
        assertEquals(PairingCode.Check.Length, PairingCode.check("12345"))
        assertEquals(PairingCode.Check.Typo, PairingCode.check("482913557207"))
        assertEquals(PairingCode.Check.Typo, PairingCode.check("482913557280"))   // adjacent transposition
    }

    @Test fun base64Url() {
        assertArrayEquals(byteArrayOf(-5, -1), Base64Url.decode("-_8"))
        assertEquals(null, Base64Url.decode("abc="))
        assertEquals(null, Base64Url.decode("a"))
    }

    @Test fun selfSignedCertificateParsesAndCarriesTheKey() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val point = Spki.point(kp.public)!!
        val der = SelfSignedCertificate.make(kp.private, point)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(kp.public)
        assertArrayEquals(Spki.fingerprint(point), Spki.fingerprint(cert.publicKey))
        assertArrayEquals(sha256(kp.public.encoded), Spki.fingerprint(point))
    }

    @Test fun homeDoor() {
        assertEquals(HomeDoor.PLAIN, HomeDoor.of(mapOf("r" to "x".toByteArray())))
        assertEquals(HomeDoor.OPEN, HomeDoor.of(mapOf("p" to "0".toByteArray())))
        assertEquals(HomeDoor.PAIRING_REQUIRED, HomeDoor.of(mapOf("p" to "1".toByteArray())))
        assertEquals(HomeDoor.PAIRING_REQUIRED, HomeDoor.of(mapOf("P" to null)))
    }
}
