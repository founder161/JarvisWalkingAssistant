package com.example.jarviswalkingassistant.core

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Kotlin port of core/claims_gate.py — the same facts_ledger.json drives both.
 * Every answer passes check() before it is shown or spoken.
 */
class ClaimsGate(json: String) {

    data class Banned(val rx: Pattern, val reason: String, val sayInstead: String)
    data class Fact(val id: String, val text: String)
    data class Venture(
        val key: String, val label: String, val aliases: List<String>,
        val facts: List<Fact>, val banned: List<Banned>, val numbers: Set<String>
    )

    data class Result(
        val text: String,
        val label: String,              // VERIFIED / WEB / UNVERIFIED / BLOCKED
        val blocked: List<String>,
        val unverified: List<String>,
        val crossVenture: List<String>,
        val ipFixed: List<String>,
    )

    val ventures: LinkedHashMap<String, Venture> = linkedMapOf()
    private val globalBanned = mutableListOf<Banned>()
    private val prohibited = mutableListOf<String>()
    private val substitutes = mutableMapOf<String, String>()
    val version: String

    init {
        val root = JSONObject(json)
        version = root.optString("version")
        val vs = root.getJSONObject("ventures")
        for (key in vs.keys()) {
            val v = vs.getJSONObject(key)
            val facts = v.getJSONArray("facts").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { f -> Fact(f.getString("id"), f.getString("text")) } }
            }
            val banned = v.optJSONArray("banned")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).toBanned() }
            } ?: emptyList()
            val aliases = v.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            ventures[key] = Venture(key, v.getString("label"), aliases, facts, banned,
                numbersIn(facts.joinToString(" ") { it.text }))
        }
        root.optJSONArray("global_banned")?.let { a ->
            for (i in 0 until a.length()) globalBanned += a.getJSONObject(i).toBanned()
        }
        root.optJSONObject("ip_safe")?.let { ip ->
            ip.optJSONArray("prohibited_terms")?.let { a -> for (i in 0 until a.length()) prohibited += a.getString(i) }
            ip.optJSONObject("substitutes")?.let { s -> for (k in s.keys()) substitutes[k] = s.getString(k) }
        }
        prohibited.sortByDescending { it.length }
    }

    private fun JSONObject.toBanned() = Banned(
        Pattern.compile(getString("pattern"), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        getString("reason"), optString("say_instead", "")
    )

    fun label(venture: String) = if (venture == "ALL") "All ventures" else ventures[venture]?.label ?: venture

    fun factsBlock(venture: String): String =
        (if (venture == "ALL") ventures.values.flatMap { it.facts } else ventures[venture]?.facts ?: emptyList())
            .joinToString("\n") { "[${it.id}] ${it.text}" }

    fun bannedBlock(venture: String): String =
        (if (venture == "ALL") ventures.values.toList() else listOfNotNull(ventures[venture]))
            .flatMap { it.banned }.joinToString("\n") { "- NEVER claim: ${it.reason} Say instead: ${it.sayInstead}" }

    fun check(text: String, venture: String, source: String, ipSafe: Boolean = true): Result {
        var out = text
        val blocked = mutableListOf<String>()
        val unverified = mutableListOf<String>()
        val cross = mutableListOf<String>()
        val ipFixed = mutableListOf<String>()

        // 1. banned claims — banned everywhere, whatever venture is active
        val rules = globalBanned + ventures.values.flatMap { it.banned }
        for (b in rules) {
            if (b.rx.matcher(out).find()) {
                blocked += b.reason
                out = replaceSentence(out, b.rx, b.sayInstead)
            }
        }
        // 2. IP-safe wording
        if (ipSafe) {
            for (term in prohibited) {
                val rx = Pattern.compile("\\b" + Pattern.quote(term) + "s?\\b", Pattern.CASE_INSENSITIVE)
                if (rx.matcher(out).find()) {
                    ipFixed += term
                    out = rx.matcher(out).replaceAll(substitutes[term] ?: "hardware component")
                }
            }
        }
        // 3. figures must come from the facts
        if (source == "VERIFIED") {
            val allowed = if (venture == "ALL") ventures.values.flatMap { it.numbers }.toSet()
                          else ventures[venture]?.numbers ?: emptySet()
            for ((raw, norm) in numbersWithRaw(out)) if (norm !in allowed) unverified += raw
        }
        // 4. venture lock
        if (source == "VERIFIED" && venture != "ALL") {
            for (v in ventures.values) {
                if (v.key == venture) continue
                if (v.aliases.any { Pattern.compile("\\b" + Pattern.quote(it) + "\\b", Pattern.CASE_INSENSITIVE).matcher(out).find() }) {
                    cross += v.label
                }
            }
        }
        out = out.replace(Regex("\\s{2,}"), " ").trim(' ', '.', ';', ',')
        if (out.isNotEmpty() && !out.endsWith(".") && !out.endsWith("!") && !out.endsWith("?")) out += "."
        val label = when {
            !Regex("[A-Za-z]{3,}").containsMatchIn(out) -> { out = "Nothing verified to say — hold."; "BLOCKED" }
            unverified.isNotEmpty() || cross.isNotEmpty() -> "UNVERIFIED"
            else -> source
        }
        return Result(out, label, blocked, unverified, cross, ipFixed)
    }

    /** What may go into the ear. Unverified figures are never spoken. */
    fun spoken(r: Result): String = when (r.label) {
        "BLOCKED" -> ""
        "UNVERIFIED" -> if (r.crossVenture.isNotEmpty()) "Careful, that's the other venture. Check the screen."
                        else "Not verified — don't state figures. Check the screen."
        else -> r.text
    }

    companion object {
        private val NUM_RX = Pattern.compile(
            "(?<![\\w.])(?:[£$€]\\s?)?\\d[\\d,]*(?:\\.\\d+)?\\s?(?:%|percent|k\\b|m\\b|bn\\b|million\\b|billion\\b|thousand\\b)?",
            Pattern.CASE_INSENSITIVE
        )

        fun normalise(raw: String): String? {
            var s = raw.lowercase().replace(",", "").replace(" ", "")
            val money = s.any { it == '£' || it == '$' || it == '€' }
            s = s.trim('£', '$', '€')
            var mult = 1.0
            var pct = false
            for ((suf, m) in listOf("percent" to 0.0, "%" to 0.0, "thousand" to 1e3, "million" to 1e6,
                                    "billion" to 1e9, "bn" to 1e9, "k" to 1e3, "m" to 1e6)) {
                if (s.endsWith(suf)) {
                    s = s.dropLast(suf.length)
                    if (m == 0.0) pct = true else mult = m
                    break
                }
            }
            val v = (s.toDoubleOrNull() ?: return null) * mult
            if (pct) return fmt(v) + "%"
            if (!money && mult == 1.0 && v < 1000) return null
            if (!money && mult == 1.0 && v in 1900.0..2100.0) return null
            return fmt(v)
        }

        private fun fmt(v: Double): String =
            if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

        fun numbersWithRaw(text: String): List<Pair<String, String>> {
            val m = NUM_RX.matcher(text)
            val out = mutableListOf<Pair<String, String>>()
            while (m.find()) {
                val raw = m.group().trim()
                normalise(raw)?.let { out += raw to it }
            }
            return out
        }

        fun numbersIn(text: String): Set<String> = numbersWithRaw(text).map { it.second }.toSet()

        fun replaceSentence(text: String, rx: Pattern, replacement: String): String {
            val parts = text.split(Regex("(?<=[.!?])\\s+"))
            val out = mutableListOf<String>()
            var used = false
            for (p in parts) {
                if (rx.matcher(p).find()) {
                    if (replacement.isNotBlank() && !used) { out += replacement; used = true }
                } else out += p
            }
            return out.joinToString(" ")
        }
    }
}
