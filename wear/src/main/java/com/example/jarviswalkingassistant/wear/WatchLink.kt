package com.example.jarviswalkingassistant.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import com.example.jarviswalkingassistant.core.JLog

data class WatchState(
    val act: String = "", val label: String = "", val card: String = "", val say: String = "",
    val index: Int = 0, val total: Int = 0, val phase: String = "IDLE", val mode: String = "",
    val venture: String = "", val connected: Boolean = false,
    val headline: String = "", val units: List<String> = emptyList(), val pos: Int = -1, val whispered: Int = -1,
    val pending: Int = 0, val style: String = "CUES", val solo: Boolean = false, val ahead: String = "OFF",
    val askNext: Boolean = false, val partial: Boolean = false,
    val running: Boolean = false, val status: String = "", val local: Boolean = false, val heard: String = "",
)

object WatchLink {
    /** what the phone last sent */
    val state = MutableStateFlow(WatchState())
    /** what the watch's own engine shows (standalone mode) */
    val local = MutableStateFlow(WatchState())
    /** phone seen in the last 20 s */
    @Volatile var phoneSeenAt = 0L
    val phoneLive: Boolean get() = System.currentTimeMillis() - phoneSeenAt < 20_000
    private val scope = CoroutineScope(Dispatchers.IO)

    fun receive(ctx: Context, json: String) {
        val j = try { JSONObject(json) } catch (e: Exception) { JLog.d("link", "bad advice json"); return }
        if (!phoneLive) JLog.d("link", "phone connected (first message)")
        phoneSeenAt = System.currentTimeMillis()
        state.value = WatchState(
            act = j.optString("act"), label = j.optString("label"), card = j.optString("card"),
            say = j.optString("say"), index = j.optInt("index"), total = j.optInt("total"),
            phase = j.optString("phase"), mode = j.optString("mode"), venture = j.optString("venture"),
            connected = true, headline = j.optString("headline"),
            units = j.optJSONArray("units")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            pos = j.optInt("pos", -1), whispered = j.optInt("whispered", -1), pending = j.optInt("pending"),
            style = j.optString("style", "CUES"), solo = j.optBoolean("solo"), ahead = j.optString("ahead", "OFF"),
            askNext = j.optBoolean("askNext"), partial = j.optBoolean("partial"), running = j.optString("phase") != "IDLE")
        buzz(ctx, j.optInt("buzz"))
        val sig = "${j.optString("phase")}|${j.optString("style")}|${j.optString("ahead")}|${j.optBoolean("solo")}|${j.optBoolean("askNext")}|${j.optInt("index")}/${j.optInt("total")}"
        if (sig != lastSig) { lastSig = sig; JLog.d("link", "phone → $sig") }
    }
    private var lastSig = ""

    /** Haptic language: 1 = answer ready, 2 = fact-check, 3 = careful (you're about to say something wrong), 4 = they asked something — it's waiting. */
    fun buzz(ctx: Context, kind: Int) {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        val effect = when (kind) {
            1 -> VibrationEffect.createOneShot(70, 200)
            2 -> VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), intArrayOf(0, 220, 0, 220), -1)
            3 -> VibrationEffect.createWaveform(longArrayOf(0, 400, 120, 400), intArrayOf(0, 255, 0, 255), -1)
            4 -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), intArrayOf(0, 160, 0, 160), -1)
            else -> return
        }
        v.vibrate(effect)
    }

    /** Keys and settings pushed from the phone: store on the watch so it can run alone. */
    fun receiveKeys(ctx: Context, json: String) {
        val j = try { JSONObject(json) } catch (e: Exception) { JLog.d("link", "bad keys json"); return }
        val p = WatchPrefs(ctx)
        j.optString("anthropic").takeIf { it.isNotBlank() }?.let { p.anthropicKey = it }
        j.optString("assembly").takeIf { it.isNotBlank() }?.let { p.assemblyKey = it }
        j.optString("venture").takeIf { it.isNotBlank() }?.let { p.venture = it }
        j.optString("model").takeIf { it.isNotBlank() }?.let { p.model = it }
        if (j.has("context")) p.context = j.optString("context")
        if (j.has("web")) p.web = j.optBoolean("web"); if (j.has("ipSafe")) p.ipSafe = j.optBoolean("ipSafe")
        if (j.has("whisper")) p.whisper = j.optBoolean("whisper"); if (j.has("readFull")) p.readFull = j.optBoolean("readFull")
        if (j.has("speechRate")) p.speechRate = j.optDouble("speechRate").toFloat(); if (j.has("aheadRate")) p.aheadRate = j.optDouble("aheadRate").toFloat()
        local.value = local.value.copy(status = if (p.ready) "Keys received from phone ✓" else "Keys incomplete")
        JLog.d("link", "keys from phone: claude=${if (p.ready) "ok" else "MISSING"} assembly=${if (p.assemblyKey.isNotBlank()) "ok" else "none"} venture=${p.venture}")
    }

    /** Send the watch's diagnostic log to the phone, which saves it to Downloads/JarvisNova. */
    fun sendLog(ctx: Context, onResult: (String) -> Unit) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(app).connectedNodes.await()
                if (nodes.isEmpty()) { onResult("Phone not connected"); return@launch }
                val mc = Wearable.getMessageClient(app)
                val body = JLog.dump().toByteArray()
                nodes.forEach { mc.sendMessage(it.id, "/jarvis/log", body).await() }
                onResult("Sent to phone (${body.size / 1024} KB)")
            } catch (e: Exception) { onResult("Send failed: ${e.message?.take(60)}") }
        }
    }

    fun cmd(ctx: Context, command: String) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(app).connectedNodes.await()
                val mc = Wearable.getMessageClient(app)
                if (nodes.isEmpty()) JLog.d("link", "cmd '$command' — no phone connected")
                nodes.forEach { mc.sendMessage(it.id, "/jarvis/cmd", command.toByteArray()).await() }
            } catch (e: Exception) { JLog.d("link", "cmd '$command' failed: $e") }
        }
    }
}
