package dev.fmagana.oriel.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.fmagana.oriel.protocol.HomeDoor
import dev.fmagana.oriel.protocol.RecognitionTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.Executors

/** A Mac's home door, as Bonjour (`_sill._tcp`) describes it. */
data class FoundMac(
    val serviceName: String,
    val host: String,
    val port: Int,
    val door: HomeDoor,
    /** The TXT record's `r`, which a saved Mac's recognition key resolves. */
    val tag: String?,
)

class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
    private var listener: NsdManager.DiscoveryListener? = null

    private val _found = MutableStateFlow<Map<String, FoundMac>>(emptyMap())
    val found: StateFlow<Map<String, FoundMac>> = _found

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = executor.execute { watch(info) }
            override fun onServiceLost(info: NsdServiceInfo) = executor.execute { forget(info.serviceName) }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to start: $errorCode")
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        executor.execute {
            callbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            callbacks.clear()
            _found.value = emptyMap()
        }
    }

    private fun watch(info: NsdServiceInfo) {
        val name = info.serviceName
        if (callbacks.containsKey(name)) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "Resolving $name failed: $errorCode")
            }
            override fun onServiceUpdated(info: NsdServiceInfo) {
                // Prefer IPv4: a link-local IPv6 address needs its scope, which the socket API loses.
                val address = info.hostAddresses.firstOrNull { it is Inet4Address }
                    ?: info.hostAddresses.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress }
                    ?: return
                val txt = info.attributes
                val found = FoundMac(
                    serviceName = name,
                    host = address.hostAddress ?: return,
                    port = info.port,
                    door = HomeDoor.of(txt),
                    tag = txt[RecognitionTag.TXT_KEY]?.toString(Charsets.UTF_8),
                )
                _found.update { it + (name to found) }
            }
            override fun onServiceLost() = executor.execute { forget(name) }
            override fun onServiceInfoCallbackUnregistered() {}
        }
        callbacks[name] = cb
        runCatching { nsd.registerServiceInfoCallback(info, executor, cb) }
            .onFailure { callbacks.remove(name); Log.w(TAG, "Could not resolve $name", it) }
    }

    private fun forget(name: String) {
        callbacks.remove(name)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        _found.update { it - name }
    }

    companion object {
        const val SERVICE_TYPE = "_sill._tcp"
        private const val TAG = "OrielDiscovery"
    }
}
