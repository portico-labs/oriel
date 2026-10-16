package dev.fmagana.oriel.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.fmagana.oriel.protocol.SelfSignedCertificate
import dev.fmagana.oriel.protocol.Spki
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This install's P-256 key and self-signed certificate, which every Mac pins once paired. The key
 * is a software key (TLS needs to sign with it), kept on disk encrypted under an AES key that never
 * leaves the Android Keystore.
 */
class DeviceIdentity(val privateKey: PrivateKey, val certificate: X509Certificate, val fingerprint: ByteArray) {
    companion object {
        private const val FILE = "identity.bin"
        private const val WRAP_ALIAS = "oriel-identity-wrap"

        @Volatile private var cached: DeviceIdentity? = null

        /** Loads the identity, making one the first time. Blocking: call off the main thread. */
        @Synchronized
        fun load(context: Context): DeviceIdentity {
            cached?.let { return it }
            val file = File(context.filesDir, FILE)
            val identity = runCatching { if (file.exists()) read(file) else null }.getOrNull() ?: create(file)
            cached = identity
            return identity
        }

        private fun wrapKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        private fun create(file: File): DeviceIdentity {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val point = Spki.point(kp.public) ?: error("not a P-256 key")
            val der = SelfSignedCertificate.make(kp.private, point)
            val pkcs8 = kp.private.encoded
            val plain = ByteBuffer.allocate(8 + pkcs8.size + der.size)
                .putInt(pkcs8.size).put(pkcs8).putInt(der.size).put(der).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
            val sealed = cipher.doFinal(plain)
            file.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + sealed)
            return read(file)
        }

        private fun read(file: File): DeviceIdentity {
            val bytes = file.readBytes()
            val ivLen = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
            }
            val b = ByteBuffer.wrap(cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen))
            val pkcs8 = ByteArray(b.int).also { b.get(it) }
            val der = ByteArray(b.int).also { b.get(it) }
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            val fp = Spki.fingerprint(cert.publicKey) ?: error("not a P-256 certificate")
            return DeviceIdentity(key, cert, fp)
        }
    }
}
