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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {

    // Must be registered before the activity reaches STARTED — kept as a field,
    // not built inside setContent (which recomposes) or it silently breaks.
    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                DocumentStore.selectFolder(applicationContext, it)
                JarvisForegroundService.onUpdate?.invoke()
            }
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
            var detailedAnswer by remember { mutableStateOf(JarvisForegroundService.latestFullAnswer) }
            var textInput by remember { mutableStateOf("") }
            var mode by remember { mutableStateOf(JarvisForegroundService.searchMode) }
            var folderLabel by remember { mutableStateOf(DocumentStore.folderLabel) }

            LaunchedEffect(Unit) {
                JarvisForegroundService.onUpdate = {
                    liveTranscript = JarvisForegroundService.liveTranscript
                    detailedAnswer = JarvisForegroundService.latestFullAnswer
                    mode = JarvisForegroundService.searchMode
                    folderLabel = DocumentStore.folderLabel
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Jarvis Walking Assistant",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Divider()

                    // --- Mode + document folder controls ---
                    Text(
                        text = "Mode: $mode",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("FILES", "WEB", "BOTH").forEach { option ->
                            Button(
                                onClick = {
                                    val intent = Intent(this@MainActivity, JarvisForegroundService::class.java).apply {
                                        putExtra("SET_MODE", option)
                                    }
                                    startService(intent)
                                    mode = option
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = if (option != "BOTH") 4.dp else 0.dp),
                                colors = if (mode == option)
                                    ButtonDefaults.buttonColors()
                                else
                                    ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text(option, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Documents: $folderLabel",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select Documents Folder")
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = "Live Transcript:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = liveTranscript,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Claude Response:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = detailedAnswer,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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
                    ) {
                        Text("Send Query")
                    }
                }
            }
        }
    }
}
