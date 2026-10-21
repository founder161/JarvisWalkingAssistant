package com.example.jarviswalkingassistant

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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.example.jarviswalkingassistant.core.Advice
import com.example.jarviswalkingassistant.core.AndroidStt
import com.example.jarviswalkingassistant.core.AssemblyAiStt
import com.example.jarviswalkingassistant.core.Brain
import com.example.jarviswalkingassistant.core.ClaimsGate
import com.example.jarviswalkingassistant.core.Follower
import com.example.jarviswalkingassistant.core.Line
import com.example.jarviswalkingassistant.core.Section
import com.example.jarviswalkingassistant.core.Stt
import com.example.jarviswalkingassistant.core.SttListener
import com.example.jarviswalkingassistant.core.Style
import com.example.jarviswalkingassistant.core.Whisper
import com.example.jarviswalkingassistant.core.WhisperAhead
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The always-on part of Jarvis. Listens through the phone mic, thinks, and delivers to every surface:
 * hearing aids (opening line, or whisper-ahead phrase by phrase), watch (haptic + card),
 * cover screen / lock screen (live notification) and the main app.
 *
 * Same behaviour as the laptop version:
 *  - ANSWER: answer the last question they asked (no "Jarvis" needed)   - ASK_NEXT: the next thing I say is a question
 *  - Just-me mode: everything I say is a question                       - style: Say it / Bullets / Full
 *  - follows me as I deliver an answer; if I move off it, adds a continuation section underneath
 *  - questions that arrive while I'm mid-answer are kept pending (one buzz), answered when I press Answer
 *  - whisper-ahead: the answer in my ear a phrase (or a cue) ahead of where I am
 */
class JarvisService : Service(), SttListener, TextToSpeech.OnInitListener {

    companion object {
        const val ACTION = "jarvis_action"
        const val START_MEETING = "start_meeting"; const val START_DAY = "start_day"
        const val PRIVATE = "private"; const val TOGGLE_PAUSE = "toggle_pause"
        const val REPLAY = "replay"; const val NEXT = "next"; const val PREV = "prev"
        const val SILENCE = "silence"; const val END = "end"; const val MARK_ME = "mark_me"
        const val PIN = "pin"; const val VENTURE = "venture"; const val ASK = "ask"
        const val ANSWER = "answer"; const val ASK_NEXT = "ask_next"; const val SOLO = "solo"
        const val STYLE = "style"; const val AHEAD = "ahead"; const val READ = "read"; const val HUSH = "hush"
        private const val CH_LIVE = "jarvis_live"; private const val CH_ADVICE = "jarvis_advice"
        private const val NOTE_ID = 7

        fun send(ctx: Context, action: String, extra: String? = null) {
            val i = Intent(ctx, JarvisService::class.java).putExtra(ACTION, action)
            if (extra != null) i.putExtra("extra", extra)
            if (action == START_MEETING || action == START_DAY) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val thinkLock = Mutex()
    private lateinit var prefs: Prefs
    private lateinit var gate: ClaimsGate
    private lateinit var brain: Brain
    private var stt: Stt? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var speaking = false
    @Volatile private var pendingDirect: Line? = null
    @Volatile private var pendingLine: Line? = null
    @Volatile private var askNext = false
    @Volatile private var askedCount = 0          // ANSWER/ASK requests in flight → direct answers get priority

    // teleprompter
    private var follower: Follower? = null
    private var followId = 0L
    private var followKey = ""
    private var continueJob: Job? = null
    private var lastContinueAt = 0L
    private val ahead = WhisperAhead({ t -> speakAhead(t) }, { JarvisState.set { s -> s.copy(status = it) } })
    private var tickJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        gate = ClaimsGate(assets.open("facts_ledger.json").bufferedReader().readText())
        brain = Brain(gate, assets.open("brain_prompt.txt").bufferedReader().readText(), prefs)
        brain.wantFull = prefs.style == Style.FULL || prefs.ahead == Whisper.PHRASE || prefs.readFull
        tts = TextToSpeech(this, this)
        channels()
        JarvisState.set { it.copy(venture = prefs.venture, style = prefs.style, solo = prefs.solo, ahead = prefs.ahead) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ commands
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(ACTION)
        val extra = intent?.getStringExtra("extra")
        if (!JarvisState.ui.value.running && action != START_MEETING && action != START_DAY) {
            // Android won't let the microphone start from the background (watch/notification),
            // so commands only apply while Jarvis is running. Settings changes still save.
            when (action) {
                VENTURE -> extra?.let { prefs.venture = it; JarvisState.set { s -> s.copy(venture = it) } }
                STYLE -> setStyle(extra); AHEAD -> setAhead(extra); SOLO -> toggleSolo()
                ASK -> extra?.let { q -> startForegroundNow(); JarvisState.set { it.copy(running = true, mode = Mode.PRIVATE, phase = Phase.PAUSED, status = "Private — microphone off") }; onFinal("ME", "Jarvis, $q"); return START_STICKY }
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (action) {
            START_MEETING -> begin(Mode.MEETING)
            START_DAY -> begin(Mode.DAY)
            PRIVATE -> goPrivate()
            TOGGLE_PAUSE -> togglePause()
            REPLAY -> JarvisState.current()?.let { speak(it.say.ifBlank { it.card }, force = true) }
            NEXT -> move(+1); PREV -> move(-1)
            SILENCE, HUSH -> hush()
            END -> endMeeting()
            MARK_ME -> markMe()
            PIN -> pin()
            VENTURE -> extra?.let { v -> prefs.venture = v; JarvisState.set { it.copy(venture = v) }; pushWatch() }
            ASK -> extra?.let { onFinal("ME", "Jarvis, $it") }
            ANSWER -> answerLast()
            ASK_NEXT -> { askNext = !askNext; JarvisState.set { it.copy(status = if (askNext) "Go on — ask me" else "Listening") }; pushWatch() }
            SOLO -> toggleSolo()
            STYLE -> setStyle(extra)
            AHEAD -> setAhead(extra)
            READ -> JarvisState.current()?.let { a -> speak(a.full.ifBlank { (listOf(a.say) + a.points).joinToString(" ") }, force = true) }
        }
        return START_STICKY
    }

    private fun startForegroundNow() {
        val n = liveNotification("Jarvis", "Starting…", null)
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTE_ID, n)
    }

    private fun begin(mode: Mode) {
        startForegroundNow()
        stt?.stop()
        stt = if (prefs.assemblyKey.isNotBlank()) AssemblyAiStt(prefs.assemblyKey, this) else AndroidStt(this, this)
        JarvisState.set { it.copy(mode = mode, running = true, phase = Phase.LISTENING, sttEngine = stt!!.name,
            status = if (prefs.ready) "Listening" else "Add your Claude key in Settings") }
        stt!!.start()
        if (tickJob == null) tickJob = scope.launch { while (isActive) { delay(300); ahead.tick() } }
        refreshNotification()
        pushWatch()
    }

    private fun goPrivate() {
        stt?.stop(); stt = null
        tts?.stop(); ahead.stop()
        JarvisState.set { it.copy(mode = Mode.PRIVATE, phase = Phase.PAUSED, status = "Private — microphone off", partial = "") }
        refreshNotification(); pushWatch()
    }

    private fun togglePause() {
        val s = JarvisState.ui.value
        if (s.phase == Phase.PAUSED || s.mode == Mode.PRIVATE) begin(if (s.mode == Mode.DAY) Mode.DAY else Mode.MEETING)
        else { stt?.stop(); stt = null; tts?.stop(); ahead.stop(); setPhase(Phase.PAUSED, "Paused"); refreshNotification(); pushWatch() }
    }

    private fun hush() { tts?.stop(); ahead.stop(); speaking = false; setPhase(Phase.LISTENING); pushWatch() }

    private fun move(d: Int) {
        JarvisState.set { s -> if (s.advice.isEmpty()) s else s.copy(cursor = (s.cursor + d).coerceIn(0, s.advice.size - 1)) }
        JarvisState.current()?.let { armFollower(it, reset = true) }
        refreshNotification(); pushWatch(buzz = 0)
    }

    private fun pin() = JarvisState.set { s ->
        val cur = s.advice.getOrNull(s.cursor) ?: return@set s
        s.copy(advice = s.advice.map { if (it.id == cur.id) it.copy(pinned = !it.pinned) else it })
    }

    private fun toggleSolo() {
        prefs.solo = !prefs.solo
        JarvisState.set { it.copy(solo = prefs.solo, status = if (prefs.solo) "Just me — everything you say is a question" else "Listening") }
        pushWatch()
    }

    private fun setStyle(v: String?) {
        val st = if (v == null || v == "next") Style.entries[(prefs.style.ordinal + 1) % Style.entries.size] else Style.of(v)
        prefs.style = st
        brain.wantFull = st == Style.FULL || prefs.ahead == Whisper.PHRASE || prefs.readFull
        JarvisState.set { it.copy(style = st) }
        JarvisState.current()?.let { armFollower(it, reset = false) }
        refreshNotification(); pushWatch()
    }

    private fun setAhead(v: String?) {
        val w = if (v == null || v == "next") Whisper.entries[(prefs.ahead.ordinal + 1) % Whisper.entries.size] else Whisper.of(v)
        prefs.ahead = w
        brain.wantFull = prefs.style == Style.FULL || w == Whisper.PHRASE || prefs.readFull
        if (w == Whisper.OFF) ahead.stop()
        JarvisState.set { it.copy(ahead = w, status = when (w) { Whisper.OFF -> "Whisper-ahead off"; Whisper.CUE -> "Cue-ahead: first words of each point"; Whisper.PHRASE -> "Phrase-ahead: whole answer, a phrase ahead" }) }
        pushWatch()
    }

    /** ANSWER button: answer the last question they asked (pending ones first), no "Jarvis" needed. */
    private fun answerLast() {
        val s = JarvisState.ui.value
        val q = s.pending.firstOrNull() ?: s.lastQuestion
            ?: s.lines.lastOrNull { it.speaker != "ME" && Brain.looksLikeQuestion(it.text) }
            ?: s.lines.lastOrNull { it.speaker != "ME" }
        if (q == null) { JarvisState.set { it.copy(status = "Nothing to answer yet") }; return }
        JarvisState.set { it.copy(pending = it.pending.filter { p -> p !== q }, lastQuestion = null) }
        ask(q, "answer")
    }

    private fun ask(line: Line, kind: String) {
        if (!prefs.ready) { JarvisState.set { it.copy(status = "Add your Claude key in Settings") }; return }
        askedCount++
        hush()
        setPhase(Phase.THINKING, "Thinking…")
        scope.launch {
            thinkLock.withLock {
                try {
                    val a = brain.think(JarvisState.ui.value.lines, line, prefs.venture, kind) { p -> JarvisState.putAdvice(p); refreshNotification(); pushWatch(0) }
                    if (a != null) deliver(a, asked = true)
                } catch (e: Exception) {
                    JarvisState.set { it.copy(status = e.message?.take(80) ?: "Error") }
                } finally { askedCount--; if (!speaking) setPhase(Phase.LISTENING) }
            }
        }
    }

    private fun markMe() {
        val last = JarvisState.ui.value.lines.lastOrNull { it.speaker.startsWith("THEM") || it.speaker == "SPEAKER" } ?: return
        val label = last.speaker.removePrefix("THEM ").trim()
        JarvisState.set { s -> s.copy(meSpeaker = label, lines = s.lines.map { if (it.speaker == "THEM $label") it.copy(speaker = "ME") else it }) }
    }

    private fun endMeeting() {
        val lines = JarvisState.ui.value.lines
        stt?.stop(); stt = null; ahead.stop(); tts?.stop()
        setPhase(Phase.THINKING, "Writing meeting notes…")
        scope.launch {
            val notes = try {
                if (lines.size >= 3 && prefs.ready) brain.summarise(lines, prefs.venture) else "Not enough conversation to summarise."
            } catch (e: Exception) { "Summary failed: ${e.message}" }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.UK).format(Date())
            val dir = File(filesDir, "meetings").apply { mkdirs() }
            File(dir, "$stamp.txt").writeText(notes + "\n\n--- COMMITMENTS LOGGED LIVE ---\n" + JarvisState.ui.value.commitments.joinToString("\n"))
            JarvisState.set { it.copy(running = false, phase = Phase.IDLE, status = "Meeting notes saved", lines = emptyList(),
                commitments = emptyList(), meSpeaker = null, pending = emptyList(), follow = Follow()) }
            MeetingNotes.latest = notes
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------------ hearing
    override fun onPartial(text: String) {
        JarvisState.set { it.copy(partial = text) }
        // partials are what make the prompter feel live. The phone mic mostly hears me when I'm talking,
        // so while I'm delivering an answer every partial is treated as mine.
        if (follower != null && !speaking) feedFollower(text, partial = true)
    }
    override fun onLevel(level: Float) = JarvisState.set { it.copy(level = level) }
    override fun onStatus(status: String) = JarvisState.set { it.copy(status = status) }

    override fun onFinal(speaker: String, text: String) {
        val s = JarvisState.ui.value
        var me = s.meSpeaker
        val direct = Brain.DIRECT.containsMatchIn(text)
        if (me == null && direct && speaker != "?" && speaker != "ME") { me = speaker; JarvisState.set { it.copy(meSpeaker = speaker) } }
        val delivering = follower != null && (ahead.active || (System.currentTimeMillis() - followArmedAt) < 90_000)
        val who = when {
            speaker == "ME" -> "ME"
            me != null && speaker == me -> "ME"
            s.solo || askNext -> "ME"
            speaker == "?" && delivering && (follower?.hear(text)?.off ?: 1) == 0 -> "ME"   // unknown voice, matches my script → me
            speaker == "?" -> "SPEAKER"
            else -> "THEM $speaker"
        }
        val line = Line(who, text)
        JarvisState.addLine(line)
        if (s.phase == Phase.PAUSED && s.mode == Mode.PRIVATE && !direct && !s.solo && !askNext) return
        if (!prefs.ready) return

        // ---- me
        if (who == "ME") {
            if (askNext) { askNext = false; ask(line, "direct"); return }
            if (direct) { ask(line, "direct"); return }
            if (s.solo && Brain.worthACall(text)) { ask(line, "direct"); return }
            if (follower != null) { feedFollower(text, partial = false); return }
            // ordinary speech from me: live brain may still warn / fact-check
            pendingLine = line; scope.launch { drain() }
            return
        }
        // ---- them
        if (Brain.looksLikeQuestion(text) && Brain.worthACall(text)) {
            if (delivering || askedCount > 0 || speaking) {
                // don't interrupt me mid-answer: keep it, one buzz, it's first in line when I press Answer
                JarvisState.set { it.copy(pending = (it.pending + line).takeLast(5), lastQuestion = line) }
                pushWatch(buzz = 4); refreshNotification()
                return
            }
            JarvisState.set { it.copy(lastQuestion = line) }
        }
        pendingLine = line
        scope.launch { drain() }
    }

    /** Only one brain call at a time; while busy keep just the newest line (directs win). */
    private suspend fun drain() {
        if (!thinkLock.tryLock()) return
        try {
            while (true) {
                val line = pendingDirect ?: pendingLine ?: break
                if (line === pendingDirect) pendingDirect = null else pendingLine = null
                setPhase(Phase.THINKING)
                val advice = try { brain.think(JarvisState.ui.value.lines, line, prefs.venture) }
                             catch (e: Exception) { JarvisState.set { it.copy(status = e.message?.take(80) ?: "Error") }; null }
                if (advice != null) deliver(advice, asked = false)
                if (!speaking) setPhase(Phase.LISTENING)
            }
        } finally { thinkLock.unlock() }
    }

    // ------------------------------------------------------------------ teleprompter
    private var followArmedAt = 0L

    private fun armFollower(a: Advice, reset: Boolean) {
        val units = Follower.unitsOf(a, if (prefs.style == Style.CUES && a.full.isNotBlank() && prefs.ahead == Whisper.PHRASE) Style.FULL else prefs.style)
        val key = a.id.toString() + "|" + units.size + "|" + a.sections.size + "|" + prefs.style
        if (!reset && key == followKey && follower != null) return
        val old = follower
        val f = Follower(units)
        if (!reset && old != null && followId == a.id) { // same answer, more text (streaming / continuation): keep my place
            f.jump(old.pos); f.frac = old.frac; f.done += old.done; f.skipped += old.skipped; f.started = old.started
        }
        follower = f; followId = a.id; followKey = key; followArmedAt = System.currentTimeMillis()
        brain.onScreen = units.joinToString("\n")
        JarvisState.set { it.copy(follow = Follow(a.id, f.pos, f.frac, f.skipped, f.done, ahead.whispered, false)) }
    }

    private fun feedFollower(text: String, partial: Boolean) {
        val f = follower ?: return
        val st = f.hear(text) ?: return
        val a = JarvisState.ui.value.advice.firstOrNull { it.id == followId } ?: return
        if (prefs.ahead != Whisper.OFF && ahead.adviceId == a.id) {
            when (ahead.onHeard(text)) {
                WhisperAhead.Event.DRIFT -> if (!partial) continueFrom(a, text)
                WhisperAhead.Event.FINISHED -> JarvisState.set { it.copy(status = "Done") }
                else -> {}
            }
        } else if (st.drift && !partial) continueFrom(a, text)
        JarvisState.set { it.copy(follow = Follow(a.id, st.pos, st.frac, st.skipped, st.done, ahead.whispered, st.drift)) }
        if (st.moved) pushWatch(0)
    }

    /** I've moved off the answer: write the next section for where I'm going, underneath, without losing the original. */
    private fun continueFrom(a: Advice, said: String) {
        val now = System.currentTimeMillis()
        if (continueJob?.isActive == true || now - lastContinueAt < 8000 || !Brain.worthACall(said)) return
        lastContinueAt = now
        JarvisState.set { it.copy(status = "Following you…") }
        continueJob = scope.launch {
            thinkLock.withLock {
                try {
                    val more = brain.think(JarvisState.ui.value.lines, Line("ME", said), prefs.venture, "continue") ?: return@withLock
                    val sec = Section(more.say, more.headline, more.cues, more.points, more.full, "where you went")
                    JarvisState.update(a.id) { it.copy(sections = it.sections + sec) }
                    val updated = JarvisState.ui.value.advice.first { it.id == a.id }
                    armFollower(updated, reset = false)
                    follower?.let { f -> f.off = 0; val firstNew = Follower.unitsOf(a, prefs.style).size; f.jump(firstNew) }
                    if (prefs.ahead != Whisper.OFF) ahead.extend(updated, prefs.ahead)
                    refreshNotification(); pushWatch(1)
                } catch (e: Exception) { JarvisState.set { it.copy(status = e.message?.take(80) ?: "Error") } }
            }
        }
    }

    // ------------------------------------------------------------------ delivery
    private fun deliver(a: Advice, asked: Boolean) {
        JarvisState.putAdvice(a)
        refreshNotification(alert = a.urgency >= 1)
        val buzz = when (a.act) { "warn" -> 3; "factcheck" -> 2; else -> if (a.urgency >= 1) 1 else 0 }
        pushWatch(buzz)
        if (asked || a.act == "answer") armFollower(a, reset = true)
        if (asked && prefs.ahead != Whisper.OFF) {
            tts?.stop(); ahead.start(a, prefs.ahead)
            return
        }
        if (!prefs.whisper || a.say.isBlank() || a.urgency < 2) return
        scope.launch {
            // Wait for a natural pause (hearing aids add ~0.3-0.5 s), unless it's a warning or I asked for it
            val deadline = System.currentTimeMillis() + 7000
            if (a.urgency < 3 && !asked) {
                while (System.currentTimeMillis() < deadline && (stt?.silenceMs() ?: 9999) < 750) delay(120)
                if (System.currentTimeMillis() >= deadline) return@launch // missed the gap: screen + watch only
            }
            speak(if (asked && prefs.readFull && a.full.isNotBlank()) a.full else a.say)
            JarvisState.update(a.id) { it.copy(spoken = true) }
        }
    }

    private fun speak(text: String, force: Boolean = false) {
        if (!ttsReady || text.isBlank()) return
        if (!force && speaking) return
        tts?.setSpeechRate(prefs.speechRate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "j${System.currentTimeMillis()}")
    }

    /** Whisper-ahead phrases: a touch faster, always interrupt the previous phrase. */
    private fun speakAhead(text: String) {
        if (!ttsReady) return
        tts?.setSpeechRate(prefs.aheadRate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "a${System.currentTimeMillis()}")
        JarvisState.set { it.copy(follow = it.follow.copy(whispered = ahead.whispered)) }
        pushWatch(0)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        t.language = Locale.UK
        // Media route = A2DP to the Phonak aids. Never the call (SCO/HFP) route.
        t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        t.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "GB" }.thenByDescending { it.quality })
            ?.firstOrNull()?.let { t.voice = it }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true; setPhase(Phase.SPEAKING) }
            override fun onDone(id: String?) { speaking = false; setPhase(Phase.LISTENING) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking = false; setPhase(Phase.LISTENING) }
        })
        ttsReady = true
    }

    private fun setPhase(p: Phase, status: String? = null) = JarvisState.set {
        val keep = it.phase == Phase.PAUSED && p != Phase.LISTENING && p != Phase.PAUSED
        if (keep) it else it.copy(phase = p, status = status ?: it.status)
    }

    // ------------------------------------------------------------------ watch
    private fun pushWatch(buzz: Int = 0) {
        val s = JarvisState.ui.value
        val a = s.advice.getOrNull(s.cursor)
        val j = JSONObject().apply {
            put("buzz", buzz); put("mode", s.mode.name); put("phase", s.phase.name)
            put("venture", gate.label(s.venture)); put("index", s.cursor); put("total", s.advice.size)
            put("style", s.style.name); put("solo", s.solo); put("ahead", s.ahead.name); put("pending", s.pending.size)
            put("askNext", askNext)
            if (a != null) {
                put("act", a.act); put("label", a.label); put("card", a.card); put("say", a.say); put("headline", a.headline)
                val units = Follower.unitsOf(a, s.style)
                put("units", JSONArray(units)); put("pos", if (s.follow.adviceId == a.id) s.follow.pos else -1)
                put("whispered", if (ahead.adviceId == a.id) ahead.whispered else -1)
                put("partial", a.partial)
            }
        }
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@JarvisService).connectedNodes.await()
                val mc = Wearable.getMessageClient(this@JarvisService)
                nodes.forEach { mc.sendMessage(it.id, "/jarvis/advice", j.toString().toByteArray()).await() }
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ notifications (cover screen)
    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Jarvis listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ADVICE, "Jarvis answers", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
    }

    private fun refreshNotification(alert: Boolean = false) {
        val s = JarvisState.ui.value
        val a = s.advice.getOrNull(s.cursor)
        val pend = if (s.pending.isNotEmpty()) " · ${s.pending.size} waiting" else ""
        val title = when {
            s.mode == Mode.PRIVATE -> "Jarvis · Private"
            s.phase == Phase.PAUSED -> "Jarvis · Paused"
            a == null -> "Jarvis · ${gate.label(s.venture)} · listening$pend"
            else -> "${badge(a.label)} ${a.act.uppercase()} · ${s.cursor + 1}/${s.advice.size}$pend"
        }
        val text = a?.let { when (s.style) { Style.FULL -> it.full.ifBlank { it.card }; Style.CUES -> (listOf(it.say.ifBlank { it.headline }) + it.cues.map { c -> "· $c" }).joinToString("\n"); else -> it.card } } ?: s.status
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, liveNotification(title, text, a, alert))
    }

    private fun badge(label: String) = when (label) { "VERIFIED" -> "🟢"; "WEB" -> "🔵"; "UNVERIFIED" -> "🟠"; else -> "🔴" }

    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, JarvisService::class.java).putExtra(ACTION, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun liveNotification(title: String, text: String, a: Advice?, alert: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val paused = JarvisState.ui.value.phase == Phase.PAUSED
        return NotificationCompat.Builder(this, if (alert) CH_ADVICE else CH_LIVE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(!alert).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(when (a?.label) { "VERIFIED" -> 0xFF22C55E.toInt(); "WEB" -> 0xFF3B82F6.toInt(); "UNVERIFIED" -> 0xFFF59E0B.toInt(); else -> 0xFF7C5CFF.toInt() })
            .setContentIntent(open)
            .addAction(0, "Answer", pi(ANSWER, 1))
            .addAction(0, "Ask", pi(ASK_NEXT, 2))
            .addAction(0, if (paused) "Resume" else "Pause", pi(TOGGLE_PAUSE, 3))
            .build()
    }

    override fun onDestroy() {
        stt?.stop(); tts?.shutdown(); scope.cancel(); super.onDestroy()
    }
}

object MeetingNotes { @Volatile var latest: String = "" }
