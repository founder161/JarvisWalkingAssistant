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
import com.example.jarviswalkingassistant.BuildConfig
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

        // Three modes, toggled from either the watch button or the phone UI:
        // FILES = only your selected document folder, no web tool sent at all.
        // WEB   = only Anthropic's live web search, no document context.
        // BOTH  = both sources available to the model in the same call.
        // Default is FILES — web is opt-in ("if I grant it"), not on by default.
        @Volatile var searchMode: String = "FILES"

        fun setMode(mode: String) {
            if (mode in setOf("FILES", "WEB", "BOTH")) {
                searchMode = mode
                onUpdate?.invoke()
            }
        }

        // Condensed Ground Truth so this app cannot repeat the PXN call fabrication
        // incident (assistant stated invented "signed design partners" 4x live).
        // This is a static snapshot, not a substitute for the Notion Ground Truth
        // page — regenerate this string from Notion, don't hand-edit it in place.
        const val GROUND_TRUTH_SYSTEM_PROMPT = """
You are Jarvis, a private ambient advisor for Dale Williams (sole founder, Leek Venture Studio).
Hard rules: If you do not know a fact with confidence, say "I don't have that confirmed" —
never invent names, numbers, or partners. Never state a design partner, investor, or CTO as
"signed" or "confirmed" unless explicitly told so in this prompt.

AgentGuard Systems Ltd: hardware-enforced AI governance kill-switch, regulated financial services.
UK patent GB2607435.1 filed 31 Mar 2026, amended claims submitted 16 May 2026. NOT yet granted.
PCT/EPO deadline 31 Mar 2027 (unrecoverable if missed). No design partners are signed yet —
Beazley, Twenty7Tec, Monzo are TARGETS only, Q4 2026 fee discussions, not confirmed. SEIS cap
£250,000 (not £500k). Competitors: Geordie AI ($36.5M raised), EQTY Lab, Credo AI, Holistic AI,
Arthur AI. Never use terms: DPU, TDX, eBPF, FPGA, GPIO, SSR, BlueField, Gramine — use "hardware
enforcement processor", "secure execution environment", "wire-speed packet inspection",
"electronic isolation mechanism", or "physical network severance component" instead.

AI Bluetooth Ltd / Jarvis: passive ambient listening + private document retrieval to Bluetooth
audio (this app). Patent NOT yet filed — 11 claims drafted only, no priority date.

Dale is sole founder, sole equity across all entities — never suggest equity co-founders.
CTO call with Michał Kowalczyk (Invisible Things Lab) is 14 July 13:00 CEST.
"""
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
        DocumentStore.restorePersistedFolder(applicationContext)
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
        intent?.getStringExtra("SET_MODE")?.let { mode ->
            setMode(mode)
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
        val apiKey = BuildConfig.CLAUDE_API_KEY
        val url = "https://api.anthropic.com/v1/messages"
        val mediaType = "application/json".toMediaType()
        val mode = searchMode // FILES / WEB / BOTH, set via watch button or phone UI

        return try {
            val rootObj = JSONObject().apply {
                put("model", "claude-haiku-4-5-20251001")
                put("max_tokens", 1024)

                var systemPrompt = GROUND_TRUTH_SYSTEM_PROMPT
                if (mode == "FILES" || mode == "BOTH") {
                    val docContext = DocumentStore.retrieveContext(prompt)
                    if (docContext.isNotBlank()) {
                        systemPrompt += "\n\nReference documents (from your selected folder):\n$docContext"
                    }
                }
                put("system", systemPrompt)

                // Real server-side tool: Anthropic executes the search and returns
                // final text directly. Only attached when the mode calls for it —
                // FILES-only mode sends no web tool at all, so a call flagged as
                // "files only" genuinely cannot reach the internet.
                if (mode == "WEB" || mode == "BOTH") {
                    val toolsArray = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                        })
                    }
                    put("tools", toolsArray)
                }
                
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
                val responseBody = response.body?.string() ?: return "Empty response (HTTP ${response.code})"
                
                if (!response.isSuccessful) {
                    return "API Error ${response.code}: $responseBody"
                }
                
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
            "Error: ${e.javaClass.simpleName}: ${e.message}"
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
