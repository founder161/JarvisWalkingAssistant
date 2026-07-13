package com.example.jarviswalkingassistant.wear

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WatchActivity : ComponentActivity() {

    private val modes = listOf("FILES", "WEB", "BOTH")
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var responseText by remember { mutableStateOf("Awaiting response...") }
            var currentMode by remember { mutableStateOf("BOTH") }

            LaunchedEffect(Unit) {
                MessageListenerService.onMessageReceived = { msg ->
                    responseText = msg
                    vibrate()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Jarvis",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.title3
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Single button, cycles FILES -> WEB -> BOTH -> FILES, same
                    // pattern as the spacebar mute toggle on the laptop version.
                    Button(
                        onClick = {
                            val nextIndex = (modes.indexOf(currentMode) + 1) % modes.size
                            currentMode = modes[nextIndex]
                            vibrate()
                            sendModeToPhone(currentMode)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Mode: $currentMode")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = responseText,
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    private fun sendModeToPhone(mode: String) {
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(applicationContext)
                val messageClient = Wearable.getMessageClient(applicationContext)
                val nodes = com.google.android.gms.tasks.Tasks.await(nodeClient.connectedNodes)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, "/jarvis/set_mode", mode.toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java)
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
