package com.example.jarviswalkingassistant

import android.app.*
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
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

                val qaHistory: MutableList<Pair<String, String>> =
                Collections.synchronizedList(mutableListOf<Pair<String, String>>())

                @Volatile var searchMode: String = "BOTH"

                @Volatile var isListeningEnabled = true

                @Volatile var preferredMicSource: String = "AUTO"

                fun setMicSource(source: String) {
                        if (source in setOf("AUTO", "PHONE", "BLUETOOTH")) {
                                preferredMicSource = source
                                onUpdate?.invoke()
                        }
                }

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

                const val BASE_SYSTEM_PROMPT = """
                You are Jarvis — a silent advisor whispering brief, tactical advice into Dale's
                ear while he is in a live, real conversation with someone else (a manager,
                colleague, client, etc). You are NOT a participant in that conversation and you
                have no job, contract, or role of your own — every statement you hear was made
                BY someone else TO Dale, or is Dale thinking aloud. Never respond as if a
                statement was addressed to you or is about you.

                Your entire reply is spoken aloud through an earpiece the instant you produce
                it, so:
                - Default to ONE short sentence — the single most useful thing to say or think
                right now. Aim for under 25 words.
                - Never use bullet points, numbered lists, headings, bold text, or multiple
                named options ("Option A / Option B") — say it the way a person would
                actually whisper a quick tip, not the way a document would present it.
                - Only go longer than one sentence if Dale explicitly types or says something
                like "explain more" or "give me detail" — and even then, speak in plain
                flowing sentences, never formatted lists.
                - If you're not confident about a specific fact (a name, a number, a status),
                say so briefly rather than inventing one — especially anything business or
                investor-related.
                - If "Reference documents" are included below, treat them as background for
                this conversation only.
                """
        }

        override fun onCreate() {
                super.onCreate()
                createNotificationChannel()
                tts = TextToSpeech(this, this)
                setupSpeechRecognizer()
                DocumentStore.restorePersisted(applicationContext)
        }

        private fun routeToBluetoothMic(useBluetooth: Boolean) {
                val actuallyUseBluetooth = when (preferredMicSource) {
                        "PHONE" -> false
                        "BLUETOOTH" -> true
                        else -> useBluetooth
                }
                val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
                try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (actuallyUseBluetooth) {
                                        val btDevice = audioManager.availableCommunicationDevices.firstOrNull {
                                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                                        }
                                        if (btDevice != null) audioManager.setCommunicationDevice(btDevice)
                                } else {
                                        audioManager.clearCommunicationDevice()
                                }
                        } else {
                                if (actuallyUseBluetooth) {
                                        audioManager.startBluetoothSco()
                                        audioManager.isBluetoothScoOn = true
                                } else {
                                        audioManager.stopBluetoothSco()
                                        audioManager.isBluetoothScoOn = false
                                }
                        }
                } catch (_: SecurityException) {
                }
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
                                restartListeningIfAllowed(delayMs = 300)
                        }

                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                })

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
                                routeToBluetoothMic(true)
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
                        if (isSpeaking) {
                                tts?.stop()
                                isSpeaking = false
                                isListeningEnabled = true
                                restartListeningIfAllowed()
                        } else {
                                val nowOn = toggleListening()
                                if (nowOn) restartListeningIfAllowed() else speechRecognizer?.stopListening()
                        }
                }
                intent?.getStringExtra("SET_MIC_SOURCE")?.let { source ->
                        setMicSource(source)
                }

                return START_STICKY
        }

        override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.UK
                        val bestVoice = tts?.voices
                        ?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                        ?.maxByOrNull { it.quality }
                        bestVoice?.let { tts?.voice = it }
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

                        val spokenSummary = prepareForSpeech(response)
                        routeToBluetoothMic(false)
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
                                        val recentHistory = synchronized(qaHistory) { qaHistory.take(6) }.reversed()
                                        recentHistory.forEach { (pastQuestion, pastAnswer) ->
                                                put(JSONObject().apply {
                                                        put("role", "user")
                                                        put("content", pastQuestion)
                                                })
                                                put(JSONObject().apply {
                                                        put("role", "assistant")
                                                        put("content", pastAnswer)
                                                })
                                        }
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

        private fun prepareForSpeech(fullText: String): String {
                val stripped = fullText
                .replace(Regex("[*#_`]"), "")
                .replace(Regex("(?m)^[-•]\\s*"), "")
                .replace(Regex("\\n{2,}"), ". ")
                .replace(Regex("\\n"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
                val maxChars = 320
                return if (stripped.length > maxChars) stripped.take(maxChars).trimEnd() + "…" else stripped
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
