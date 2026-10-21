package com.example.jarviswalkingassistant.core

/** One finished line of speech. speaker: "ME", "THEM <label>", or "SPEAKER" (unknown). */
data class Line(val speaker: String, val text: String, val ts: Long = System.currentTimeMillis())

/** A continuation added when Dale moves off the answer he was giving. */
data class Section(
    val say: String, val headline: String, val cues: List<String>, val points: List<String>, val full: String,
    val label: String,
)

data class Advice(
    val id: Long,
    val act: String,            // answer factcheck warn name tactic commit direct
    val kind: String,           // live | direct | answer | continue
    val who: String,            // who said the line that triggered it (ME / THEM …)
    val label: String,          // VERIFIED WEB UNVERIFIED BLOCKED
    val source: String,         // VERIFIED | WEB
    val say: String,            // the opening line, whispered ("" = screen only)
    val headline: String,
    val cues: List<String>,     // memory-joggers, in his words
    val points: List<String>,   // things to say, one per line
    val full: String,           // the whole answer as he'd say it
    val heard: String,
    val urgency: Int,
    val factIds: List<String>,
    val blocked: List<String>,
    val latencyMs: Long,
    val partial: Boolean = false,
    val sections: List<Section> = emptyList(),
    val pinned: Boolean = false,
    val spoken: Boolean = false,
    val ts: Long = System.currentTimeMillis(),
) {
    /** Compact text for small screens / notifications. */
    val card: String get() = (headline.ifBlank { say }) + if (points.isNotEmpty()) "\n• " + points.joinToString("\n• ") else ""
}

/** What the user is looking at: Say it (opener + cues) / Bullets / Full. */
enum class Style { CUES, BULLETS, FULL;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: CUES }
}

/** Whisper-ahead: nothing / cue-ahead (first words of each point) / phrase-ahead (the whole answer, a phrase at a time). */
enum class Whisper { OFF, CUE, PHRASE;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: OFF }
}

/** Settings the core needs; the phone and the watch each implement it over their own storage. */
interface CoreSettings {
    val anthropicKey: String
    val venture: String
    val model: String          // smart | max  (live listening is always fast)
    val web: Boolean
    val ipSafe: Boolean
    val context: String
    val style: Style
}
