package com.example.jarviswalkingassistant
import android.app.*
import android.content.Intent
import android.media.AudioAttributes
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
                var liveTranscript = "Idle — press Arm before they speak"
                var onUpdate: (() -> Unit)? = null
                val qaHistory: MutableList<Pair<String, String>> =
                Collections.synchronizedList(mutableListOf<Pair<String, String>>())
                @Volatile var searchMode: String = "BOTH"
                // Replaces the old always-on ambient listening. Default is OFF: the mic is
                // not active and nothing you say gets treated as a question. You arm it
                // deliberately right before the other person is about to speak; it catches
                // their next utterance, gives one reply, then disarms itself automatically.
                @Volatile var isArmed = false
                // PHONE = always use the phone's own mic, ignore any Bluetooth device.
                // BLUETOOTH / AUTO = prefer a connected Bluetooth mic (earbuds), fall back
                // to the phone mic silently if none is connected.
                @Volatile var micSource: String = "AUTO"
                fun setMode(mode: String) {
                        if (mode in setOf("FILES", "WEB", "BOTH")) {
                                searchMode = mode
                                onUpdate?.invoke()
                        }
                }
                fun setMicSource(source: String) {
                        if (source in setOf("PHONE", "BLUETOOTH", "AUTO")) {
                                micSource = source
                                onUpdate?.invoke()
                        }
                }
                const val BASE_SYSTEM_PROMPT = """
                You are Jarvis, a silent in-ear advisor. The user is in a live conversation with
                someone else and you are secretly listening. The text you receive is what the
                OTHER PERSON just said out loud — it is NOT a question addressed to you, and you
                are not a participant in the conversation. Your job is to give the user, quietly
                in their ear, the single best thing to say or do next.
                
                Rules:
                - Reply in 1-2 short spoken sentences. A third sentence only if genuinely
                  necessary. No bullet points, no headers, no numbered lists.
                  - Never ask the user a clarifying question back. They can't reply mid
                    conversation — you get one shot. Make your best judgment call and give
                      concrete, usable advice.
                      - Do not address the other person and do not treat their statement as being
                        said to you. You are coaching the user on how THEY should respond.
                        - If the statement is too garbled to act on, say so in one short sentence
                          rather than guessing wildly.
                          - If unsure about a specific fact (a name, a number, a status), say so briefly
                            rather than inventing one.
                            - If reference documents are included below, they are background context about
                              the user's situation, not something the other person said.
                              """
        }
        override fun onCreate() {
                super.onCreate()
                createNotificationChannel()
                tts = TextToSpeech(this, this)
                setupSpeechRecognizer()
                DocumentStore.restorePersisted(applicationContext)
        }
        // Handles the MIC side only. Independent from TTS output routing below —
        // conflating the two was the cause of the "quiet then suddenly loud" bug,
        // since clearing the SCO link exactly when playback started forced a live
        // route change mid-utterance.
        private fun routeMic(useBluetooth: Boolean) {
                val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
                val wantBluetooth = useBluetooth && micSource != "PHONE"
                try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (wantBluetooth) {
                                        val btDevice = audioManager.availableCommunicationDevices.firstOrNull {
                                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                                        }
                                        if (btDevice != null) audioManager.setCommunicationDevice(btDevice)
                                } else {
                                        audioManager.clearCommunicationDevice()
                                }
                        } else {
                                if (wantBluetooth) {
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
                                        // One-shot: consume the arm state immediately so nothing else
                                        // triggers another reply until the user arms it again.
                                        isArmed = false
                                        onUpdate?.invoke()
                                        processUserPrompt(matches[0])
                                } else {
                                        restartIfArmed()
                                }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!matches.isNullOrEmpty()) {
                                        liveTranscript = matches[0]
                                        onUpdate?.invoke()
                                }
                        }
                        override fun onEndOfSpeech() {
                                restartIfArmed()
                        }
                        override fun onError(error: Int) {
                                // Silence/no-match fires constantly while armed and waiting — that's
                                // expected, not a fault. Keep retrying only while still armed.
                                restartIfArmed(delayMs = 300)
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
                                onUpdate?.invoke()
                                // Deliberately no restart here — one reply per arm, then idle.
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                                isSpeaking = false
                                onUpdate?.invoke()
                        }
                })
        }
        private fun restartIfArmed(delayMs: Long = 0) {
                serviceScope.launch(Dispatchers.Main) {
                        if (delayMs > 0) delay(delayMs)
                        if (isArmed && !isSpeaking) {
                                routeMic(true)
                                recognizerIntent?.let { speechRecognizer?.startListening(it) }
                        }
                }
        }
        // Same control does double duty, like the watch/phone buttons: if Jarvis is
        // mid-sentence this interrupts it (the "shut up" override); otherwise it
        // arms for the other person's next line.
        fun armForNextReply() {
                if (isSpeaking) {
                        stopSpeaking()
                        return
                }
                isArmed = true
                liveTranscript = "Armed — listening for their next line..."
                onUpdate?.invoke()
                restartIfArmed()
        }
        fun stopSpeaking() {
                tts?.stop()
                isSpeaking = false
                isArmed = false
                speechRecognizer?.stopListening()
                liveTranscript = "Idle — press Arm before they speak"
                onUpdate?.invoke()
        }
        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                val statusText = when {
                        isSpeaking -> "Speaking..."
                        isArmed -> "Armed — waiting for their reply"
                        else -> "Idle"
                }
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis")
                .setContentText(statusText)
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
                intent?.getStringExtra("SET_MIC_SOURCE")?.let { source ->
                        setMicSource(source)
                }
                if (intent?.getBooleanExtra("ARM", false) == true) {
                        armForNextReply()
                }
                if (intent?.getBooleanExtra("STOP_SPEAKING", false) == true) {
                        stopSpeaking()
                }
                return START_STICKY
        }
        override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.UK
                        tts?.setAudioAttributes(
                                AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                                )
                        val bestVoice = tts?.voices
                        ?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                        ?.maxByOrNull { it.quality }
                        bestVoice?.let { tts?.voice = it }
                }
        }
        fun processUserPrompt(prompt: String) {
                liveTranscript = "Heard: $prompt"
                latestFullAnswer = "Thinking..."
                onUpdate?.invoke()
                serviceScope.launch(Dispatchers.IO) {
                        val response = callClaude(prompt)
                        latestFullAnswer = response
                        qaHistory.add(0, prompt to response)
                        if (qaHistory.size > 20) qaHistory.removeAt(qaHistory.size - 1)
                        withContext(Dispatchers.Main) { onUpdate?.invoke() }
                        val spokenSummary = capForSpeech(response)
                        // Release the mic-side SCO link and give the audio route a moment to
                        // settle onto the normal playback path BEFORE speaking starts, instead
                        // of during — that's what caused the quiet-then-loud jump.
                        withContext(Dispatchers.Main) { routeMic(false) }
                        delay(200)
                        val ttsParams = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
                        tts?.speak(spokenSummary, TextToSpeech.QUEUE_FLUSH, ttsParams, "JarvisTTS-${System.currentTimeMillis()}")
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
                                put("max_tokens", 1024)
                                var systemPrompt = BASE_SYSTEM_PROMPT
                                if (mode == "FILES" || mode == "BOTH") {
                                        val docContext = DocumentStore.retrieveContext(prompt)
                                        if (docContext.isNotBlank()) {
                                                systemPrompt += "\n\nReference documents (background context only):\n$docContext"
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
                                textAnswer.ifEmpty { "No response from Claude" }
                        }
                } catch (e: Exception) {
                        "Error: ${e.javaClass.simpleName}: ${e.message}"
                }
        }
        // The system prompt already asks for 1-2 short sentences; this is just a
        // safety cap in case the model ignores that and returns something long.
        private fun capForSpeech(fullText: String): String {
                val sentences = fullText.split(Regex("(?<=[.!?])\\s+"))
                return if (sentences.size > 3) sentences.take(3).joinToString(" ") else fullText
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

                private fun routeToBluetoothMic(useBluetooth: Boolean) {
                            val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
                            try {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                                if (useBluetooth) {
                                                                                        val btDevice = audioManager.availableCommunicationDevices.firstOrNull {
                                                                                                                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                                                                                                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                                                                                        }
                                                                                                            if (btDevice != null) audioManager.setCommunicationDevice(btDevice)
                                                                } else {
                                                                                        audioManager.clearCommunicationDevice()
                                                                }
                                            } else {
                                                                if (useBluetooth) {
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
                                                                                                                    routeToBluetoothMic(false)
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
                                                                                                                                        val nowOn = toggleListening()
                                                                                                                                                    if (nowOn) restartListeningIfAllowed() else speechRecognizer?.stopListening()
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
