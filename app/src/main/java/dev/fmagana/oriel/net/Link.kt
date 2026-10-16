package dev.fmagana.oriel.net

import dev.fmagana.oriel.protocol.Message
import dev.fmagana.oriel.protocol.Spki
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** One TCP connection to a Mac, plain or TLS, carrying Sill's framed messages. */
class Link private constructor(private val socket: Socket, val peerFingerprint: ByteArray?) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
    private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

    fun read(): Message? = Message.read(input)

    fun send(m: Message) = synchronized(output) {
        output.write(m.serialized())
        output.flush()
    }

    fun setReadTimeout(ms: Int) { socket.soTimeout = ms }

    fun close() = runCatching { socket.close() }

    companion object {
        /** A session (catalog, frames, input): paired devices only, or any device at an open door. */
        const val SESSION_ALPN = "sill/1"
        /** Pairing: one kind 19 out, one kind 20 back. */
        const val PAIRING_ALPN = "sill-pair/1"
        private const val CONNECT_TIMEOUT_MS = 10_000

        private fun tcp(host: String, port: Int): Socket = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }

        /** A development host's plain door: no TLS, no pairing. */
        fun plain(host: String, port: Int): Link = Link(tcp(host, port), null)

        /**
         * TLS 1.3 with this device's certificate. `pin` is the Mac's SPKI fingerprint: any other key
         * fails the handshake before this device sends its own certificate. Null accepts any P-256
         * key (the typed-code pairing, whose proofs bind both keys, and an open door).
         */
        fun tls(host: String, port: Int, alpn: String, identity: DeviceIdentity, pin: ByteArray?): Link {
            var seen: ByteArray? = null
            val trust = object : X509ExtendedTrustManager() {
                fun check(chain: Array<out X509Certificate>?) {
                    val fp = chain?.firstOrNull()?.publicKey?.let { Spki.fingerprint(it) }
                        ?: throw CertificateException("The Mac's key is not P-256")
                    if (pin != null && !MessageDigest.isEqual(fp, pin)) throw PinMismatchException()
                    seen = fp
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val keys = object : X509ExtendedKeyManager() {
                override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "device"
                override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = "device"
                override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
                override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("device")
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            }
            val context = SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keys), arrayOf(trust), SecureRandom()) }
            val raw = tcp(host, port)
            try {
                // "sill" is the SNI every client sends, so a MagicDNS name never crosses the network in clear.
                val ssl = context.socketFactory.createSocket(raw, "sill", port, true) as SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply {
                    protocols = arrayOf("TLSv1.3")
                    applicationProtocols = arrayOf(alpn)
                    serverNames = listOf(SNIHostName("sill"))
                    endpointIdentificationAlgorithm = null
                }
                ssl.soTimeout = CONNECT_TIMEOUT_MS
                ssl.startHandshake()
                if (ssl.applicationProtocol != alpn) {
                    ssl.close()
                    throw IOException("The Mac does not speak $alpn")
                }
                ssl.soTimeout = 0
                return Link(ssl, seen ?: ssl.session.peerCertificates.firstOrNull()?.publicKey?.let { Spki.fingerprint(it) })
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw e
            }
        }
    }
}

class PinMismatchException : CertificateException("This Mac's key is not the one paired with")
