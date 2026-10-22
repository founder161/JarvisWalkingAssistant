package com.example.jarviswalkingassistant.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

/**
 * The watch face of Jarvis. Shows the opener and the ONE line you're on (plus the next, small), so a glance is enough.
 * Answer = answer their last question · Ask = next thing I say is a question · Hush = stop talking in my ear.
 * Crown scrolls between answers. Buzzes: 1 answer ready · 2 fact-check · 3 careful · 4 a question is waiting.
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

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) WatchService.send(this, WatchService.START) }

    /** Route a command to the watch engine when it's running on its own, else to the phone. */
    private fun go(c: String) {
        if (WatchService.running) {
            val (a, x) = c.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
            WatchService.send(this, a, x)
        } else WatchLink.cmd(this, c)
    }

    private fun startOnWatch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) mic.launch(Manifest.permission.RECORD_AUDIO)
        else WatchService.send(this, WatchService.START)
    }

    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { super.onPause(); visible = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val phoneState by WatchLink.state.collectAsState()
            val localState by WatchLink.local.collectAsState()
            val s = if (WatchService.running || localState.local && !WatchLink.phoneLive) localState else phoneState
            val focus = remember { FocusRequester() }
            var acc by remember { mutableFloatStateOf(0f) }
            val col = color(s.label)
            val dim = Color(0xFF8A90A6)
            Box(Modifier.fillMaxSize().background(Color(0xFF06070B))
                .onRotaryScrollEvent {
                    acc += it.verticalScrollPixels
                    if (acc > 60) { go("next"); acc = 0f }
                    if (acc < -60) { go("prev"); acc = 0f }
                    true
                }.focusRequester(focus).focusable()
                .border(3.dp, (if (s.pending > 0) Color(0xFFF59E0B) else col).copy(alpha = if (s.card.isBlank()) .25f else .9f), CircleShape)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((if (s.local) "⌚ " else "") + title(s).uppercase() + (if (s.phase == "THINKING") " …" else ""), color = col, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text(buildString {
                        if (s.total > 0) append("${s.index + 1}/${s.total} · ")
                        append(s.venture)
                        if (s.pending > 0) append(" · ${s.pending} waiting")
                    }, color = if (s.pending > 0) Color(0xFFF59E0B) else dim, fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    when {
                        !s.connected && !WatchService.running -> {
                            Text(if (WatchPrefs(this@WatchActivity).ready) "Phone not here. Run Jarvis on the watch?" else "Open Jarvis on your phone once so the watch gets your keys.",
                                color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            if (WatchPrefs(this@WatchActivity).ready) Btn("Start on watch", wide = true, color = Color(0xFF14532D)) { startOnWatch() }
                            Spacer(Modifier.height(4.dp))
                            Text(localState.status, color = dim, fontSize = 10.sp, textAlign = TextAlign.Center)
                        }
                        s.card.isBlank() -> Text("Listening…", color = dim, fontSize = 16.sp, textAlign = TextAlign.Center)
                        s.units.isNotEmpty() -> {
                            // the line I'm on, big; the next one small; the opener stays at the top until I've passed it
                            val pos = s.pos.coerceAtLeast(0)
                            val opener = s.units.first()
                            if (pos > 0) Text(opener, color = dim, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
                            Text(s.units.getOrNull(pos) ?: opener, color = Color.White, fontSize = if (s.units[pos.coerceIn(0, s.units.size - 1)].length > 70) 15.sp else 18.sp,
                                lineHeight = 21.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { go("replay"); WatchLink.buzz(this@WatchActivity, 1) })
                            s.units.getOrNull(pos + 1)?.let { nxt ->
                                Spacer(Modifier.height(4.dp))
                                Text((if (s.whispered == pos + 1) "♪ " else "") + nxt, color = dim, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3)
                            }
                            if (s.partial) Text("…", color = dim, fontSize = 16.sp)
                        }
                        else -> Text(s.card, color = Color.White, fontSize = 17.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { go("replay"); WatchLink.buzz(this@WatchActivity, 1) })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn("Answer", wide = true, color = Color(0xFF14532D)) { go("answer") }
                        Btn(if (s.askNext) "Ask ✓" else "Ask", color = Color(0xFF3B2A8A)) { go("ask_next") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn("Hush") { go("hush") }
                        Btn(if (s.solo) "Me ✓" else "Me", color = if (s.solo) Color(0xFF0E4F5A) else Color(0xFF181B26)) { go("solo") }
                        if (WatchService.running) Btn("■", color = Color(0xFF7F1D1D)) { WatchService.send(this@WatchActivity, WatchService.STOP) }
                        else Btn(if (s.phase == "PAUSED") "▶" else "Ⅱ") { WatchLink.cmd(this@WatchActivity, "toggle_pause") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn(when (s.style) { "FULL" -> "Full"; "BULLETS" -> "Bullets"; else -> "Say it" }, wide = true) { go("style:next") }
                        Btn(when (s.ahead) { "PHRASE" -> "♪ Phrase"; "CUE" -> "♪ Cue"; else -> "♪ Off" }, wide = true) { go("ahead:next") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Btn("▲") { go("prev") }
                        Btn("▼") { go("next") }
                        Btn("Pin") { WatchLink.cmd(this@WatchActivity, "pin") }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (WatchService.running) Text("Running on the watch · ${s.status}", color = dim, fontSize = 10.sp, textAlign = TextAlign.Center)
                    else { Btn("Private", wide = true) { WatchLink.cmd(this@WatchActivity, "private") }
                           if (WatchPrefs(this@WatchActivity).ready) { Spacer(Modifier.height(6.dp)); Btn("Watch only", wide = true) { startOnWatch() } } }
                    Spacer(Modifier.height(20.dp))
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }

    @Composable
    private fun Btn(t: String, wide: Boolean = false, color: Color = Color(0xFF181B26), onClick: () -> Unit) {
        Box(Modifier.then(if (wide) Modifier.width(84.dp) else Modifier.size(44.dp)).height(40.dp)
            .clip(RoundedCornerShape(20.dp)).background(color)
            .clickable { WatchLink.buzz(this@WatchActivity, 1); onClick() }, contentAlignment = Alignment.Center) {
            Text(t, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
