package com.example.jarviswalkingassistant.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.example.jarviswalkingassistant.core.AndroidStt
import com.example.jarviswalkingassistant.core.AssemblyAiStt
import com.example.jarviswalkingassistant.core.Brain
import com.example.jarviswalkingassistant.core.ClaimsGate
import com.example.jarviswalkingassistant.core.Engine
import com.example.jarviswalkingassistant.core.Follower
import com.example.jarviswalkingassistant.core.JLog
import com.example.jarviswalkingassistant.core.Stt
import com.example.jarviswalkingassistant.core.SttListener
import com.example.jarviswalkingassistant.core.Style
import com.example.jarviswalkingassistant.core.Whisper
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * Jarvis running ON THE WATCH, no phone needed: watch mic → AssemblyAI (or the watch's own recogniser when there is
 * no AssemblyAI key) over the watch's own Wi-Fi/LTE → Claude → spoken to whatever is paired to the watch
 * (Phonaks / earbuds) as media audio. Same engine, same behaviour as the phone.
 */
class WatchService : Service(), SttListener, TextToSpeech.OnInitListener, Engine.Host {

    companion object {
        const val ACTION = "a"; const val START = "start"; const val STOP = "stop"
        const val ANSWER = "answer"; const val ASK_NEXT = "ask_next"; const val HUSH = "hush"; const val SOLO = "solo"
        const val STYLE = "style"; const val AHEAD = "ahead"; const val NEXT = "next"; const val PREV = "prev"
        const val REPLAY = "replay"; const val READ = "read"; const val ASK = "ask"
        private const val NOTE = 9
        /** observable so the screen redraws when the watch engine starts/stops */
        val runningFlow = MutableStateFlow(false)
        val running: Boolean get() = runningFlow.value
        @Volatile var engine: Engine? = null; private set

        fun send(ctx: Context, action: String, extra: String? = null) {
            val i = Intent(ctx, WatchService::class.java).putExtra(ACTION, action)
            if (extra != null) i.putExtra("x", extra)
            try {
                if (action == START) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { JLog.d("watch", "send($action) failed: $e") }
        }
    }

    private lateinit var prefs: WatchPrefs
    private var stt: Stt? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile override var speaking = false
    private var wake: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        JLog.install(this, "watch-service")
        prefs = WatchPrefs(this)
        val gate = ClaimsGate(assets.open("facts_ledger.json").bufferedReader().readText())
        val brain = Brain(gate, assets.open("brain_prompt.txt").bufferedReader().readText(), prefs)
        brain.log = { JLog.d("brain", it) }
        brain.wantFull = prefs.style == Style.FULL || prefs.ahead == Whisper.PHRASE || prefs.readFull
        engine = Engine(brain, prefs, this)
        tts = TextToSpeech(this, this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("jarvis_watch", "Jarvis on watch", NotificationManager.IMPORTANCE_LOW))
        JLog.d("watch", "service created; claudeKey=${if (prefs.ready) "yes" else "NO"} assemblyKey=${if (prefs.assemblyKey.isNotBlank()) "yes" else "no"} venture=${prefs.venture}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = engine ?: return START_NOT_STICKY
        val x = intent?.getStringExtra("x")
        val action = intent?.getStringExtra(ACTION)
        if (action != null) JLog.d("watch", "cmd $action${x?.let { ":$it" } ?: ""}")
        when (action) {
            START -> start()
            STOP -> { stopAll(); stopSelf(); return START_NOT_STICKY }
            ANSWER -> e.answer()
            ASK_NEXT -> e.askNext()
            HUSH -> e.hush()
            SOLO -> { prefs.solo = !prefs.solo; changed() }
            STYLE -> { prefs.style = if (x == null || x == "next") Style.entries[(prefs.style.ordinal + 1) % 3] else Style.of(x); e.styleChanged() }
            AHEAD -> { prefs.ahead = if (x == null || x == "next") Whisper.entries[(prefs.ahead.ordinal + 1) % 3] else Whisper.of(x); e.aheadChanged() }
            NEXT -> e.move(+1); PREV -> e.move(-1)
            REPLAY -> e.replay(); READ -> e.read()
            ASK -> x?.let { e.ask(it) }
            null -> { /* restarted by the system after being killed: keep the notification honest */
                if (running) start() else { stopSelf(); return START_NOT_STICKY } }
        }
        return START_STICKY
    }

    private fun start() {
        val n = note("Jarvis listening on the watch")
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTE, n)
        } catch (e: Exception) {
            JLog.d("watch", "startForeground failed: $e")
            status("Can't start: ${e.message?.take(60)} — allow the microphone for Jarvis in watch Settings → Apps")
            return
        }
        if (wake == null) wake = (getSystemService(PowerManager::class.java)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:watch")
        if (wake?.isHeld != true) wake?.acquire(4 * 60 * 60 * 1000L)
        requestFastNetwork()
        stt?.stop()
        if (!prefs.ready) {
            runningFlow.value = true
            status("No Claude key on the watch yet — open Jarvis on the phone once (Setup → Save)")
            JLog.d("watch", "started without a Claude key")
            return
        }
        stt = if (prefs.assemblyKey.isNotBlank()) AssemblyAiStt(prefs.assemblyKey, this) else AndroidStt(this, this)
        JLog.d("watch", "mic: ${stt?.name}")
        stt?.start()
        runningFlow.value = true
        engine?.start()
        changed()
    }

    /**
     * When the phone is nearby, Wear OS sends the watch's internet through Bluetooth to the phone, which is far too
     * slow for live audio. Ask for Wi-Fi or LTE and pin the process to it while we're listening.
     */
    private fun requestFastNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val kind = when { caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "LTE"; else -> "network" }
                val ok = cm.bindProcessToNetwork(network)
                JLog.d("net", "using $kind (bound=$ok) ↓${caps?.linkDownstreamBandwidthKbps ?: 0}kbps ↑${caps?.linkUpstreamBandwidthKbps ?: 0}kbps")
            }
            override fun onLost(network: Network) { JLog.d("net", "fast network lost — back to Bluetooth via phone"); cm.bindProcessToNetwork(null) }
            override fun onUnavailable() { JLog.d("net", "no Wi-Fi/LTE available — using the phone's connection over Bluetooth (may be slow)") }
        }
        netCallback = cb
        try { cm.requestNetwork(req, cb, 10_000) } catch (e: Exception) { JLog.d("net", "requestNetwork failed: $e"); netCallback = null }
    }

    private fun releaseNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        netCallback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = null
        try { cm?.bindProcessToNetwork(null) } catch (_: Exception) {}
    }

    private fun stopAll() {
        stt?.stop(); stt = null
        engine?.stop()
        releaseNetwork()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        runningFlow.value = false
        WatchLink.local.value = WatchLink.local.value.copy(running = false, status = "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        JLog.d("watch", "stopped")
    }

    private fun status(s: String) { WatchLink.local.value = WatchLink.local.value.copy(running = true, local = true, status = s) }

    // ---- mic
    override fun onPartial(text: String) { engine?.onPartial(text); WatchLink.local.value = WatchLink.local.value.copy(heard = text) }
    override fun onFinal(speaker: String, text: String) { JLog.d("mic", "heard ${text.length} chars from $speaker"); engine?.onFinal(speaker, text) }
    override fun onLevel(level: Float) {}
    override fun onStatus(status: String) { JLog.d("mic", status); WatchLink.local.value = WatchLink.local.value.copy(status = status) }
    override fun silenceMs(): Long = stt?.silenceMs() ?: 9999

    // ---- voice
    override fun speak(text: String, ahead: Boolean) {
        if (text.isBlank()) return
        if (!ttsReady) { JLog.d("tts", "not ready — can't speak"); return }
        tts?.setSpeechRate(if (ahead) prefs.aheadRate else prefs.speechRate)
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "w${System.currentTimeMillis()}")
        if (r != TextToSpeech.SUCCESS) JLog.d("tts", "speak returned $r")
    }
    override fun stopSpeaking() { tts?.stop(); speaking = false }
    override fun buzz(kind: Int) = WatchLink.buzz(this, kind)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            JLog.d("tts", "init failed ($status) — no TTS engine on the watch")
            onStatus("No voice on this watch — install Google Text-to-speech"); return
        }
        val t = tts ?: return
        t.language = Locale.UK
        t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val v = t.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "GB" }.thenByDescending { it.quality })
            ?.firstOrNull()?.also { t.voice = it }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { speaking = false }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking = false; JLog.d("tts", "utterance error") }
        })
        ttsReady = true
        JLog.d("tts", "ready: engine=${t.defaultEngine} voice=${v?.name ?: "default"}")
    }

    // ---- state → screen
    override fun changed() {
        val e = engine ?: return
        val s = e.state
        val a = s.current
        if (s.status != WatchLink.local.value.status) JLog.d("engine", s.status)
        WatchLink.local.value = WatchState(
            act = a?.act ?: "", label = a?.label ?: "", card = a?.card ?: "", say = a?.say ?: "", headline = a?.headline ?: "",
            index = s.cursor, total = s.advice.size, phase = if (s.thinking) "THINKING" else "LISTENING", mode = "WATCH",
            venture = prefs.venture, connected = true,
            units = a?.let { Follower.unitsOf(it, prefs.style) } ?: emptyList(), pos = s.followPos, whispered = s.whispered,
            pending = s.pending.size, style = prefs.style.name, solo = prefs.solo, ahead = prefs.ahead.name,
            askNext = s.askNext, partial = a?.partial ?: false, running = true, status = s.status, local = true)
        getSystemService(NotificationManager::class.java).notify(NOTE, note(a?.let { (it.say.ifBlank { it.headline }) } ?: s.status))
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "jarvis_watch").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis · watch").setContentText(text).setOngoing(true).setSilent(true).setContentIntent(open).build()
    }

    override fun onDestroy() {
        stt?.stop(); engine?.destroy(); engine = null; tts?.shutdown(); releaseNetwork()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        runningFlow.value = false; super.onDestroy()
    }
}
