package com.example.jarviswalkingassistant.core

/**
 * The spoken teleprompter. Whispers an answer into Dale's ear one phrase ahead of where he is.
 *
 *  - PHRASE mode: the whole answer, cut into phrases of ~6–10 words. He hears phrase 1; the moment he's
 *    most of the way through it, he hears phrase 2. Never two ahead.
 *  - CUE mode: just the first few words of each point — easier on the brain, more natural; he talks around them.
 *  - If he stops for a while without starting the phrase he was given, it's repeated quietly.
 *  - If he goes off the text (two misses in a row) it goes quiet and reports drift; the service can then add a
 *    continuation section and call [extend] to carry on.
 *
 * Call [onHeard] with every transcript (partials included — they're what make the pacing feel live) and
 * [tick] a few times a second.
 */
class WhisperAhead(private val speak: (String) -> Unit, private val log: (String) -> Unit = {}) {

    enum class Event { NONE, DRIFT, FINISHED }

    var phrases: List<String> = emptyList(); private set
    var follower: Follower? = null; private set
    var mode = Whisper.OFF; private set
    /** index of the last phrase whispered (-1 = nothing yet) */
    var whispered = -1; private set
    private var whisperedAt = 0L
    private var lastProgressAt = 0L
    private var lastRepeatAt = 0L
    private var repeats = 0
    var active = false; private set
    var adviceId = 0L; private set

    companion object {
        /** Cut spoken prose into phrases a person can hold in their head: 6–10 words, at natural seams. */
        fun phrasesOf(text: String): List<String> {
            val out = ArrayList<String>()
            for (sentence in text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }) {
                val words = sentence.split(Regex("\\s+"))
                if (words.size <= 11) { out += sentence; continue }
                // split at commas / semicolons / linking words, keeping chunks 5–11 words
                val parts = sentence.split(Regex("(?<=[,;:—–])\\s+|\\s+(?=(?:and|but|so|which|because|while|then|that means|the way)\\b)"))
                var cur = ""
                for (p in parts) {
                    val cw = cur.split(Regex("\\s+")).count { it.isNotBlank() }
                    val pw = p.split(Regex("\\s+")).count { it.isNotBlank() }
                    if (cur.isNotBlank() && cw + pw > 11) { out += cur.trim(); cur = p } else cur = (cur + " " + p).trim()
                }
                if (cur.isNotBlank()) out += cur.trim()
            }
            // merge tiny tails into the previous phrase
            val merged = ArrayList<String>()
            for (p in out) {
                if (merged.isNotEmpty() && p.split(" ").size < 4) merged[merged.size - 1] = merged.last() + " " + p else merged += p
            }
            return merged
        }

        fun cueLine(c: String): String {
            val w = c.replace(Regex("^[^:]{1,20}:\\s*"), "").split(Regex("\\s+")).filter { it.isNotBlank() }
            return w.take(5).joinToString(" ")
        }

        fun phrasesFor(a: Advice, mode: Whisper, section: Section? = null): List<String> {
            val say = section?.say ?: a.say; val full = section?.full ?: a.full
            val cues = section?.cues ?: a.cues; val points = section?.points ?: a.points; val headline = section?.headline ?: a.headline
            return when (mode) {
                Whisper.PHRASE -> {
                    val body = full.ifBlank { points.joinToString(" ") }
                    val ps = phrasesOf(body)
                    if (say.isNotBlank() && (ps.isEmpty() || Follower.ratio(ps[0].lowercase(), say.lowercase()) < 0.6)) listOf(say) + ps else ps
                }
                Whisper.CUE -> listOfNotNull(say.ifBlank { headline }.takeIf { it.isNotBlank() }) + (cues.ifEmpty { points }).map { cueLine(it) }
                Whisper.OFF -> emptyList()
            }
        }
    }

    fun start(a: Advice, m: Whisper) {
        mode = m; adviceId = a.id
        phrases = phrasesFor(a, m)
        follower = Follower(phrases)
        whispered = -1; active = phrases.isNotEmpty()
        lastProgressAt = System.currentTimeMillis()
        if (active) whisperNext()
        log("whisper-ahead: ${phrases.size} phrases ($m)")
    }

    /** A continuation section arrived: carry on into it. */
    fun extend(a: Advice, m: Whisper) {
        val sec = a.sections.lastOrNull() ?: return
        val more = phrasesFor(a, m, sec)
        if (more.isEmpty()) return
        val before = phrases.size
        phrases = phrases + more
        val f = Follower(phrases)
        f.jump(before); f.done += (0 until before); f.started = true
        follower = f
        whispered = before - 1; active = true
        lastProgressAt = System.currentTimeMillis()
        whisperNext()
    }

    fun stop() { active = false }

    private fun whisperNext() {
        val next = whispered + 1
        if (next >= phrases.size) { active = false; return }
        whispered = next; whisperedAt = System.currentTimeMillis(); repeats = 0
        speak(phrases[next])
    }

    /** Feed what the mic heard (partials welcome). Returns an event the service may need to act on. */
    fun onHeard(text: String): Event {
        if (!active) return Event.NONE
        val f = follower ?: return Event.NONE
        val st = f.hear(text) ?: return Event.NONE
        if (st.drift) { active = false; log("whisper-ahead: drift"); return Event.DRIFT }
        if (st.off > 0) return Event.NONE
        lastProgressAt = System.currentTimeMillis()
        // he's on (or past) the phrase we last gave him and well into it → give him the next one
        if (f.pos > whispered || (f.pos == whispered && f.frac >= 0.5)) {
            if (f.pos > whispered) { whispered = f.pos - 1 }   // he skipped ahead: catch up, then one ahead
            if (whispered + 1 >= phrases.size) { active = false; return Event.FINISHED }
            whisperNext()
        }
        return Event.NONE
    }

    /**
     * Call a few times a second. If he's gone quiet without starting the phrase, repeats it once after 3.5 s and once
     * more after that — then stops and goes quiet. It never nags: if he isn't delivering the answer, that's his call.
     */
    fun tick(now: Long = System.currentTimeMillis()) {
        if (!active || whispered < 0) return
        val f = follower ?: return
        val waiting = f.pos < whispered || (f.pos == whispered && f.frac < 0.2)
        if (!waiting) return
        if (now - maxOf(whisperedAt, lastProgressAt, lastRepeatAt) > 3500) {
            if (repeats >= 2) { active = false; log("whisper-ahead: no take-up, going quiet"); return }
            repeats++; lastRepeatAt = now
            speak(phrases[whispered])
        }
    }

    /** For the screen: current phrase index he is speaking, and the one whispered. */
    fun position(): Pair<Int, Int> = (follower?.pos ?: 0) to whispered
}
