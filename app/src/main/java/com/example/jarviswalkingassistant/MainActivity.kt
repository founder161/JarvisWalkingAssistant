package com.example.jarviswalkingassistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {

    // Registered as fields (not inside setContent) — required before STARTED.
    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                DocumentStore.addFolder(applicationContext, it)
                JarvisForegroundService.onUpdate?.invoke()
            }
        }

    private val filesPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            uris.forEach { DocumentStore.addFile(applicationContext, it) }
            JarvisForegroundService.onUpdate?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            100
        )

        val serviceIntent = Intent(this, JarvisForegroundService::class.java)
        startService(serviceIntent)

        setContent {
            var liveTranscript by remember { mutableStateOf(JarvisForegroundService.liveTranscript) }
            var mode by remember { mutableStateOf(JarvisForegroundService.searchMode) }
            var listening by remember { mutableStateOf(JarvisForegroundService.isListeningEnabled) }
            var sourcesVersion by remember { mutableStateOf(0) } // bump to force source-list recompose
            var historyVersion by remember { mutableStateOf(0) } // bump to force history recompose

            LaunchedEffect(Unit) {
                JarvisForegroundService.onUpdate = {
                    liveTranscript = JarvisForegroundService.liveTranscript
                    mode = JarvisForegroundService.searchMode
                    listening = JarvisForegroundService.isListeningEnabled
                    sourcesVersion++
                    historyVersion++
                }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

                    Text(
                        text = "Jarvis Walking Assistant",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // --- Start/Stop control ---
                    Button(
                        onClick = {
                            val intent = Intent(this@MainActivity, JarvisForegroundService::class.java).apply {
                                putExtra("TOGGLE_LISTENING", true)
                            }
                            startService(intent)
                            listening = !listening
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (listening) Color(0xFF2E7D32) else Color(0xFFC62828)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (listening) "● LISTENING — tap to stop" else "○ STOPPED — tap to start")
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    // --- Mode toggle ---
                    Text("Mode: $mode", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        listOf("FILES", "WEB", "BOTH").forEach { option ->
                            Button(
                                onClick = {
                                    val intent = Intent(this@MainActivity, JarvisForegroundService::class.java).apply {
                                        putExtra("SET_MODE", option)
                                    }
                                    startService(intent)
                                    mode = option
                                },
                                modifier = Modifier.weight(1f).padding(end = if (option != "BOTH") 4.dp else 0.dp),
                                colors = if (mode == option) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                            ) { Text(option, style = MaterialTheme.typography.labelMedium) }
                        }
                    }

                    // --- Documents: add / list / remove ---
                    Text("Documents (${DocumentStore.summaryLabel()})", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Button(
                            onClick = { filesPickerLauncher.launch(arrayOf("text/plain", "text/markdown", "*/*")) },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        ) { Text("+ Add Files") }
                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) { Text("+ Add Folder") }
                    }
                    // Reading sourcesVersion here just to force this block to recompose after add/remove
                    @Suppress("UNUSED_EXPRESSION") sourcesVersion
                    Column(modifier = Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState())) {
                        DocumentStore.sources.forEach { source ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = (if (source.isFolder) "📁 " else "📄 ") + source.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    DocumentStore.removeSource(applicationContext, source)
                                    sourcesVersion++
                                }) { Text("Remove") }
                            }
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("Live Transcript:", style = MaterialTheme.typography.titleMedium)
                    Text(liveTranscript, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("History (most recent first):", style = MaterialTheme.typography.titleMedium)

                    @Suppress("UNUSED_EXPRESSION") historyVersion
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    ) {
                        val historySnapshot = synchronized(JarvisForegroundService.qaHistory) {
                            JarvisForegroundService.qaHistory.toList()
                        }
                        historySnapshot.forEach { (question, answer) ->
                            Text("Q: $question", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(answer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                            Divider(modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }

                    var textInput by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = { Text("Manual Query") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                val intent = Intent(this@MainActivity, JarvisForegroundService::class.java).apply {
                                    putExtra("PROMPT", textInput)
                                }
                                startService(intent)
                                textInput = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Send Query") }
                }
            }
        }
    }
}
