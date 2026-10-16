package dev.fmagana.oriel.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fmagana.oriel.AppViewModel
import dev.fmagana.oriel.protocol.StreamSource
import dev.fmagana.oriel.protocol.WindowInfo
import dev.fmagana.oriel.session.Session
import dev.fmagana.oriel.session.SessionState
import dev.fmagana.oriel.session.StreamView
import kotlin.math.roundToInt

@Composable
fun StreamScreen(model: AppViewModel) {
    val session by model.session.collectAsStateWithLifecycle()
    val s = session ?: return
    val state by s.state.collectAsStateWithLifecycle()
    var keyboard by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(false) }
    var streamView by remember { mutableStateOf<StreamView?>(null) }

    ImmersiveWhileShown()

    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout).imePadding()) {
        Bar(
            state = state,
            onBack = { model.disconnect() },
            onSelect = { s.select(it) },
            onApps = { apps = true },
            keyboard = keyboard,
            onKeyboard = { keyboard = !keyboard; streamView?.showKeyboard(keyboard) },
        )
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val refresh = LocalView.current.display?.refreshRate?.roundToInt()?.coerceIn(30, 120) ?: 60
            DisposableEffect(maxWidth, maxHeight, refresh) {
                s.setViewport(maxWidth.value.toDouble(), maxHeight.value.toDouble(), refresh)
                onDispose {}
            }
            val aspect = state.videoSize?.let { (w, h) -> w.toFloat() / h } ?: (16f / 10f)
            AndroidView(
                factory = { ctx ->
                    StreamView(ctx).apply {
                        send = { s.input(it) }
                        onSurface = { s.setSurface(it) }
                        streamView = this
                    }
                },
                modifier = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = maxWidth / maxHeight > aspect),
            )
            if (state.phase == SessionState.Phase.CONNECTING || state.videoSize == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (state.phase == SessionState.Phase.CONNECTING) "Connecting to ${state.macName}…" else "Waiting for the picture…",
                        color = Color.White,
                    )
                }
            }
        }
        StatusLine(state)
    }

    if (apps) AppsDialog(s, state, onDismiss = { apps = false })
}

@Composable
private fun Bar(
    state: SessionState,
    onBack: () -> Unit,
    onSelect: (StreamSource) -> Unit,
    onApps: () -> Unit,
    keyboard: Boolean,
    onKeyboard: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1C1C1E)).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Disconnect", tint = Color.White) }
        IconButton(onClick = onApps) { Icon(Icons.Filled.Apps, "Apps", tint = Color.White) }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.windows, key = { it.id }) { w ->
                WindowChip(w, state, selected = state.active == StreamSource.Window(w.id)) { onSelect(StreamSource.Window(w.id)) }
            }
        }
        IconButton(onClick = { onSelect(StreamSource.Desktop) }) {
            Icon(
                Icons.Filled.DesktopMac, "Desktop",
                tint = if (state.active == StreamSource.Desktop) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
        IconButton(onClick = onKeyboard) {
            Icon(Icons.Filled.Keyboard, "Keyboard", tint = if (keyboard) MaterialTheme.colorScheme.primary else Color.White)
        }
    }
}

@Composable
private fun WindowChip(w: WindowInfo, state: SessionState, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(96.dp).clip(shape)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(88.dp, 52.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2C2C2E))) {
            state.thumbnails[w.id]?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            state.icons[w.bundleID]?.let { Image(it, null, Modifier.size(20.dp).align(Alignment.BottomEnd)) }
        }
        Text(
            w.title.ifEmpty { w.appName }, color = Color.White, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusLine(state: SessionState) {
    val parts = buildList {
        add(state.macName)
        state.stream?.let { add("${it.width}×${it.height} @ ${it.fps} fps, ${it.mbps} Mbps") }
        add("${state.fps} fps shown")
        state.rttMs?.let { add("$it ms round trip") }
    }
    Text(
        parts.joinToString(" · "),
        color = Color(0xFF8E8E93), style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1C1C1E)).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun AppsDialog(s: Session, state: SessionState, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = state.apps.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open an app on ${state.macName}") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (state.apps.isEmpty()) Text("The Mac has not sent its app list yet.")
                LazyColumn(Modifier.height(360.dp)) {
                    items(shown, key = { it.bundleID }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { s.launch(app); onDismiss() }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            state.icons[app.bundleID]?.let { Image(it, null, Modifier.size(28.dp)) } ?: Spacer(Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(app.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Hides the status and navigation bars while the stream shows; a swipe from the edge brings them back. */
@Composable
private fun ImmersiveWhileShown() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
