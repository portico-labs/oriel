package dev.fmagana.oriel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fmagana.oriel.AppViewModel
import dev.fmagana.oriel.MacRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(model: AppViewModel, row: MacRow) {
    val ui by model.pairUi.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    val busy = ui.asking || ui.pairing

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair with ${row.name}") },
                navigationIcon = {
                    IconButton(onClick = { model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Pair once and this device can connect to ${row.name} from then on, encrypted, with no account.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (ui.asking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Asking ${row.name} to show a code…")
                }
            }
            ui.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            if (ui.codeShown) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() || it == ' ' }.take(14) },
                    label = { Text("12-digit code") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.pairWithCode(row, code) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !busy && code.count { it.isDigit() } == 12, onClick = { model.pairWithCode(row, code) }) {
                        Text(if (ui.pairing) "Pairing…" else "Pair")
                    }
                    OutlinedButton(enabled = !busy, onClick = { model.ask(row) }) { Text("Ask for a new code") }
                }
            }
        }
    }
}
