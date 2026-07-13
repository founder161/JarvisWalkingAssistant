package com.example.jarviswalkingassistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.math.log10

class JarvisForegroundService : Service(), TextToSpeech.OnInitListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val client = OkHttpClient()

    companion object {
        const val CHANNEL_ID = "JarvisServiceChannel"
        const val NOTIFICATION_ID = 888
        var latestFullAnswer = "Awaiting input..."
        var liveTranscript = "Ambient listening active..."
        var onUpdate: (() -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            liveTranscript = "Speech recognition not available"
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    processUserPrompt(matches[0])
                }
                speechRecognizer?.startListening(intent)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    liveTranscript = matches[0]
                    onUpdate?.invoke()
                }
            }

            override fun onEndOfSpeech() {
                speechRecognizer?.startListening(intent)
            }

            override fun onError(error: Int) {
                liveTranscript = "Recognition error: $error"
                onUpdate?.invoke()
                speechRecognizer?.startListening(intent)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Active")
            .setContentText("Listening ambiently...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        intent?.getStringExtra("PROMPT")?.let { manualPrompt ->
            processUserPrompt(manualPrompt)
        }

        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.UK
        }
    }

    fun processUserPrompt(prompt: String) {
        liveTranscript = "User: $prompt"
        latestFullAnswer = "Querying Claude..."
        onUpdate?.invoke()

        serviceScope.launch(Dispatchers.IO) {
            val response = callClaudeWithWebSearch(prompt)
            latestFullAnswer = response
            withContext(Dispatchers.Main) { onUpdate?.invoke() }

            val spokenSummary = extractSummary(response)
            tts?.speak(spokenSummary, TextToSpeech.QUEUE_FLUSH, null, "JarvisTTS")
            sendToWatch(spokenSummary)
        }
    }

    private fun callClaudeWithWebSearch(prompt: String): String {
        val apiKey = System.getenv("CLAUDE_API_KEY") ?: "sk-ant-YOUR_KEY_HERE"
        val url = "https://api.anthropic.com/v1/messages"
        val mediaType = "application/json".toMediaType()

        return try {
            val rootObj = JSONObject().apply {
                put("model", "claude-3-5-sonnet-20240620")
                put("max_tokens", 1024)
                
                val toolsArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "web_search")
                        put("description", "Search the live web for current information")
                        put("input_schema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("query", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Search query string")
                                })
                            })
                            put("required", JSONArray().apply { put("query") })
                        })
                    })
                }
                put("tools", toolsArray)
                
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(rootObj.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "API Error: ${response.code}"
                val responseBody = response.body?.string() ?: return "Empty response"
                
                val jsonResponse = JSONObject(responseBody)
                val contentArray = jsonResponse.getJSONArray("content")
                
                var textAnswer = ""
                for (i in 0 until contentArray.length()) {
                    val item = contentArray.getJSONObject(i)
                    if (item.getString("type") == "text") {
                        textAnswer += item.getString("text")
                    }
                }

                textAnswer.ifEmpty { "No response from Claude" }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun extractSummary(fullText: String): String {
        val fragments = fullText.split(". ")
        return if (fragments.size > 1) "${fragments[0]}. ${fragments[1]}." else fullText.take(200)
    }

    private fun sendToWatch(message: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val nodeClient = Wearable.getNodeClient(applicationContext)
                val messageClient = Wearable.getMessageClient(applicationContext)
                val nodes = Tasks.await(nodeClient.connectedNodes)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, "/jarvis/response", message.toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Jarvis Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
