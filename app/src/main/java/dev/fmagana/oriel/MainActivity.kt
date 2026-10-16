package dev.fmagana.oriel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fmagana.oriel.ui.MacsScreen
import dev.fmagana.oriel.ui.PairScreen
import dev.fmagana.oriel.ui.StreamScreen
import dev.fmagana.oriel.ui.theme.OrielTheme

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.stopDiscovery()
        model.startDiscovery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 17 asks before an app reaches devices on the local network (Bonjour included).
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            askLocalNetwork.launch(LOCAL_NETWORK)
        }
        setContent {
            OrielTheme {
                val screen by model.screen.collectAsStateWithLifecycle()
                BackHandler(enabled = screen != Screen.Macs) { model.back() }
                when (val s = screen) {
                    Screen.Macs -> MacsScreen(model)
                    is Screen.Pair -> PairScreen(model, s.row)
                    Screen.Stream -> StreamScreen(model)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        model.stopDiscovery()
    }

    companion object {
        @Suppress("InlinedApi")
        private const val LOCAL_NETWORK = Manifest.permission.ACCESS_LOCAL_NETWORK
    }
}
