package com.example.jarviswalkingassistant

import com.example.jarviswalkingassistant.core.Advice
import com.example.jarviswalkingassistant.core.Line
import com.example.jarviswalkingassistant.core.Style
import com.example.jarviswalkingassistant.core.Whisper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Meeting = full listening + cloud transcription. Day = light. Private = mic off. */
enum class Mode { MEETING, DAY, PRIVATE }

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, PAUSED }

/** Where Dale is in the answer he's delivering (teleprompter). */
data class Follow(
    val adviceId: Long = 0, val pos: Int = -1, val frac: Double = 0.0,
    val skipped: Set<Int> = emptySet(), val done: Set<Int> = emptySet(),
    val whispered: Int = -1, val drift: Boolean = false,
)

data class UiState(
    val mode: Mode = Mode.MEETING,
    val phase: Phase = Phase.IDLE,
    val running: Boolean = false,
    val venture: String = "AGENTGUARD",
    val partial: String = "",
    val lines: List<Line> = emptyList(),
    val advice: List<Advice> = emptyList(),
    val cursor: Int = 0,              // which advice the watch/screen is pointing at
    val meSpeaker: String? = null,    // diarisation label that is Dale
    val status: String = "Ready",
    val sttEngine: String = "",
    val level: Float = 0f,            // mic level 0..1 for the orb
    val lastLatencyMs: Long = 0,
    val commitments: List<String> = emptyList(),
    val style: Style = Style.CUES,
    val solo: Boolean = false,        // "Just me": everything I say is a question to Jarvis
    val ahead: Whisper = Whisper.OFF, // whisper-ahead mode
    val follow: Follow = Follow(),
    val pending: List<Line> = emptyList(),   // questions asked while I was mid-answer
    val lastQuestion: Line? = null,          // the last thing THEY said that looked like a question
)

object JarvisState {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun set(block: (UiState) -> UiState) = _ui.update(block)

    fun addLine(l: Line) = set { it.copy(lines = (it.lines + l).takeLast(200), partial = "") }

    /** Insert or replace (streaming partials replace by id). */
    fun putAdvice(a: Advice, keepPlace: Boolean = false) = set {
        val exists = it.advice.any { x -> x.id == a.id }
        val list = if (exists) it.advice.map { x -> if (x.id == a.id) a.copy(pinned = x.pinned, spoken = x.spoken || a.spoken) else x }
                   else (listOf(a) + it.advice).take(60)
        it.copy(advice = list, cursor = if (exists) it.cursor else if (keepPlace) (it.cursor + 1).coerceAtMost(59) else 0, lastLatencyMs = a.latencyMs,
            commitments = if (a.act == "commit" && !exists) it.commitments + a.card else it.commitments)
    }

    fun update(id: Long, f: (Advice) -> Advice) = set { s -> s.copy(advice = s.advice.map { if (it.id == id) f(it) else it }) }

    fun current(): Advice? = ui.value.advice.getOrNull(ui.value.cursor)
}
