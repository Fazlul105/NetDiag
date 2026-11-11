package dev.netdiag

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var model: DiagnosticsModel
    private var pendingCsv: String? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val snapshot = pendingCsv
        pendingCsv = null
        if (uri != null && snapshot != null) {
            try {
                val stream = requireNotNull(contentResolver.openOutputStream(uri))
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
                Toast.makeText(this, "Session exported", Toast.LENGTH_SHORT).show()
            } catch (exception: Exception) {
                Toast.makeText(this, "Export failed: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[DiagnosticsModel::class.java]
        pendingCsv = savedInstanceState?.getString("pendingCsv")
        setContent {
            val state by model.state.collectAsState()
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("NetDiag", style = MaterialTheme.typography.headlineLarge)
                        Text("Connectivity diagnostics", style = MaterialTheme.typography.titleMedium)
                        Text(state.network)
                        Text("Record while this app is visible. Leaving the app ends the session.")
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (state.recording) model.stop() else model.start() }) {
                                Text(if (state.recording) "Stop session" else "Start session")
                            }
                            OutlinedButton(enabled = !state.recording && state.events.isNotEmpty(),
                                onClick = {
                                    pendingCsv = model.csv()
                                    export.launch("netdiag-${System.currentTimeMillis()}.csv")
                                }) { Text("Export CSV") }
                        }
                        Text("${state.events.size} events", style = MaterialTheme.typography.titleSmall)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.events) { event ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(event.type, style = MaterialTheme.typography.labelLarge)
                                        Text(event.detail)
                                        Text(event.wallTime.toString(), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); model.attach() }
    override fun onStop() {
        if (!isChangingConfigurations) model.detach()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingCsv?.let { outState.putString("pendingCsv", it) }
        super.onSaveInstanceState(outState)
    }
}
