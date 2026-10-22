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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.example.jarviswalkingassistant.core.AssemblyAiStt
import com.example.jarviswalkingassistant.core.Brain
import com.example.jarviswalkingassistant.core.ClaimsGate
import com.example.jarviswalkingassistant.core.Engine
import com.example.jarviswalkingassistant.core.Follower
import com.example.jarviswalkingassistant.core.Stt
import com.example.jarviswalkingassistant.core.SttListener
import com.example.jarviswalkingassistant.core.Style
import com.example.jarviswalkingassistant.core.Whisper
import java.util.Locale

/**
 * Jarvis running ON THE WATCH, no phone needed: watch mic → AssemblyAI over the watch's Wi-Fi/LTE → Claude → spoken
 * to whatever is paired to the watch (Phonaks / earbuds) as media audio. Same engine, same behaviour as the phone.
 */
class WatchService : Service(), SttListener, TextToSpeech.OnInitListener, Engine.Host {

    companion object {
        const val ACTION = "a"; const val START = "start"; const val STOP = "stop"
        const val ANSWER = "answer"; const val ASK_NEXT = "ask_next"; const val HUSH = "hush"; const val SOLO = "solo"
        const val STYLE = "style"; const val AHEAD = "ahead"; const val NEXT = "next"; const val PREV = "prev"
        const val REPLAY = "replay"; const val READ = "read"; const val ASK = "ask"
        private const val NOTE = 9
        @Volatile var running = false; private set
        @Volatile var engine: Engine? = null; private set

        fun send(ctx: Context, action: String, extra: String? = null) {
            val i = Intent(ctx, WatchService::class.java).putExtra(ACTION, action)
            if (extra != null) i.putExtra("x", extra)
            if (action == START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var prefs: WatchPrefs
    private var stt: Stt? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile override var speaking = false
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        prefs = WatchPrefs(this)
        val gate = ClaimsGate(assets.open("facts_ledger.json").bufferedReader().readText())
        val brain = Brain(gate, assets.open("brain_prompt.txt").bufferedReader().readText(), prefs)
        brain.wantFull = prefs.style == Style.FULL || prefs.ahead == Whisper.PHRASE || prefs.readFull
        engine = Engine(brain, prefs, this)
        tts = TextToSpeech(this, this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("jarvis_watch", "Jarvis on watch", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = engine ?: return START_NOT_STICKY
        val x = intent?.getStringExtra("x")
        when (intent?.getStringExtra(ACTION)) {
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
        }
        return START_STICKY
    }

    private fun start() {
        val n = note("Jarvis listening on the watch")
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTE, n)
        wake = (getSystemService(PowerManager::class.java)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:watch").also { it.acquire(4 * 60 * 60 * 1000L) }
        stt?.stop()
        if (prefs.assemblyKey.isBlank()) {
            WatchLink.local.value = WatchLink.local.value.copy(running = true, status = "No AssemblyAI key on the watch yet — open Jarvis on the phone once")
            running = true; return
        }
        stt = AssemblyAiStt(prefs.assemblyKey, this).also { it.start() }
        running = true
        engine?.start()
        changed()
    }

    private fun stopAll() {
        stt?.stop(); stt = null
        engine?.stop()
        try { wake?.release() } catch (_: Exception) {}
        running = false
        WatchLink.local.value = WatchLink.local.value.copy(running = false, status = "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- mic
    override fun onPartial(text: String) { engine?.onPartial(text); WatchLink.local.value = WatchLink.local.value.copy(heard = text) }
    override fun onFinal(speaker: String, text: String) { engine?.onFinal(speaker, text) }
    override fun onLevel(level: Float) {}
    override fun onStatus(status: String) { WatchLink.local.value = WatchLink.local.value.copy(status = status) }
    override fun silenceMs(): Long = stt?.silenceMs() ?: 9999

    // ---- voice
    override fun speak(text: String, ahead: Boolean) {
        if (!ttsReady || text.isBlank()) return
        tts?.setSpeechRate(if (ahead) prefs.aheadRate else prefs.speechRate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "w${System.currentTimeMillis()}")
    }
    override fun stopSpeaking() { tts?.stop(); speaking = false }
    override fun buzz(kind: Int) = WatchLink.buzz(this, kind)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { onStatus("No voice on this watch — install Google Text-to-speech"); return }
        val t = tts ?: return
        t.language = Locale.UK
        t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        t.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "GB" }.thenByDescending { it.quality })
            ?.firstOrNull()?.let { t.voice = it }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) { speaking = false }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking = false }
        })
        ttsReady = true
    }

    // ---- state → screen
    override fun changed() {
        val e = engine ?: return
        val s = e.state
        val a = s.current
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

    override fun onDestroy() { stt?.stop(); engine?.destroy(); engine = null; tts?.shutdown(); try { wake?.release() } catch (_: Exception) {}; running = false; super.onDestroy() }
}
