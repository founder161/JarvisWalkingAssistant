package com.example.jarviswalkingassistant.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

interface SttListener {
    fun onPartial(text: String)
    fun onFinal(speaker: String, text: String)
    fun onLevel(level: Float)
    fun onStatus(status: String)
}

interface Stt {
    val name: String
    fun start()
    fun stop()
    /** Milliseconds since anyone in the room last made speech-level sound. */
    fun silenceMs(): Long
}

/**
 * Meeting mode: continuous capture from the PHONE's own microphone (never the hearing
 * aids — that would force them into call mode) streamed to AssemblyAI with speaker labels.
 * No audio is stored anywhere.
 */
class AssemblyAiStt(private val apiKey: String, private val l: SttListener) : Stt {
    override val name = "AssemblyAI live"
    private val rate = 16000
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var lastVoice = System.currentTimeMillis()
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var lastTurnOrder = -1
    private var pendingUnformatted: Pair<String, String>? = null
    private var retries = 0

    override fun silenceMs() = System.currentTimeMillis() - lastVoice

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running) return
        running = true
        connect()
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)).also { it.startRecording() }
        thread = Thread {
            val buf = ShortArray(rate / 10) // 100 ms
            val bytes = ByteArray(buf.size * 2)
            var noise = 300.0
            while (running) {
                val n = recorder?.read(buf, 0, buf.size) ?: break
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    sum += s * s
                    bytes[2 * i] = (s and 0xff).toByte()
                    bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                }
                val rms = sqrt(sum / n)
                noise = if (rms < noise) noise * 0.9 + rms * 0.1 else noise * 0.995 + rms * 0.005
                if (rms > noise * 2.2 && rms > 350) lastVoice = System.currentTimeMillis()
                l.onLevel((rms / 4000.0).coerceIn(0.0, 1.0).toFloat())
                ws?.send(bytes.toByteString(0, n * 2))
            }
        }.apply { isDaemon = true; start() }
    }

    private fun connect() {
        val url = "wss://streaming.assemblyai.com/v3/ws?sample_rate=$rate&encoding=pcm_s16le" +
            "&format_turns=true&speaker_labels=true&speech_model=universal-streaming-english"
        val req = Request.Builder().url(url).addHeader("Authorization", apiKey).build()
        l.onStatus("Connecting…")
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { retries = 0; l.onStatus("Listening") }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws = null
                val code = response?.code
                l.onStatus(if (code == 401 || code == 403) "AssemblyAI key rejected" else "Reconnecting…")
                if (running && code != 401 && code != 403) {
                    Handler(Looper.getMainLooper()).postDelayed({ if (running) connect() },
                        (1000L shl minOf(retries++, 4)))
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                if (running) Handler(Looper.getMainLooper()).postDelayed({ if (running) connect() }, 1000)
            }
        })
    }

    private fun handle(text: String) {
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        if (j.optString("type") != "Turn") return
        val transcript = j.optString("transcript").trim()
        val order = j.optInt("turn_order", -1)
        val speaker = j.optString("speaker_label", "").let { if (it.isBlank() || it == "PENDING") "?" else it }
        if (transcript.isNotEmpty()) lastVoice = System.currentTimeMillis()
        val end = j.optBoolean("end_of_turn")
        val formatted = j.optBoolean("turn_is_formatted")
        // a new turn started while the previous one never got a formatted version: flush it
        pendingUnformatted?.let { (sp, tx) -> if (order != lastTurnOrder) { l.onFinal(sp, tx); pendingUnformatted = null } }
        lastTurnOrder = order
        when {
            end && formatted -> { pendingUnformatted = null; if (transcript.isNotEmpty()) l.onFinal(speaker, transcript) }
            end -> pendingUnformatted = speaker to transcript
            else -> l.onPartial(transcript)
        }
    }

    override fun stop() {
        running = false
        try { ws?.send("{\"type\":\"Terminate\"}"); ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
        recorder = null
    }
}

/**
 * Day mode / no AssemblyAI key: Android's own recogniser.
 *
 * On Android 13+ this uses the ON-DEVICE recogniser in a segmented session: one continuous listen that hands back a
 * result at each pause, never stops and restarts, and plays no chime. Older phones fall back to the restart loop, with
 * the system chime muted around each restart and errors backed off so it can't chime itself in a loop.
 */
class AndroidStt(private val ctx: Context, private val l: SttListener) : Stt {
    override val name = "On-device"
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    @Volatile private var running = false
    @Volatile private var lastVoice = System.currentTimeMillis()
    private var segmented = false
    private var engine = ""
    private var errors = 0
    private var lastPartial = ""
    private val am get() = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    override fun silenceMs() = System.currentTimeMillis() - lastVoice

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        if (segmented) {
            // continuous: a result per pause, session never ends on its own
            putExtra("android.speech.extra.SEGMENTED_SESSION", RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
            if (android.os.Build.VERSION.SDK_INT >= 33) putExtra("android.speech.extra.ENABLE_FORMATTING", "quality")
        }
    }

    override fun start() = main.post {
        if (running) return@post
        running = true; errors = 0
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { l.onStatus("No speech recogniser on this phone"); return@post }
        // Which recogniser? Samsung's default one chimes on every (re)start; Google's is silent. Prefer, in order:
        // Google's on-device recogniser (Android 12+), then Google's app recogniser, then whatever the phone offers.
        val services = ctx.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
            .map { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        val google = services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.startsWith("com.google.") }
        val onDevice = android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        segmented = onDevice && android.os.Build.VERSION.SDK_INT >= 33
        engine = when { onDevice -> "on-device"; google != null -> google.packageName; else -> "default" }
        l.onStatus("Recognisers: " + services.joinToString(", ") { it.packageName } + " → using $engine")
        sr = (when {
            onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            google != null -> SpeechRecognizer.createSpeechRecognizer(ctx, google)
            else -> SpeechRecognizer.createSpeechRecognizer(ctx)
        }).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) { deliver(b); if (!segmented) restart(60) }
                override fun onSegmentResults(b: Bundle) { deliver(b) }
                override fun onEndOfSegmentedSession() { if (running) restart(100) }
                override fun onPartialResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        if (it.isNotBlank() && it != lastPartial) { lastPartial = it; lastVoice = System.currentTimeMillis(); l.onPartial(it) }
                    }
                }
                override fun onError(error: Int) {
                    if (!running) return
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { l.onStatus("Microphone permission not granted"); running = false }
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { errors = 0; restart(150) }   // quiet room: normal
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> restart(800)
                        else -> { errors++; l.onStatus("Recogniser error $error"); restart(minOf(8000L, 500L shl minOf(errors, 4))) }
                    }
                }
                override fun onRmsChanged(rms: Float) {
                    l.onLevel(((rms + 2) / 12f).coerceIn(0f, 1f))
                    if (rms > 4f) lastVoice = System.currentTimeMillis()
                }
                override fun onReadyForSpeech(p: Bundle?) { errors = 0; l.onStatus(if (segmented) "Listening ($engine, continuous)" else "Listening ($engine)") }
                override fun onBeginningOfSpeech() { lastVoice = System.currentTimeMillis() }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        listen()
    }.let { }

    private fun deliver(b: Bundle?) {
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
            lastPartial = ""
            if (it.isNotBlank()) { lastVoice = System.currentTimeMillis(); l.onFinal("?", it) }
        }
    }

    /** Start (or restart) listening. In the non-segmented fallback the system chime is muted for the restart. */
    private fun listen() {
        val s = sr ?: return
        try { am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.ADJUST_MUTE, 0) } catch (_: Exception) {}
        try { am.adjustStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.ADJUST_MUTE, 0) } catch (_: Exception) {}
        try { s.startListening(intent()) } catch (e: Exception) { l.onStatus("Recogniser failed: ${e.message?.take(60)}"); restart(2000) }
    }

    private fun restart(delay: Long) = main.postDelayed({ if (running) { try { sr?.cancel() } catch (_: Exception) {}; listen() } }, delay)

    override fun stop() = main.post {
        running = false
        try { sr?.cancel(); sr?.destroy() } catch (_: Exception) {}
        sr = null
        try { am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        try { am.adjustStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
    }.let { }
}
