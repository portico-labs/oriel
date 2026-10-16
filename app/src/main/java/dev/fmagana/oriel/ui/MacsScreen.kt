package dev.fmagana.oriel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fmagana.oriel.AppViewModel
import dev.fmagana.oriel.MacRow
import dev.fmagana.oriel.protocol.HomeDoor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacsScreen(model: AppViewModel) {
    val rows by model.rows.collectAsStateWithLifecycle()
    val ended by model.lastEnded.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Connect to a Mac") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add by address") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ended?.let { words ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(words, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(Modifier.width(160.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Looking for Macs running Sill on this network…", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Install Sill for Mac from getsill.app and keep it open. Away from home, add your Mac by its Tailscale address.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(rows, key = { it.key }) { row -> MacCard(row, onOpen = { model.open(row) }, onForget = { model.forget(row) }) }
        }
    }

    if (adding) AddAddressDialog(onDismiss = { adding = false }) { host, port, tls ->
        adding = false
        model.addAddress(host, port, tls)
    }
}

@Composable
private fun MacCard(row: MacRow, onOpen: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (row.saved != null || row.door != HomeDoor.PLAIN) Icons.Filled.Lock else Icons.Filled.Computer,
                contentDescription = null,
                tint = if (row.saved != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (row.saved != null) "Forget this Mac" else "Remove") },
                    onClick = { menu = false; onForget() },
                )
            }
        }
    }
}

@Composable
private fun AddAddressDialog(onDismiss: () -> Unit, onAdd: (String, Int, Boolean) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(true) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a Mac by address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(host, { host = it.trim() }, label = { Text("Address") }, singleLine = true,
                    placeholder = { Text("192.168.1.20 or mac.tailnet.ts.net") })
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Encrypted (pairing)")
                        Text(
                            if (tls) "Sill for Mac, or SillHost --pairing" else "A development SillHost without --pairing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(tls, { tls = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotEmpty() && portNumber != null, onClick = { onAdd(host, portNumber!!, tls) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
