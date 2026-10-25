package com.example.jarviswalkingassistant.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Jarvis brain — one call per turn. Same prompt, ledger and rules as the laptop (core/brain.py).
 *
 * kinds:  live     background listening, may stay silent
 *         direct   Dale asked (Ask button, typed, "Jarvis…", Just-me mode) — never silent
 *         answer   Dale pressed Answer: the words to say back to the question just put to him
 *         continue Dale has moved off the answer on screen — write the next section
 *
 * Answers stream in; `onPartial` gets the gated pieces as they arrive so the opening line can be
 * whispered before the rest is written.
 */
class Brain(val gate: ClaimsGate, private val promptTemplate: String, private val s: CoreSettings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val recentSay = ArrayDeque<String>()
    private val recentGiven = ArrayDeque<String>()
    private var nextId = System.currentTimeMillis()
    @Volatile var wantFull = true
    @Volatile var onScreen = ""          // what Dale is reading (for CONTINUE turns)
    var log: (String) -> Unit = {}

    companion object {
        val MODELS = mapOf("fast" to "claude-haiku-5-5", "smart" to "claude-sonnet-5-5", "max" to "claude-opus-5-5")
        private val ACTS = setOf("answer", "factcheck", "warn", "name", "tactic", "commit", "direct")
        private val FILLER = Regex("^(?:yeah|yes|no|ok(?:ay)?|right|sure|mm+|uh+|um+|cool|great|thanks?(?: you)?|thanks for watching|bye)[.!?]*$", RegexOption.IGNORE_CASE)
        val DIRECT = Regex("^\\s*(hey\\s+)?jarvis\\b", RegexOption.IGNORE_CASE)
        val DETAIL = Regex("\\b(detail|detailed|everything|all about|in depth|full|explain|walk me through|step by step)\\b", RegexOption.IGNORE_CASE)
        val QUESTION = Regex("\\?|^\\s*(what|what's|whats|how|why|when|where|who|which|can|could|would|will|is|are|do|does|did|have|has|tell me|explain|give me|walk me|talk me)\\b", RegexOption.IGNORE_CASE)
        private val OWN = Regex("\\b(we|we're|our|ours|us|my|i|i'm|agent ?guard|jarvis|sovereign|skv|leek venture|patent|patents|raise|raising|round|valuation|forecast|revenue|plan|runway|team|traction|investor|pitch)\\b", RegexOption.IGNORE_CASE)
        private val OUTSIDE = Regex("\\b(news|latest|today|current|currently|this week|who is|who's|what is|what's|company|competitor|market size|price|regulation|law|act|rule|policy|bank|fund|vc)\\b", RegexOption.IGNORE_CASE)
        private val ORDER = listOf("VERIFIED", "WEB", "UNVERIFIED", "BLOCKED")
        /** per model: index of the fastest request settings it accepted */
        private val speedOk = HashMap<String, Int>()

        fun worthACall(text: String): Boolean {
            val t = text.trim()
            if (FILLER.matches(t)) return false
            if (t.split(Regex("\\s+")).size < 4 && !t.contains("?")) return false
            return true
        }
        fun looksLikeQuestion(t: String) = QUESTION.containsMatchIn(t)
        fun needsWeb(q: String): Boolean {
            if (OWN.containsMatchIn(q) && !Regex("\\b(news|latest|competitor|market)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)) return false
            return OUTSIDE.containsMatchIn(q)
        }
    }

    fun systemPrompt(venture: String): String = promptTemplate
        .replace("{venture}", gate.label(venture))
        .replace("{facts}", gate.factsBlock(venture))
        .replace("{banned}", gate.bannedBlock(venture))
        .replace("{context}", s.context.ifBlank { "(none)" })

    /** Returns null when Jarvis should stay silent. Blocking — call off the main thread. */
    fun think(transcript: List<Line>, latest: Line, venture: String, kind: String = "live",
              onPartial: ((Advice) -> Unit)? = null): Advice? {
        val k = if (kind == "live" && DIRECT.containsMatchIn(latest.text)) "direct" else kind
        if (k == "live" && !worthACall(latest.text)) return null
        val asked = k != "live"
        val model = if (asked) MODELS[s.model] ?: MODELS.getValue("smart") else MODELS.getValue("fast")
        val detail = DETAIL.containsMatchIn(latest.text)
        val t0 = System.currentTimeMillis()
        val id = nextId++
        val user = userMessage(transcript, latest, k, detail)
        val web = asked && s.web && needsWeb(latest.text)
        var raw = call(model, systemPrompt(venture), user, k, detail, web,
            if (asked && onPartial != null) { txt -> partialFields(txt)?.let { f -> buildAdvice(f, k, latest, venture, id, t0, partial = true)?.let(onPartial) } } else null)
        var j = parseJson(raw)
        if (asked && (j == null || j.optString("act") == "silent" || (j.optString("headline").isBlank() && j.optString("full").isBlank()))) {
            log("brain: retry")
            raw = call(model, systemPrompt(venture), user + "\n\nYou returned nothing usable. Answer the question now. JSON only, starting with {.", k, detail, false, null)
            j = parseJson(raw) ?: JSONObject().put("act", "direct").put("source", "WEB").put("full", raw.take(1200))
        }
        if (j == null) return null
        return buildAdvice(j, k, latest, venture, id, t0, partial = false)
    }

    private fun buildAdvice(j: JSONObject, kind: String, latest: Line, venture: String, id: Long, t0: Long, partial: Boolean): Advice? {
        val asked = kind != "live"
        var act = j.optString("act", "silent")
        if (asked && (act.isBlank() || act == "silent")) act = if (kind == "answer") "answer" else "direct"
        if (act !in ACTS) return null
        val source = if (j.optString("source") == "WEB") "WEB" else "VERIFIED"
        fun g(t: String) = if (t.isBlank()) null else gate.check(t, venture, source, s.ipSafe)
        val pointsIn = j.optJSONArray("points").strings()
        val cuesIn = j.optJSONArray("cues").strings().take(6)
        var headlineIn = j.optString("headline").ifBlank { j.optString("card") }.trim()
        val fullIn = j.optString("full").trim()
        if (headlineIn.isBlank()) headlineIn = pointsIn.firstOrNull() ?: fullIn.substringBefore(". ").take(160)
        val sayIn = j.optString("say").trim().ifBlank { headlineIn }
        val hR = g(headlineIn); val fR = g(fullIn); val sR = g(sayIn)
        val pR = pointsIn.mapNotNull { g(it) }; val cR = cuesIn.mapNotNull { g(it) }
        val all = listOfNotNull(hR, fR, sR) + pR + cR
        val live = all.filter { it.label != "BLOCKED" }
        var label = if (live.isEmpty()) "BLOCKED" else source
        for (r in live) if (ORDER.indexOf(r.label) > ORDER.indexOf(label)) label = r.label
        val headline = hR?.text ?: ""
        val points = pR.filter { it.label != "BLOCKED" }.map { it.text }
        val cues = cR.filter { it.label != "BLOCKED" }.map { it.text.trimEnd('.') }
        val full = if (fR != null && fR.label != "BLOCKED") fR.text else ""
        var say = if (sR == null) "" else gate.spoken(sR)
        if (!partial) {
            if (say.isNotBlank() && !asked && recentSay.any { similar(it, say) }) say = ""
            if (!asked && headline.isNotBlank() && recentGiven.any { similar(it, headline) }) return null
            if (say.isNotBlank()) { recentSay.addLast(say); if (recentSay.size > 8) recentSay.removeFirst() }
            if (headline.isNotBlank()) { recentGiven.addLast(headline); if (recentGiven.size > 6) recentGiven.removeFirst() }
        }
        var urgency = if (asked) 2 else j.optInt("urgency", 1)
        if (label == "UNVERIFIED" && !asked) urgency = minOf(urgency, 1)
        return Advice(
            id = id, act = act, kind = kind, who = latest.speaker, label = label, source = source,
            say = say, headline = headline, cues = cues, points = points, full = full,
            heard = latest.text, urgency = urgency,
            factIds = j.optJSONArray("fact_ids").strings(),
            blocked = all.flatMap { it.blocked }.distinct(),
            latencyMs = System.currentTimeMillis() - t0, partial = partial,
        )
    }

    /** Settings → Test: one tiny call. Returns null when the key works, otherwise a short reason. Blocking. */
    fun testKey(): String? = try {
        if (!s.anthropicKey.startsWith("sk-")) "That doesn't look like a Claude key (should start sk-)"
        else { call(MODELS.getValue("fast"), "Reply with the single word OK.", "ping", "live", false, false, null, maxTokens = 5); null }
    } catch (e: Exception) {
        val m = e.message ?: "no reply"
        when {
            "401" in m -> "Key rejected by Claude (401) — check it was pasted in full"
            "429" in m -> "Key works but is rate-limited / out of credit (429)"
            "Unable to resolve" in m || "timeout" in m.lowercase() -> "No internet connection"
            else -> m.take(120)
        }
    }

    /** End-of-meeting summary, follow-ups and commitments. */
    fun summarise(transcript: List<Line>, venture: String): String {
        val sys = "You write Dale Williams' post-meeting notes. UK English. Plain text, no markdown symbols. " +
            "Sections: SUMMARY (3 lines), WHAT THEY WANT, COMMITMENTS (who/what/when), FOLLOW-UPS FOR DALE, " +
            "CLAIMS DALE MADE THAT NEED CHECKING. Only use the VERIFIED FACTS when restating Dale's venture facts.\n\n" +
            "VERIFIED FACTS:\n" + gate.factsBlock(venture)
        val user = "TRANSCRIPT:\n" + transcript.joinToString("\n") { "${it.speaker}: ${it.text}" }
        return call(MODELS.getValue("smart"), sys, user, "summary", false, false, null, maxTokens = 1500)
    }

    private fun userMessage(t: List<Line>, latest: Line, kind: String, detail: Boolean): String {
        val given = if (recentGiven.isEmpty()) "" else "\nALREADY TOLD DALE RECENTLY (don't repeat unless asked):\n- " + recentGiven.joinToString("\n- ")
        var task = when (kind) {
            "direct" -> {
                val q = latest.text.replace(Regex("^\\s*(hey\\s+)?jarvis[\\s,.:!-]*", RegexOption.IGNORE_CASE), "").ifBlank { latest.text }
                "TURN: ASKED. Dale is asking you directly: \"$q\"\n" + (if (detail) "He wants DETAIL — use the longer limits.\n" else "") + "Answer him fully. Never silent."
            }
            "answer" -> "TURN: ANSWER-THIS. Dale pressed Answer. The question put to him: \"${latest.text}\"\nGive him the answer he should give, in his voice. Never silent."
            "continue" -> "TURN: CONTINUE. Dale has moved off the answer on screen. What he's saying now: \"${latest.text}\"\n" +
                (if (onScreen.isNotBlank()) "ALREADY ON SCREEN (don't repeat):\n$onScreen\n" else "") + "Write the next section for where he's going, in his voice. Never silent."
            else -> "TURN: LIVE. Latest line: ${latest.text}\nDecide: silent unless it clearly helps him now."
        }
        task += if (!wantFull) "\nFORMAT: no prose — leave \"full\" empty (\"\")."
                else "\nHe may READ \"full\" ALOUD or have it whispered to him phrase by phrase: make it a real spoken paragraph that flows, with the opening line first, in his voice — the quality of \"full\" matters most."
        return "TRANSCRIPT (oldest first):\n" + t.takeLast(60).joinToString("\n") { "${it.speaker}: ${it.text}" } + given + "\n\n" + task + "\nJSON only."
    }

    private fun call(model: String, system: String, user: String, kind: String, detail: Boolean, web: Boolean,
                     onText: ((String) -> Unit)?, maxTokens: Int? = null): String {
        val asked = kind != "live"
        val tokens = maxTokens ?: (if (asked) (if (wantFull) (if (detail) 2000 else 1300) else (if (detail) 1300 else 800)) else (if (wantFull) 700 else 400))
        // Fastest request settings each model accepts, best first. Opus-class models only take adaptive thinking;
        // the others take between_tools / disabled. A refused setting is remembered per model so it's never retried.
        val ladders = if (model.contains("opus")) listOf(
            JSONObject().put("thinking", JSONObject().put("type", "adaptive")).put("output_config", JSONObject().put("effort", "low")),
            JSONObject().put("output_config", JSONObject().put("effort", "low")),
            JSONObject())
        else listOf(
            JSONObject().put("thinking", JSONObject().put("type", "between_tools")).put("output_config", JSONObject().put("effort", "low")),
            JSONObject().put("thinking", JSONObject().put("type", "disabled")).put("output_config", JSONObject().put("effort", "low")),
            JSONObject().put("thinking", JSONObject().put("type", "disabled")),
            JSONObject().put("output_config", JSONObject().put("effort", "low")),
            JSONObject())
        val start = speedOk[model] ?: 0
        for (i in start until ladders.size) {
            val body = JSONObject().apply {
                put("model", model); put("max_tokens", tokens)
                put("system", JSONArray().put(JSONObject().put("type", "text").put("text", system)
                    .put("cache_control", JSONObject().put("type", "ephemeral"))))
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                if (web) put("tools", JSONArray().put(JSONObject().apply {
                    put("type", "web_search_20250305"); put("name", "web_search"); put("max_uses", 3)
                    put("user_location", JSONObject().put("type", "approximate").put("country", "GB").put("timezone", "Europe/London"))
                }))
                if (onText != null) put("stream", true)
                val extra = ladders[i]; for (key in extra.keys()) put(key, extra.get(key))
            }
            val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", s.anthropicKey).addHeader("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                if (r.code == 400 && i < ladders.size - 1) {
                    val msg = r.body?.string()?.take(160) ?: ""
                    log("brain: $model refused speed setting $i ($msg)")
                    return@use
                }
                if (!r.isSuccessful) throw RuntimeException("Claude ${r.code}: ${r.body?.string()?.take(200)}")
                speedOk[model] = i
                if (onText != null) return streamText(r.body!!.source(), onText)
                val content = JSONObject(r.body?.string() ?: "{}").optJSONArray("content") ?: return ""
                val sb = StringBuilder()
                for (c in 0 until content.length()) { val b = content.getJSONObject(c); if (b.optString("type") == "text") sb.append(b.optString("text")) }
                return sb.toString()
            }
        }
        return ""
    }

    private fun streamText(src: okio.BufferedSource, onText: (String) -> Unit): String {
        val sb = StringBuilder(); var last = 0L
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val ev = try { JSONObject(line.substring(5).trim()) } catch (e: Exception) { continue }
            when (ev.optString("type")) {
                "content_block_delta" -> if (ev.optJSONObject("delta")?.optString("type") == "text_delta") {
                    sb.append(ev.getJSONObject("delta").optString("text"))
                    val now = System.currentTimeMillis()
                    if (now - last > 150) { last = now; onText(sb.toString()) }
                }
                "error" -> throw RuntimeException(ev.optJSONObject("error")?.toString() ?: "stream error")
            }
        }
        onText(sb.toString())
        return sb.toString()
    }

    // ------------------------------------------------------------------ parsing
    private fun parseJson(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        val starts = Regex("\\{\\s*\"act\"").findAll(raw).map { it.range.first }.toList().ifEmpty { raw.indices.filter { raw[it] == '{' } }
        for (i in starts.reversed()) {
            try { return JSONObject(raw.substring(i)) } catch (_: Exception) {}
            // truncated: salvage what arrived
            partialFields(raw.substring(i))?.let { if (it.has("headline") || it.has("full") || it.has("points")) return it }
        }
        return null
    }

    /** Fields that are fully written so far in a JSON answer that is still arriving. */
    private fun partialFields(raw: String): JSONObject? {
        val i = raw.indexOf('{'); if (i < 0) return null
        val frag = raw.substring(i); val out = JSONObject()
        for (k in listOf("act", "source", "say", "headline", "full")) {
            val m = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(frag) ?: continue
            try { out.put(k, JSONObject("{\"v\":\"${m.groupValues[1]}\"}").getString("v")) } catch (_: Exception) {}
        }
        for (k in listOf("cues", "points")) {
            val m = Regex("\"$k\"\\s*:\\s*\\[(.*)", RegexOption.DOT_MATCHES_ALL).find(frag) ?: continue
            var body = m.groupValues[1]
            val end = body.indexOf("\"]").let { if (it >= 0) it + 1 else if (body.trimStart().startsWith("]")) 0 else -1 }
            if (end >= 0) body = body.substring(0, end)
            val arr = JSONArray()
            for (p in Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body)) {
                try { JSONObject("{\"v\":\"${p.groupValues[1]}\"}").getString("v").let { if (it.isNotBlank()) arr.put(it) } } catch (_: Exception) {}
            }
            out.put(k, arr)
        }
        return if (out.has("source") && (out.has("say") || out.has("headline") || out.optJSONArray("points")?.length() ?: 0 > 0)) out else null
    }

    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList()

    private fun similar(a: String, b: String): Boolean {
        val wa = a.lowercase().split(" ").toSet(); val wb = b.lowercase().split(" ").toSet()
        val inter = wa.intersect(wb).size.toDouble(); val uni = wa.union(wb).size.toDouble()
        return uni > 0 && inter / uni > 0.7
    }
}
