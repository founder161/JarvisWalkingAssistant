package com.example.jarviswalkingassistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {

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

            LaunchedEffect(Unit) {
                JarvisForegroundService.onUpdate = {
                    liveTranscript = JarvisForegroundService.liveTranscript
                    detailedAnswer = JarvisForegroundService.latestFullAnswer
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
                    
                    Text(
                        text = "Live Transcript:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
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
