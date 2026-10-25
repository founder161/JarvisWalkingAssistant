package com.example.jarviswalkingassistant.core

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

/**
 * The whole Jarvis behaviour with no Android in it: feed it what the mic heard, it decides when to think,
 * what to show, what to say and when to buzz. The watch runs this on its own when the phone isn't there.
 *
 *  - answer(): answer the last question they asked · askNext(): the next thing I say is a question · solo: all mine are questions
 *  - follows me as I deliver an answer; if I go off it, adds a continuation section underneath (never loses the original)
 *  - questions that arrive mid-answer are kept pending (one short double-buzz) until I press Answer
 *  - whisper-ahead (CUE / PHRASE) in my ear, a phrase ahead of where I am
 */
class Engine(
    private val brain: Brain,
    private val settings: EngineSettings,
    private val host: Host,
) {
    interface EngineSettings : CoreSettings {
        val solo: Boolean
        val ahead: Whisper
        val readFull: Boolean
        val whisper: Boolean
    }

    interface Host {
        fun speak(text: String, ahead: Boolean)          // ahead = whisper-ahead phrase (faster, always interrupts)
        fun stopSpeaking()
        fun buzz(kind: Int)                              // 1 answer 2 factcheck 3 careful 4 question waiting
        fun changed()                                    // state changed: redraw / push
        fun silenceMs(): Long
        val speaking: Boolean
    }

    data class State(
        val lines: List<Line> = emptyList(),
        val advice: List<Advice> = emptyList(),
        val cursor: Int = 0,
        val status: String = "Ready",
        val thinking: Boolean = false,
        val pending: List<Line> = emptyList(),
        val lastQuestion: Line? = null,
        val askNext: Boolean = false,
        val followPos: Int = -1, val followDone: Set<Int> = emptySet(), val followSkipped: Set<Int> = emptySet(),
        val whispered: Int = -1,
    ) { val current: Advice? get() = advice.getOrNull(cursor) }

    @Volatile var state = State(); private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    @Volatile private var pendingLine: Line? = null
    @Volatile private var askedCount = 0
    private var follower: Follower? = null
    private var followId = 0L
    private var followKey = ""
    private var followArmedAt = 0L
    private var continueJob: Job? = null
    private var lastContinueAt = 0L
    private val ahead: WhisperAhead = WhisperAhead({ t -> say(t, ahead = true); whisperedChanged() })
    private fun whisperedChanged() { val w = ahead.whispered; set { copy(whispered = w) } }
    private var tickJob: Job? = null

    /**
     * FOCUS: once Dale has asked for an answer (Answer / Ask / Just me / "Jarvis…") or moved to one, the screen and
     * his ear belong to that answer. Live listening carries on in the background — new advice is kept in the list
     * underneath, questions go to pending with a buzz — but nothing replaces what he's reading or cuts across what
     * he's hearing until he presses something or the room has been quiet for a while.
     */
    @Volatile private var focusUntil = 0L
    private val focused: Boolean get() = System.currentTimeMillis() < focusUntil
    private fun focus(ms: Long = 120_000) { focusUntil = System.currentTimeMillis() + ms }
    fun unfocus() { focusUntil = 0 }

    private inline fun set(f: State.() -> State) { state = state.f(); host.changed() }

    /** ECHO GUARD: what Jarvis said last and when, so its own voice coming back through the mic is never a question. */
    @Volatile private var lastSaid = ""
    @Volatile private var lastSaidUntil = 0L
    private fun say(text: String, ahead: Boolean) {
        lastSaid = text; lastSaidUntil = System.currentTimeMillis() + 2500 + text.length * 70L   // ~ speaking time + tail
        host.speak(text, ahead)
    }
    private fun isEcho(text: String): Boolean {
        if (host.speaking || System.currentTimeMillis() < lastSaidUntil) return true
        if (lastSaid.isBlank()) return false
        val a = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val b = lastSaid.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        return a.isNotEmpty() && a.intersect(b).size.toDouble() / a.size > 0.6
    }

    fun start() {
        if (tickJob == null) tickJob = scope.launch { while (isActive) { delay(300); ahead.tick() } }
        set { copy(status = if (settings.anthropicKey.startsWith("sk-")) "Listening" else "No Claude key yet — open Jarvis on the phone once") }
    }
    fun stop() { ahead.stop(); host.stopSpeaking(); tickJob?.cancel(); tickJob = null }
    fun destroy() { stop(); scope.cancel() }

    // ------------------------------------------------------------ controls
    fun hush() { host.stopSpeaking(); ahead.stop(); focusUntil = 0; set { copy(status = "Listening") } }
    fun askNext() { set { copy(askNext = !askNext, status = if (!askNext) "Go on — ask me" else "Listening") } }
    fun move(d: Int) { focus(); set { if (advice.isEmpty()) this else copy(cursor = (cursor + d).coerceIn(0, advice.size - 1)) }; state.current?.let { arm(it, reset = true) } }
    fun replay() { focus(); state.current?.let { say(it.say.ifBlank { it.card }, ahead = false) } }
    fun read() { focus(); state.current?.let { a -> say(a.full.ifBlank { (listOf(a.say) + a.points).joinToString(" ") }, ahead = false) } }
    fun styleChanged() { brain.wantFull = settings.style == Style.FULL || settings.ahead == Whisper.PHRASE || settings.readFull; state.current?.let { arm(it, reset = false) } }
    fun aheadChanged() { styleChanged(); if (settings.ahead == Whisper.OFF) ahead.stop() }
    fun ask(text: String) = ask(Line("ME", "Jarvis, $text"), "direct")

    /** ANSWER: the last question they asked — pending ones first. No "Jarvis" needed. */
    fun answer() {
        val s = state
        val q = s.pending.firstOrNull() ?: s.lastQuestion
            ?: s.lines.lastOrNull { it.speaker != "ME" && Brain.looksLikeQuestion(it.text) }
            ?: s.lines.lastOrNull { it.speaker != "ME" }
        if (q == null) { set { copy(status = "Nothing to answer yet") }; return }
        set { copy(pending = pending.filter { it !== q }, lastQuestion = null) }
        ask(q, "answer")
    }

    private fun ask(line: Line, kind: String) {
        if (!settings.anthropicKey.startsWith("sk-")) { set { copy(status = "No Claude key on the watch yet") }; return }
        askedCount++
        hush()
        set { copy(thinking = true, status = "Thinking…") }
        scope.launch {
            lock.withLock {
                try {
                    val a = brain.think(state.lines, line, settings.venture, kind) { p -> put(p) }
                    if (a != null) deliver(a, asked = true)
                } catch (e: Exception) { set { copy(status = e.message?.take(80) ?: "Error") } }
                finally { askedCount--; set { copy(thinking = false, status = if (status == "Thinking…") "Listening" else status) } }
            }
        }
    }

    // ------------------------------------------------------------ hearing
    fun onPartial(text: String) { if (follower != null && !host.speaking) feed(text, partial = true) }

    /** speaker: "ME", "?" (unknown) or a diarisation label. */
    fun onFinal(speaker: String, text: String) {
        // ECHO vs DELIVERY: while the voice is actually playing, nothing heard counts. Just after it has spoken, or when
        // the words match what it said, that's either an echo or Dale reading the answer back — if the prompter is armed
        // it's his delivery (feed it), otherwise it's an echo (drop it).
        if (host.speaking) return
        if (isEcho(text)) { if (follower != null) feed(text, partial = false); return }
        val s = state
        val direct = Brain.DIRECT.containsMatchIn(text)
        val delivering = follower != null && (ahead.active || System.currentTimeMillis() - followArmedAt < 90_000)
        val who = when {
            speaker == "ME" || settings.solo || s.askNext -> "ME"
            speaker == "?" && delivering && (follower?.hear(text)?.off ?: 1) == 0 -> "ME"
            speaker == "?" -> "SPEAKER"
            else -> "THEM $speaker"
        }
        val line = Line(who, text)
        set { copy(lines = (lines + line).takeLast(200)) }
        if (!settings.anthropicKey.startsWith("sk-")) return
        if (who == "ME") {
            if (s.askNext) { set { copy(askNext = false) }; ask(line, "direct"); return }
            if (direct) { ask(line, "direct"); return }
            if (settings.solo && Brain.worthACall(text)) { ask(line, "direct"); return }
            if (follower != null) { feed(text, partial = false); return }
            pendingLine = line; scope.launch { drain() }; return
        }
        if (Brain.looksLikeQuestion(text) && Brain.worthACall(text)) {
            if (delivering || askedCount > 0 || host.speaking) {
                set { copy(pending = (pending + line).takeLast(5), lastQuestion = line) }
                host.buzz(4); return
            }
            set { copy(lastQuestion = line) }
        }
        pendingLine = line; scope.launch { drain() }
    }

    private suspend fun drain() {
        if (!lock.tryLock()) return
        try {
            while (true) {
                val line = pendingLine ?: break
                pendingLine = null
                set { copy(thinking = true) }
                val a = try { brain.think(state.lines, line, settings.venture) } catch (e: Exception) { set { copy(status = e.message?.take(80) ?: "Error") }; null }
                if (a != null) deliver(a, asked = false)
                set { copy(thinking = false) }
            }
        } finally { lock.unlock() }
    }

    // ------------------------------------------------------------ prompter
    private fun arm(a: Advice, reset: Boolean) {
        val units = Follower.unitsOf(a, settings.style)
        val key = "${a.id}|${units.size}|${a.sections.size}|${settings.style}"
        if (!reset && key == followKey && follower != null) return
        val old = follower
        val f = Follower(units)
        if (!reset && old != null && followId == a.id) { f.jump(old.pos); f.frac = old.frac; f.done += old.done; f.skipped += old.skipped; f.started = old.started }
        follower = f; followId = a.id; followKey = key; followArmedAt = System.currentTimeMillis()
        brain.onScreen = units.joinToString("\n")
        set { copy(followPos = f.pos, followDone = f.done.toSet(), followSkipped = f.skipped.toSet()) }
    }

    private fun feed(text: String, partial: Boolean) {
        val f = follower ?: return
        val st = f.hear(text) ?: return
        val a = state.advice.firstOrNull { it.id == followId } ?: return
        if (settings.ahead != Whisper.OFF && ahead.adviceId == a.id) {
            when (ahead.onHeard(text)) {
                WhisperAhead.Event.DRIFT -> if (!partial) continueFrom(a, text)
                WhisperAhead.Event.FINISHED -> set { copy(status = "Done") }
                else -> {}
            }
        } else if (st.drift && !partial) continueFrom(a, text)
        set { copy(followPos = st.pos, followDone = st.done, followSkipped = st.skipped, whispered = ahead.whispered) }
    }

    private fun continueFrom(a: Advice, said: String) {
        val now = System.currentTimeMillis()
        if (continueJob?.isActive == true || now - lastContinueAt < 8000 || !Brain.worthACall(said)) return
        lastContinueAt = now
        set { copy(status = "Following you…") }
        continueJob = scope.launch {
            lock.withLock {
                try {
                    val more = brain.think(state.lines, Line("ME", said), settings.venture, "continue") ?: return@withLock
                    val sec = Section(more.say, more.headline, more.cues, more.points, more.full, "where you went")
                    val before = Follower.unitsOf(a, settings.style).size
                    set { copy(advice = advice.map { if (it.id == a.id) it.copy(sections = it.sections + sec) else it }) }
                    val updated = state.advice.first { it.id == a.id }
                    arm(updated, reset = false)
                    follower?.let { it.off = 0; it.jump(before) }
                    if (settings.ahead != Whisper.OFF) ahead.extend(updated, settings.ahead)
                    host.buzz(1)
                } catch (e: Exception) { set { copy(status = e.message?.take(80) ?: "Error") } }
            }
        }
    }

    // ------------------------------------------------------------ delivery
    private fun put(a: Advice, keepPlace: Boolean = false) = set {
        val exists = advice.any { it.id == a.id }
        copy(advice = if (exists) advice.map { if (it.id == a.id) a.copy(pinned = it.pinned, spoken = it.spoken || a.spoken) else it } else (listOf(a) + advice).take(40),
             cursor = if (exists) cursor else if (keepPlace) (cursor + 1).coerceAtMost(39) else 0)
    }

    private fun deliver(a: Advice, asked: Boolean) {
        val hold = !asked && focused && a.act != "warn"      // he's busy with an answer: file it underneath, don't take over
        put(a, keepPlace = hold)
        if (asked) focus()
        host.buzz(when (a.act) { "warn" -> 3; "factcheck" -> 2; else -> if (a.urgency >= 1 && !hold) 1 else 0 })
        if (asked || (a.act == "answer" && !hold)) arm(a, reset = true)
        if (asked && settings.ahead != Whisper.OFF) { host.stopSpeaking(); ahead.start(a, settings.ahead); return }
        if (!settings.whisper || a.say.isBlank() || a.urgency < 2) return
        if (hold) return                                       // never talk over the answer he's on
        scope.launch {
            val deadline = System.currentTimeMillis() + 7000
            if (a.urgency < 3 && !asked) {
                while (System.currentTimeMillis() < deadline && (host.silenceMs() < 750 || host.speaking)) delay(120)
                if (System.currentTimeMillis() >= deadline) return@launch
            }
            say(if (asked && settings.readFull && a.full.isNotBlank()) a.full else a.say, ahead = false)
            set { copy(advice = advice.map { if (it.id == a.id) it.copy(spoken = true) else it }) }
        }
    }
}
