package dev.fmagana.oriel

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.fmagana.oriel.net.DeviceIdentity
import dev.fmagana.oriel.net.Discovery
import dev.fmagana.oriel.net.FoundMac
import dev.fmagana.oriel.net.Pairing
import dev.fmagana.oriel.net.SavedMac
import dev.fmagana.oriel.net.SavedMacStore
import dev.fmagana.oriel.protocol.HomeDoor
import dev.fmagana.oriel.protocol.PairingCode
import dev.fmagana.oriel.protocol.Payloads
import dev.fmagana.oriel.session.Session
import dev.fmagana.oriel.session.SessionState
import dev.fmagana.oriel.session.SessionTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the Macs list: a Mac on this network, a saved one, or an address typed in. */
data class MacRow(
    val key: String,
    val name: String,
    val host: String,
    val port: Int,
    val door: HomeDoor,
    val saved: SavedMac?,
    val nearby: Boolean,
) {
    val subtitle: String
        get() = when {
            saved != null && nearby -> "Paired · $host"
            saved != null -> "Paired · last at $host:$port"
            door == HomeDoor.PAIRING_REQUIRED -> "Pair to connect · $host"
            door == HomeDoor.OPEN -> "Encrypted, no pairing needed · $host"
            else -> "Development host, not encrypted · $host:$port"
        }
}

sealed interface Screen {
    data object Macs : Screen
    data class Pair(val row: MacRow) : Screen
    data object Stream : Screen
}

data class PairUi(
    val asking: Boolean = false,
    val pairing: Boolean = false,
    /** What the Mac said, or what went wrong. */
    val note: String? = null,
    val codeShown: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SavedMacStore(app)
    private val discovery = Discovery(app)

    private val saved = MutableStateFlow(store.all())
    private val manual = MutableStateFlow(store.addresses().map { (h, p, t) -> typed(h, p, t) })

    private fun typed(host: String, port: Int, tls: Boolean) =
        FoundMac(host, host, port, if (tls) HomeDoor.PAIRING_REQUIRED else HomeDoor.PLAIN, null)

    private fun persistAddresses() =
        store.saveAddresses(manual.value.map { Triple(it.host, it.port, it.door != HomeDoor.PLAIN) })

    val screen = MutableStateFlow<Screen>(Screen.Macs)
    val pairUi = MutableStateFlow(PairUi())
    val session = MutableStateFlow<Session?>(null)
    /** Why the last session ended, shown on the Macs list. */
    val lastEnded = MutableStateFlow<String?>(null)

    /** The Mac's key a pairing ask saw, so the code is proved to the same Mac. */
    private var askedFingerprint: ByteArray? = null

    val rows: StateFlow<List<MacRow>> = combine(discovery.found, saved, manual) { found, savedMacs, typed ->
        val rows = ArrayList<MacRow>()
        val matched = HashSet<String>()
        for (f in found.values.sortedBy { it.serviceName } + typed) {
            val mac = savedMacs.firstOrNull { it.recognises(f.tag) }
                ?: savedMacs.firstOrNull { it.host == f.host && it.port == f.port }
            mac?.let { matched += it.macID }
            rows += MacRow(
                key = "found:${f.serviceName}:${f.host}:${f.port}", name = mac?.name ?: f.serviceName,
                host = f.host, port = f.port, door = f.door, saved = mac, nearby = true,
            )
        }
        for (mac in savedMacs) if (mac.macID !in matched) {
            rows += MacRow("saved:${mac.macID}", mac.name, mac.host, mac.port, HomeDoor.PAIRING_REQUIRED, mac, nearby = false)
        }
        rows
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val deviceName: String = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    private val deviceLabel = "$deviceName (${Build.MANUFACTURER} ${Build.MODEL}, Android)"
    private val appVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "0"

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    fun addAddress(host: String, port: Int, encrypted: Boolean) {
        manual.update { list -> list.filterNot { it.host == host && it.port == port } + typed(host, port, encrypted) }
        persistAddresses()
    }

    fun forget(row: MacRow) {
        row.saved?.let { store.forget(it.macID) }
        saved.value = store.all()
        manual.update { list -> list.filterNot { it.host == row.host && it.port == row.port } }
        persistAddresses()
    }

    fun open(row: MacRow) {
        lastEnded.value = null
        when {
            row.saved != null -> connect(SessionTarget(row.saved.name, row.host, row.port, tls = true, pin = row.saved.fingerprint))
            row.door == HomeDoor.PAIRING_REQUIRED -> {
                pairUi.value = PairUi()
                askedFingerprint = null
                screen.value = Screen.Pair(row)
                ask(row)
            }
            row.door == HomeDoor.OPEN -> connect(SessionTarget(row.name, row.host, row.port, tls = true, pin = null))
            else -> connect(SessionTarget(row.name, row.host, row.port, tls = false, pin = null))
        }
    }

    /** Asks the Mac to show a pairing code. */
    fun ask(row: MacRow) {
        pairUi.value = PairUi(asking = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { pairing().ask(row.host, row.port) }
            }
            outcome.fold(
                onSuccess = { o ->
                    when (o) {
                        is Pairing.AskOutcome.Shown -> {
                            askedFingerprint = o.macFingerprint
                            pairUi.value = PairUi(codeShown = true, note = "Type the code ${row.name} shows.")
                        }
                        is Pairing.AskOutcome.OpenOnMac -> {
                            askedFingerprint = o.macFingerprint
                            pairUi.value = PairUi(
                                codeShown = true,
                                note = "On ${row.name}, choose Pair iPhone or iPad… in Sill's menu, then type the code it shows.",
                            )
                        }
                        is Pairing.AskOutcome.Refused -> pairUi.value = PairUi(codeShown = true, note = o.words)
                    }
                },
                onFailure = { e ->
                    pairUi.value = PairUi(codeShown = true, note = "Could not reach ${row.name}: ${e.message}")
                },
            )
        }
    }

    fun pairWithCode(row: MacRow, input: String) {
        val code = when (val c = PairingCode.check(input)) {
            is PairingCode.Check.Ok -> c.code
            PairingCode.Check.Length -> { pairUi.update { it.copy(note = "A code has 12 digits.") }; return }
            PairingCode.Check.Typo -> { pairUi.update { it.copy(note = "That code has a typo. Check it against your Mac.") }; return }
        }
        pairUi.update { it.copy(pairing = true, note = "Pairing…") }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { pairing().pairWithCode(row.host, row.port, code, askedFingerprint) }
            }
            outcome.fold(
                onSuccess = { o ->
                    when (o) {
                        is Pairing.PairOutcome.Paired -> {
                            store.save(o.mac)
                            saved.value = store.all()
                            connect(SessionTarget(o.mac.name, row.host, row.port, tls = true, pin = o.mac.fingerprint))
                        }
                        is Pairing.PairOutcome.Refused -> pairUi.update { it.copy(pairing = false, note = o.words) }
                    }
                },
                onFailure = { e -> pairUi.update { it.copy(pairing = false, note = "Pairing failed: ${e.message}") } },
            )
        }
    }

    private fun pairing() = Pairing(DeviceIdentity.load(getApplication()), deviceName, "${Build.MANUFACTURER} ${Build.MODEL}")

    private fun connect(target: SessionTarget) {
        session.value?.close()
        viewModelScope.launch {
            val identity = withContext(Dispatchers.IO) { DeviceIdentity.load(getApplication()) }
            val hello = Payloads.hello(appVersion, appVersion, deviceLabel)
            val s = Session(target, identity, hello, deviceLabel)
            session.value = s
            screen.value = Screen.Stream
            s.start()
            val end = s.state.first { it.phase == SessionState.Phase.ENDED }
            if (session.value === s) {
                lastEnded.value = end.ended
                session.value = null
                screen.value = Screen.Macs
            }
        }
    }

    fun disconnect() {
        session.value?.close()
    }

    fun back() {
        when (screen.value) {
            is Screen.Pair -> screen.value = Screen.Macs
            Screen.Stream -> disconnect()
            Screen.Macs -> Unit
        }
    }

    override fun onCleared() {
        session.value?.close()
        discovery.stop()
    }
}
