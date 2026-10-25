package com.example.jarviswalkingassistant.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.jarviswalkingassistant.core.JLog

/**
 * The watch face of Jarvis: one scrolling list. The answer at the top (the line you're on, big), then one full-width
 * button per row so nothing is cramped. Crown or swipe scrolls. Every tap is logged.
 *
 * Answer = answer their last question · Ask = next thing I say is a question · Me = all mine are questions ·
 * Hush = stop talking in my ear. Buzzes: 1 answer ready · 2 fact-check · 3 careful · 4 a question is waiting.
 */
class WatchActivity : ComponentActivity() {
    companion object {
        @Volatile var visible = false
        fun color(label: String) = when (label) {
            "VERIFIED" -> Color(0xFF22C55E); "WEB" -> Color(0xFF3B82F6); "UNVERIFIED" -> Color(0xFFF59E0B)
            "BLOCKED" -> Color(0xFFEF4444); else -> Color(0xFF7C5CFF)
        }
        fun title(s: WatchState) = when {
            s.mode == "PRIVATE" -> "Private"
            s.phase == "PAUSED" -> "Paused"
            s.askNext -> "Ask me"
            s.card.isBlank() -> if (s.solo) "Just me" else "Listening"
            else -> when (s.act) { "answer" -> "Answer"; "factcheck" -> "Fact-check"; "warn" -> "Careful";
                "name" -> "Who"; "tactic" -> "Tactic"; "commit" -> "Noted"; "direct" -> "You asked"; else -> "Jarvis" } +
                when (s.label) { "VERIFIED" -> " · facts"; "WEB" -> " · intel"; "UNVERIFIED" -> " · check"; else -> "" }
        }
    }

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        JLog.d("ui", "mic permission ${if (ok) "granted" else "DENIED"}")
        if (ok) WatchService.send(this, WatchService.START)
    }

    /** Route a command to the watch engine when it's running on its own, else to the phone. */
    private fun go(c: String) {
        val local = WatchService.running
        JLog.d("tap", "$c → ${if (local) "watch" else "phone"}")
        WatchLink.buzz(this, 1)
        if (local) {
            val (a, x) = c.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
            WatchService.send(this, a, x)
        } else WatchLink.cmd(this, c)
    }

    private fun startOnWatch() {
        JLog.d("tap", "start on watch")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) mic.launch(Manifest.permission.RECORD_AUDIO)
        else WatchService.send(this, WatchService.START)
    }

    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { super.onPause(); visible = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JLog.install(this, "watch-ui")
        JLog.d("ui", "opened; claudeKey=${if (WatchPrefs(this).ready) "yes" else "NO"} assemblyKey=${if (WatchPrefs(this).assemblyKey.isNotBlank()) "yes" else "no"}")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                var showLog by remember { mutableStateOf(false) }
                if (showLog) LogScreen(onClose = { showLog = false }) else Main(onLog = { showLog = true })
            }
        }
    }

    @Composable
    private fun Main(onLog: () -> Unit) {
        val phoneState by WatchLink.state.collectAsState()
        val localState by WatchLink.local.collectAsState()
        val svcRunning by WatchService.runningFlow.collectAsState()
        val s = if (svcRunning || localState.local && !WatchLink.phoneLive) localState else phoneState
        var crash by remember { mutableStateOf(JLog.lastCrash(this@WatchActivity)) }
        val ready = remember { WatchPrefs(this@WatchActivity).ready }
        val col = color(s.label)
        val dim = Color(0xFF8A90A6)
        val list = rememberScalingLazyListState()

        Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
            ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize().background(Color(0xFF06070B)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp)) {

                // ---- header
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text((if (s.local) "⌚ " else "") + title(s).uppercase() + (if (s.phase == "THINKING") " …" else ""),
                            color = col, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, textAlign = TextAlign.Center)
                        Text(buildString {
                            if (s.total > 0) append("${s.index + 1}/${s.total} · ")
                            append(s.venture)
                            if (s.pending > 0) append(" · ${s.pending} waiting")
                        }, color = if (s.pending > 0) Color(0xFFF59E0B) else dim, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }

                crash?.let { c ->
                    item {
                        Text("Crashed last time — tap to clear (details on the Log page)", color = Color(0xFFEF4444), fontSize = 11.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(8.dp).clickable { JLog.clearCrash(this@WatchActivity); crash = null })
                    }
                }

                // ---- not connected to anything
                if (!s.connected && !svcRunning) {
                    item {
                        Text(if (ready) "Phone not here. Run Jarvis on the watch?" else "Open Jarvis on your phone once (Setup tab) so the watch gets your keys.",
                            color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    if (ready) item { Big("▶  Start on watch", Color(0xFF14532D)) { startOnWatch() } }
                    item { Text(localState.status, color = dim, fontSize = 11.sp, textAlign = TextAlign.Center) }
                    item { Big("Log", Color(0xFF181B26)) { onLog() } }
                    return@ScalingLazyColumn
                }

                // ---- the answer
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { go("replay") }, horizontalAlignment = Alignment.CenterHorizontally) {
                        when {
                            s.card.isBlank() -> {
                                Text("Listening…", color = dim, fontSize = 17.sp, textAlign = TextAlign.Center)
                                if (s.heard.isNotBlank()) Text(s.heard.takeLast(90), color = dim.copy(alpha = .7f), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 3)
                            }
                            s.units.isNotEmpty() -> {
                                val pos = s.pos.coerceIn(0, s.units.size - 1)
                                if (pos > 0) Text(s.units.first(), color = dim, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
                                Text(s.units[pos], color = Color.White, fontSize = if (s.units[pos].length > 70) 16.sp else 19.sp, lineHeight = 22.sp,
                                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                                s.units.getOrNull(pos + 1)?.let { nxt ->
                                    Spacer(Modifier.height(4.dp))
                                    Text((if (s.whispered == pos + 1) "♪ " else "") + nxt, color = dim, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3)
                                }
                                if (s.partial) Text("…", color = dim, fontSize = 16.sp)
                            }
                            else -> Text(s.card, color = Color.White, fontSize = 17.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                        }
                        if (s.card.isNotBlank()) Text("tap to replay", color = dim.copy(alpha = .6f), fontSize = 10.sp)
                    }
                }

                // ---- controls: one per row
                item { Big("Answer", Color(0xFF14532D)) { go("answer") } }
                item { Big(if (s.askNext) "Ask ✓  (next thing I say)" else "Ask  (next thing I say)", Color(0xFF3B2A8A)) { go("ask_next") } }
                item { Big(if (s.solo) "Just me ✓" else "Just me  (all mine are questions)", if (s.solo) Color(0xFF0E4F5A) else Color(0xFF181B26)) { go("solo") } }
                item { Big("Hush", Color(0xFF181B26)) { go("hush") } }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Half("▲ Newer") { go("prev") }
                        Half("▼ Older") { go("next") }
                    }
                }
                item { Big("Read it all", Color(0xFF181B26)) { go("read") } }
                item { Big("View: " + when (s.style) { "FULL" -> "Full"; "BULLETS" -> "Bullets"; else -> "Say it" }, Color(0xFF181B26)) { go("style:next") } }
                item { Big("Whisper-ahead: " + when (s.ahead) { "PHRASE" -> "Phrase"; "CUE" -> "Cue"; else -> "Off" }, Color(0xFF181B26)) { go("ahead:next") } }
                if (!svcRunning) {
                    item { Big(if (s.phase == "PAUSED") "▶ Resume" else "Ⅱ Pause", Color(0xFF181B26)) { WatchLink.cmd(this@WatchActivity, "toggle_pause") } }
                    item { Big("Pin", Color(0xFF181B26)) { go("pin") } }
                    item { Big("Private (mic off)", Color(0xFF181B26)) { WatchLink.cmd(this@WatchActivity, "private") } }
                    if (ready) item { Big("Run on watch instead", Color(0xFF181B26)) { startOnWatch() } }
                } else {
                    item { Big("■  Stop watch mode", Color(0xFF7F1D1D)) { JLog.d("tap", "stop"); WatchService.send(this@WatchActivity, WatchService.STOP) } }
                    item { Text(s.status, color = dim, fontSize = 11.sp, textAlign = TextAlign.Center) }
                }
                item { Big("Log", Color(0xFF181B26)) { onLog() } }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    @Composable
    private fun Big(label: String, bg: Color, onClick: () -> Unit) {
        Chip(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ChipDefaults.chipColors(backgroundColor = bg, contentColor = Color.White),
            label = { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) })
    }

    @Composable
    private fun RowScope.Half(label: String, onClick: () -> Unit) {
        Chip(onClick = onClick, modifier = Modifier.weight(1f).height(44.dp),
            colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF181B26), contentColor = Color.White),
            label = { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) })
    }

    /** The diagnostic log, on the wrist. "Send to phone" drops it in the phone's Downloads/JarvisNova folder. */
    @Composable
    private fun LogScreen(onClose: () -> Unit) {
        val last by JLog.latest.collectAsState()
        var result by remember { mutableStateOf("") }
        val lines = remember(last) { JLog.recent(60).reversed() }
        val list = rememberScalingLazyListState()
        Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
            ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize().background(Color(0xFF06070B)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp)) {
                item { Text("LOG", color = Color(0xFF7C5CFF), fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
                item { Big("← Back", Color(0xFF181B26)) { onClose() } }
                item { Big("Send to phone", Color(0xFF14532D)) { result = "Sending…"; WatchLink.sendLog(this@WatchActivity) { result = it } } }
                if (result.isNotBlank()) item { Text(result, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center) }
                JLog.lastCrash(this@WatchActivity)?.let { c -> item { Text(c.take(600), color = Color(0xFFEF4444), fontSize = 9.sp) } }
                if (lines.isEmpty()) item { Text("Nothing logged yet", color = Color(0xFF8A90A6), fontSize = 11.sp) }
                items(lines.size) { i ->
                    val l = lines[i]
                    Text(l.substringAfter(' '), color = if ("crash" in l || "failed" in l || "rejected" in l) Color(0xFFF59E0B) else Color(0xFFCBD0E0), fontSize = 9.sp, lineHeight = 11.sp)
                }
            }
        }
    }
}
