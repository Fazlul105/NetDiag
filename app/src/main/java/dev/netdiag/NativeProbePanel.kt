package dev.netdiag

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun NativeProbePanel(state: ProbeState, start: (Int, String, String, String) -> Unit, cancel: () -> Unit,
    recording: Boolean, startRecovery: (String, String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("7777") }
    var timeout by rememberSaveable { mutableStateOf("1500") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TCP / UDP diagnostics", style = MaterialTheme.typography.titleMedium)
            Text("Use a server you control. UDP requires a matching echo server.")
            OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                enabled = !state.working, label = { Text("Server IP (IPv4 or IPv6)") },
                modifier = Modifier.fillMaxWidth().testTag("native_ip"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = port, onValueChange = { port = it }, singleLine = true,
                    enabled = !state.working, label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("native_port"))
                OutlinedTextField(value = timeout, onValueChange = { timeout = it }, singleLine = true,
                    enabled = !state.working, label = { Text("Timeout (ms)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("native_timeout"))
            }
            OutlinedButton(enabled = !state.working && !recording,
                onClick = { startRecovery(address, port) }) { Text("Record Wi-Fi recovery") }
            Text("Recovery recording checks TCP after Wi-Fi returns and includes results in CSV.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.working, onClick = { start(0, address, port, timeout) },
                    modifier = Modifier.weight(1f).testTag("native_tcp")) { Text("TCP connect") }
                Button(enabled = !state.working, onClick = { start(1, address, port, timeout) },
                    modifier = Modifier.weight(1f).testTag("native_udp")) { Text("UDP echo") }
            }
            if (state.working) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Testing…")
                    OutlinedButton(onClick = cancel) { Text("Cancel") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.observation?.let { observation ->
                val result = observation.result
                Text("${observation.protocol}: ${result.status}", modifier = Modifier.testTag("native_result"),
                    style = MaterialTheme.typography.titleSmall)
                val metric = if (result.status == "OK") {
                    if (observation.protocol == "TCP connect") "Connect time" else "Round-trip time"
                } else "Elapsed"
                Text("$metric: ${String.format(Locale.US, "%.3f", result.elapsedMs)} ms")
                Text("${observation.target} • ${result.detail}")
                if (result.systemError != 0) Text("System error: ${result.systemError}")
                Text(observation.at.toString(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
