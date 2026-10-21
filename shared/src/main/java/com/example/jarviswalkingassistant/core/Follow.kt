package com.example.jarviswalkingassistant.core

/**
 * Works out where Dale is in the text he's delivering, from what the mic hears. Port of the laptop's follow.py.
 *  - anchors on distinctive words (mishearings don't throw it) · forward only · holds when unsure
 *  - paraphrase-tolerant (stems, numbers said in words, near-spellings)
 *  - jumping past lines marks them SKIPPED, not lost · two misses in a row = he's off the text (drift)
 */
class Follower(val units: List<String>) {
    companion object {
        private val STOP = ("a an and are as at be but by for from has have i if in into is it its of on or our so that the their them " +
            "there these they this to was we were what when which who will with you your can do does not no yes about been more " +
            "than also only very just any all how much many im ive were theyre thats its weve youre dont isnt arent").split(" ").toSet()
        private val NUM = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
            "eight" to "8", "nine" to "9", "ten" to "10", "million" to "m", "thousand" to "k", "grand" to "k", "billion" to "bn", "percent" to "%")

        fun stem(w: String): String {
            for (suf in listOf("ings", "ing", "ies", "ers", "es", "ed", "ly", "s"))
                if (w.length > suf.length + 3 && w.endsWith(suf)) return w.dropLast(suf.length) + (if (suf == "ies") "y" else "")
            return w
        }
        fun words(text: String): List<String> {
            var t = text.lowercase().replace("’", "").replace("'", "")
            t = t.replace(Regex("£\\s?([\\d.]+)\\s?m\\b"), "$1m").replace(Regex("£\\s?([\\d.]+)\\s?k\\b"), "$1k")
            return Regex("[a-z0-9.%]+").findAll(t).map { it.value.trim('.') }.filter { it.isNotEmpty() && it !in STOP }
                .map { stem(NUM[it] ?: it) }.toList()
        }
        fun close(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.length < 4 || b.length < 4) return false
            return ratio(a, b) >= 0.8
        }
        /** difflib-style similarity on characters (good enough for near-spellings). */
        fun ratio(a: String, b: String): Double {
            val m = lcs(a, b); return 2.0 * m / (a.length + b.length)
        }
        private fun lcs(a: String, b: String): Int {
            val dp = IntArray(b.length + 1)
            for (i in 1..a.length) { var prev = 0
                for (j in 1..b.length) { val tmp = dp[j]; dp[j] = if (a[i - 1] == b[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1]); prev = tmp } }
            return dp[b.length]
        }

        /** The lines Dale is delivering, in the order shown, for the current view (plus any continuation sections). */
        fun unitsOf(a: Advice, style: Style): List<String> {
            fun one(say: String, headline: String, cues: List<String>, points: List<String>, full: String): List<String> = when {
                style == Style.FULL && full.isNotBlank() -> full.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
                style == Style.CUES -> listOfNotNull(say.ifBlank { headline }.takeIf { it.isNotBlank() }) + (cues.ifEmpty { points.take(4) })
                else -> listOfNotNull(headline.takeIf { it.isNotBlank() }) + points
            }
            return one(a.say, a.headline, a.cues, a.points, a.full) + a.sections.flatMap { one(it.say, it.headline, it.cues, it.points, it.full) }
        }
    }

    data class State(val pos: Int, val frac: Double, val moved: Boolean, val off: Int, val drift: Boolean,
                     val skipped: Set<Int>, val done: Set<Int>)

    private val uw = units.map { words(it) }
    private val weight: Map<String, Double>
    var pos = 0; var frac = 0.0
    val skipped = HashSet<Int>(); val done = HashSet<Int>()
    var off = 0
    var started = false

    init {
        val counts = HashMap<String, Int>()
        for (ws in uw) for (w in ws.toSet()) counts[w] = (counts[w] ?: 0) + 1
        weight = counts.mapValues { (_, c) -> if (c == 1) 3.0 else if (c == 2) 1.5 else 1.0 }
    }

    private data class Score(val cov: Double, val frac: Double, val hits: Int, val real: Int)

    private fun score(heard: List<String>, i: Int): Score {
        val ws = uw[i]; if (ws.isEmpty() || heard.isEmpty()) return Score(0.0, 0.0, 0, 0)
        val matched = HashSet<Int>()
        for (h in heard) for (j in ws.indices) if (j !in matched && close(h, ws[j])) { matched += j; break }
        val hitW = matched.sumOf { weight[ws[it]] ?: 1.0 }
        val total = ws.sumOf { weight[it] ?: 1.0 }
        val last = matched.maxOrNull() ?: -1
        val real = matched.count { ws[it].all { c -> c.isLetter() } && ws[it].length >= 4 }
        return Score(hitW / maxOf(total, 1e-6), if (last >= 0) (last + 1.0) / ws.size else 0.0, matched.size, real)
    }

    /** Feed one thing Dale said (final or partial). Null if too short to judge. */
    fun hear(text: String): State? {
        val heard = words(text); if (heard.size < 2) return null
        val n = units.size
        var best = 0.0; var bestI = -1; var bestFrac = 0.0; var bestHits = 0; var bestReal = 0
        for (i in pos until minOf(n, pos + 6)) {
            val sc = score(heard, i)
            var strength = sc.cov + 0.15 * sc.hits
            if (i > pos + 1) strength -= 0.15 * (i - pos - 1)
            if (strength > best) { best = strength; bestI = i; bestFrac = sc.frac; bestHits = sc.hits; bestReal = sc.real }
        }
        if (bestI < 0 || best < 0.35 || bestHits < 2 || bestReal < 1) {
            off++; return State(pos, frac, false, off, off >= 2, skipped, done)
        }
        off = 0; started = true
        var moved = bestI != pos || bestFrac > frac + 0.15
        if (bestI > pos) {
            for (k in pos until bestI) if (k !in done) { if (k != pos || frac < 0.5) skipped += k else done += k }
            pos = bestI; frac = bestFrac
        } else frac = maxOf(frac, bestFrac)
        if (frac >= 0.85 && pos < n - 1) { done += pos; skipped -= pos; pos++; frac = 0.0; moved = true }
        return State(pos, frac, moved, 0, false, skipped, done)
    }

    fun jump(p: Int) { pos = p.coerceIn(0, units.size - 1); frac = 0.0 }
}
