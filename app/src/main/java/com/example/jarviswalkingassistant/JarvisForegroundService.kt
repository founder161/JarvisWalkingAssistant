package com.example.jarviswalkingassistant

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

class JarvisForegroundService : Service(), TextToSpeech.OnInitListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private val client = OkHttpClient()

    @Volatile private var isSpeaking = false

    companion object {
        const val CHANNEL_ID = "JarvisServiceChannel"
        const val NOTIFICATION_ID = 888
        var latestFullAnswer = "Awaiting input..."
        var liveTranscript = "Ambient listening active..."
        var onUpdate: (() -> Unit)? = null

        // Scrolling history, most-recent-first, instead of overwriting the last
        // answer — same idea as live_transcribe.py on the laptop. Synchronized
        // since it's written from a background thread and read from the UI.
        val qaHistory: MutableList<Pair<String, String>> =
            Collections.synchronizedList(mutableListOf<Pair<String, String>>())

        // Full access by default now — web + whatever files you've added, both on.
        // FILES/WEB restrict it; you have to deliberately dial it down, not up.
        @Volatile var searchMode: String = "BOTH"

        // The Start/Stop control. "Physical button" here means an always-visible
        // on-screen toggle (like the laptop's spacebar mute was software-detected
        // too) — happy to wire it to a hardware key (e.g. volume button) next if
        // you want a literal physical press instead of on-screen.
        @Volatile var isListeningEnabled = true

        fun setMode(mode: String) {
            if (mode in setOf("FILES", "WEB", "BOTH")) {
                searchMode = mode
                onUpdate?.invoke()
            }
        }

        fun toggleListening(): Boolean {
            isListeningEnabled = !isListeningEnabled
            onUpdate?.invoke()
            return isListeningEnabled
        }

        // General-purpose by default. No company facts hardcoded here anymore —
        // if you want AgentGuard-specific grounding, add those files yourself via
        // the document picker. This prompt only guards against inventing specific
        // facts, it does not narrow what topics are allowed.
        const val BASE_SYSTEM_PROMPT = """
You are Jarvis, Dale's private ambient voice advisor. Answer any question on any
topic exactly as you normally would — general knowledge, current events, weather,
anything — using your full capabilities including live web search when available.
You are NOT limited to any single company or business topic; do not assume the
conversation is about any specific company unless the user's question or the
reference documents below actually indicate that. If "Reference documents" are
included below, treat them as supplementary context for this conversation only.
If you're not confident about a specific fact (a name, a number, a status), say
"I don't have that confirmed" rather than inventing one — especially for anything
business or investor-related.
"""
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
        DocumentStore.restorePersisted(applicationContext)
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            liveTranscript = "Speech recognition not available"
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    processUserPrompt(matches[0])
                }
                restartListeningIfAllowed()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    liveTranscript = matches[0]
                    onUpdate?.invoke()
                }
            }

            override fun onEndOfSpeech() {
                restartListeningIfAllowed()
            }

            override fun onError(error: Int) {
                // Timeouts/no-match fire constantly during ordinary silence — that's
                // normal, not a fault. Small delay avoids a tight restart loop.
                restartListeningIfAllowed(delayMs = 300)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        // THE FIX for "keeps interrupting itself": the mic used to stay open while
        // TTS was talking, so it heard its own answer and treated it as a new
        // question — which fired TTS again mid-sentence, forever. Now it stops
        // listening the instant speech starts and only resumes once done.
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                speechRecognizer?.stopListening()
            }
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                restartListeningIfAllowed()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                restartListeningIfAllowed()
            }
        })

        restartListeningIfAllowed()
    }

    private fun restartListeningIfAllowed(delayMs: Long = 0) {
        serviceScope.launch(Dispatchers.Main) {
            if (delayMs > 0) delay(delayMs)
            if (isListeningEnabled && !isSpeaking) {
                recognizerIntent?.let { speechRecognizer?.startListening(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Active")
            .setContentText(if (isListeningEnabled) "Listening ambiently..." else "Paused")
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
        if (intent?.getBooleanExtra("TOGGLE_LISTENING", false) == true) {
            val nowOn = toggleListening()
            if (nowOn) restartListeningIfAllowed() else speechRecognizer?.stopListening()
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
            val response = callClaude(prompt)
            latestFullAnswer = response
            qaHistory.add(0, prompt to response)
            if (qaHistory.size > 20) qaHistory.removeAt(qaHistory.size - 1)
            withContext(Dispatchers.Main) { onUpdate?.invoke() }

            val spokenSummary = extractSummary(response)
            tts?.speak(spokenSummary, TextToSpeech.QUEUE_FLUSH, null, "JarvisTTS-${System.currentTimeMillis()}")
            sendToWatch(spokenSummary)
        }
    }

    private fun callClaude(prompt: String): String {
        val apiKey = BuildConfig.CLAUDE_API_KEY
        val url = "https://api.anthropic.com/v1/messages"
        val mediaType = "application/json".toMediaType()
        val mode = searchMode

        return try {
            val rootObj = JSONObject().apply {
                put("model", "claude-haiku-4-5-20251001")
                // Was 1024 — real cause of "partial" answers. A web-search round
                // trip eats into that budget before the model even starts writing
                // the final answer, so it hit the cap mid-sentence.
                put("max_tokens", 4096)

                var systemPrompt = BASE_SYSTEM_PROMPT
                if (mode == "FILES" || mode == "BOTH") {
                    val docContext = DocumentStore.retrieveContext(prompt)
                    if (docContext.isNotBlank()) {
                        systemPrompt += "\n\nReference documents (only what you've explicitly added):\n$docContext"
                    }
                }
                put("system", systemPrompt)

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

                if (jsonResponse.optString("stop_reason", "") == "max_tokens") {
                    textAnswer += "\n\n[Cut off at max length — ask me to continue]"
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
