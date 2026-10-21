package com.example.jarviswalkingassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.jarviswalkingassistant.core.Advice
import com.example.jarviswalkingassistant.core.Brain
import com.example.jarviswalkingassistant.core.ClaimsGate
import com.example.jarviswalkingassistant.core.Follower
import com.example.jarviswalkingassistant.core.Style
import com.example.jarviswalkingassistant.core.Whisper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- palette
object J {
    val bg = Color(0xFF06070B)
    val surface = Color(0xFF10121A)
    val surface2 = Color(0xFF181B26)
    val line = Color(0xFF262A38)
    val text = Color(0xFFF2F3F7)
    val dim = Color(0xFF8A90A6)
    val accent = Color(0xFF7C5CFF)
    val accent2 = Color(0xFF22D3EE)
    val verified = Color(0xFF22C55E)
    val web = Color(0xFF3B82F6)
    val unverified = Color(0xFFF59E0B)
    val blocked = Color(0xFFEF4444)
    fun label(l: String) = when (l) { "VERIFIED" -> verified; "WEB" -> web; "UNVERIFIED" -> unverified; else -> blocked }
    fun labelText(l: String) = when (l) { "VERIFIED" -> "YOUR FACTS"; "WEB" -> "INTEL"; "UNVERIFIED" -> "NOT VERIFIED"; else -> "BLOCKED" }
}

private val ACT_NAMES = mapOf(
    "answer" to "Answer", "factcheck" to "Fact-check", "warn" to "Careful", "name" to "Who's this",
    "tactic" to "Tactic", "commit" to "Commitment", "direct" to "You asked"
)

class MainActivity : ComponentActivity() {

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) perms.launch(needed.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = J.accent, background = J.bg, surface = J.surface, onSurface = J.text,
                onBackground = J.text, secondary = J.accent2, surfaceVariant = J.surface2)) {
                Root()
            }
        }
    }
}

@Composable
private fun Root() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = J.bg,
        bottomBar = {
            NavigationBar(containerColor = J.surface, tonalElevation = 0.dp) {
                listOf(Icons.Rounded.GraphicEq to "Live", Icons.Rounded.Description to "Notes", Icons.Rounded.Tune to "Setup")
                    .forEachIndexed { i, (icon, name) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icon, null) }, label = { Text(name) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = J.accent.copy(alpha = .25f),
                                selectedIconColor = J.text, unselectedIconColor = J.dim, selectedTextColor = J.text, unselectedTextColor = J.dim))
                    }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(J.bg)) {
            when (tab) { 0 -> LiveScreen(); 1 -> NotesScreen(); else -> SetupScreen() }
        }
    }
}

// ================================================================ LIVE
@Composable
private fun LiveScreen() {
    val s by JarvisState.ui.collectAsState()
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header(s) }
        item { ModeRow(s) }
        item { OrbBlock(s, prefs.ready) }
        item { ActionRow(s) }
        item { StyleRow(s) }
        if (s.pending.isNotEmpty()) item { PendingBanner(s) }
        item { CurrentCard(s) }
        item { TranscriptStrip(s) }
        item { AskBar(s) }
        val older = s.advice.drop(1)
        if (older.isNotEmpty()) item { SectionTitle("Earlier · ${older.size}") }
        items(older, key = { it.id }) { MiniCard(it) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Header(s: UiState) {
    val ctx = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("JARVIS", color = J.text, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            Spacer(Modifier.weight(1f))
            Text(s.sttEngine.ifBlank { "" }, color = J.dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("AGENTGUARD" to "AgentGuard", "JARVIS" to "Jarvis", "SKV" to "SKV", "ALL" to "All").forEach { (k, n) ->
                Chip(n, s.venture == k, J.accent) {
                    Prefs(ctx).venture = k
                    JarvisState.set { it.copy(venture = k) }
                    if (s.running) JarvisService.send(ctx, JarvisService.VENTURE, k)
                }
            }
        }
    }
}

@Composable
private fun ModeRow(s: UiState) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(J.surface).padding(4.dp)) {
        listOf(Mode.MEETING to "Meeting", Mode.DAY to "Day", Mode.PRIVATE to "Private").forEach { (m, n) ->
            val sel = s.running && s.mode == m || (!s.running && m == Mode.PRIVATE && false)
            val bg by animateColorAsState(if (sel) J.accent else Color.Transparent, label = "m")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(bg).clickable {
                when (m) {
                    Mode.MEETING -> JarvisService.send(ctx, JarvisService.START_MEETING)
                    Mode.DAY -> JarvisService.send(ctx, JarvisService.START_DAY)
                    Mode.PRIVATE -> if (s.running) JarvisService.send(ctx, JarvisService.PRIVATE)
                }
            }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(n, color = if (sel) Color.White else J.dim, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OrbBlock(s: UiState, ready: Boolean) {
    val ctx = LocalContext.current
    val phaseColor = when (s.phase) {
        Phase.LISTENING -> J.accent2; Phase.THINKING -> J.accent; Phase.SPEAKING -> J.verified
        Phase.PAUSED -> J.dim; Phase.IDLE -> J.line
    }
    val c by animateColorAsState(phaseColor, tween(400), label = "c")
    val inf = rememberInfiniteTransition(label = "orb")
    val breathe by inf.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(if (s.phase == Phase.THINKING) 1200 else 9000, easing = LinearEasing)), label = "s")
    val lvl by animateFloatAsState(s.level, tween(120), label = "l")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(190.dp).clickable {
            if (!s.running) JarvisService.send(ctx, JarvisService.START_MEETING)
            else JarvisService.send(ctx, JarvisService.TOGGLE_PAUSE)
        }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().scale(if (s.running) breathe + lvl * 0.12f else 0.95f)) {
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = .55f), c.copy(alpha = .08f), Color.Transparent), center, r), r)
                drawCircle(Brush.sweepGradient(listOf(c, J.accent, c.copy(alpha = .1f), c)), r * .62f, style = Stroke(width = 5f))
                val a = Math.toRadians(spin.toDouble())
                drawCircle(Color.White.copy(alpha = .9f), 6f, Offset(center.x + (r * .62f * Math.cos(a)).toFloat(), center.y + (r * .62f * Math.sin(a)).toFloat()))
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .9f), c), center, r * .32f), r * .32f * (1f + lvl * .35f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !ready -> "Add your Claude key in Setup"
                !s.running -> "Tap the orb to start Meeting mode"
                else -> s.status
            }, color = J.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (s.lastLatencyMs > 0) Text("last answer ${"%.1f".format(s.lastLatencyMs / 1000f)} s", color = J.dim, fontSize = 12.sp)
    }
}

/** The three buttons that replace saying "Jarvis": Answer the last question · Ask (next thing I say) · Just me. */
@Composable
private fun ActionRow(s: UiState) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigBtn("Answer", if (s.pending.isNotEmpty()) "${s.pending.size} waiting" else "last question", J.verified, Modifier.weight(1.2f), enabled = s.running) {
            JarvisService.send(ctx, JarvisService.ANSWER)
        }
        BigBtn("Ask", "next thing I say", J.accent, Modifier.weight(1f), enabled = s.running) { JarvisService.send(ctx, JarvisService.ASK_NEXT) }
        BigBtn(if (s.solo) "Just me ✓" else "Just me", if (s.solo) "all mine are questions" else "no \"Jarvis\" needed", J.accent2, Modifier.weight(1f), selected = s.solo) {
            JarvisService.send(ctx, JarvisService.SOLO)
        }
    }
}

@Composable
private fun StyleRow(s: UiState) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Style.CUES to "Say it", Style.BULLETS to "Bullets", Style.FULL to "Full").forEach { (st, n) ->
            Chip(n, s.style == st, J.accent) { Prefs(ctx).style = st; JarvisState.set { it.copy(style = st) }; JarvisService.send(ctx, JarvisService.STYLE, st.name) }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.Hearing, null, tint = if (s.ahead == Whisper.OFF) J.dim else J.verified, modifier = Modifier.size(18.dp))
        listOf(Whisper.OFF to "Off", Whisper.CUE to "Cue", Whisper.PHRASE to "Phrase").forEach { (w, n) ->
            Chip(n, s.ahead == w, J.verified) { Prefs(ctx).ahead = w; JarvisState.set { it.copy(ahead = w) }; JarvisService.send(ctx, JarvisService.AHEAD, w.name) }
        }
    }
}

@Composable
private fun PendingBanner(s: UiState) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(J.unverified.copy(alpha = .15f))
        .border(1.dp, J.unverified.copy(alpha = .5f), RoundedCornerShape(16.dp))
        .clickable { JarvisService.send(ctx, JarvisService.ANSWER) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⏳", fontSize = 16.sp); Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("They asked while you were answering", color = J.unverified, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("“${s.pending.first().text}”", color = J.text, fontSize = 14.sp, maxLines = 2)
        }
        Text("Answer", color = J.unverified, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun BigBtn(title: String, sub: String, color: Color, modifier: Modifier, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) color.copy(alpha = .35f) else color.copy(alpha = if (enabled) .16f else .06f), label = "bb")
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(bg).border(1.dp, color.copy(alpha = if (enabled) .6f else .2f), RoundedCornerShape(18.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = if (enabled) J.text else J.dim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(sub, color = J.dim, fontSize = 10.sp, maxLines = 1)
    }
}

/** The answer as Dale is delivering it: the line he's on is bright, done lines dim, skipped lines struck, whispered line marked. */
@Composable
private fun Prompter(a: Advice, s: UiState) {
    val units = remember(a.id, a.sections.size, a.full, a.cues.size, a.points.size, s.style) { Follower.unitsOf(a, s.style) }
    val f = s.follow.takeIf { it.adviceId == a.id }
    val firstSection = Follower.unitsOf(a.copy(sections = emptyList()), s.style).size
    Column {
        units.forEachIndexed { i, u ->
            if (i == firstSection && a.sections.isNotEmpty()) {
                Spacer(Modifier.height(8.dp)); Text("WHERE YOU WENT", color = J.accent2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp); Spacer(Modifier.height(4.dp))
            }
            val cur = f != null && f.pos == i
            val done = f != null && (i in f.done || i < f.pos && i !in f.skipped)
            val skipped = f != null && i in f.skipped
            val whispered = f != null && s.ahead != Whisper.OFF && f.whispered == i
            val big = s.style != Style.FULL && i == 0 && a.sections.isEmpty()
            Row(Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.width(4.dp).height(if (big) 30.dp else 22.dp).clip(CircleShape)
                    .background(when { cur -> J.verified; whispered -> J.accent; else -> Color.Transparent }))
                Spacer(Modifier.width(10.dp))
                Text((if (s.style == Style.BULLETS && i > 0) "• " else "") + u,
                    color = when { cur -> J.text; skipped -> J.dim.copy(alpha = .5f); done -> J.dim; else -> J.text.copy(alpha = .85f) },
                    fontSize = if (big) 22.sp else if (s.style == Style.FULL) 18.sp else 17.sp,
                    lineHeight = if (big) 29.sp else 25.sp,
                    fontWeight = if (cur || big) FontWeight.SemiBold else FontWeight.Normal,
                    textDecoration = if (skipped) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            }
        }
        if (a.partial) Text("…", color = J.dim, fontSize = 18.sp)
    }
}

@Composable
private fun CurrentCard(s: UiState) {
    val ctx = LocalContext.current
    val a = s.advice.getOrNull(s.cursor)
    AnimatedContent(a, transitionSpec = { (slideInVertically { it / 3 } + fadeIn()) togetherWith fadeOut() }, label = "card") { adv ->
        if (adv == null) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(J.surface).padding(24.dp)) {
                Text("Answers, fact-checks and tactics appear here — and in your ear in the next pause.",
                    color = J.dim, fontSize = 15.sp)
            }
        } else {
            val col = J.label(adv.label)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(col.copy(alpha = .18f), J.surface)))
                .border(1.dp, col.copy(alpha = .5f), RoundedCornerShape(28.dp)).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(J.labelText(adv.label), col)
                    Spacer(Modifier.width(8.dp))
                    Text(ACT_NAMES[adv.act] ?: adv.act, color = J.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (adv.spoken) Icon(Icons.Rounded.Hearing, null, tint = J.verified, modifier = Modifier.size(18.dp))
                    Text("  ${s.cursor + 1}/${s.advice.size}", color = J.dim, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Prompter(adv, s)
                if (adv.blocked.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Blocked: ${adv.blocked.first()}", color = J.blocked, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("Heard: “${adv.heard}”", color = J.dim, fontSize = 13.sp, fontStyle = FontStyle.Italic, maxLines = 2)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundBtn(Icons.Rounded.Replay, "Replay") { JarvisService.send(ctx, JarvisService.REPLAY) }
                    RoundBtn(Icons.Rounded.RecordVoiceOver, "Read") { JarvisService.send(ctx, JarvisService.READ) }
                    RoundBtn(Icons.Rounded.ArrowUpward, "Newer") { JarvisService.send(ctx, JarvisService.PREV) }
                    RoundBtn(Icons.Rounded.ArrowDownward, "Older") { JarvisService.send(ctx, JarvisService.NEXT) }
                    RoundBtn(if (adv.pinned) Icons.Rounded.PushPin else Icons.Rounded.PushPin, if (adv.pinned) "Pinned" else "Pin") {
                        JarvisService.send(ctx, JarvisService.PIN)
                    }
                    RoundBtn(Icons.Rounded.VolumeOff, "Hush") { JarvisService.send(ctx, JarvisService.HUSH) }
                }
            }
        }
    }
}

@Composable
private fun TranscriptStrip(s: UiState) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(J.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE", color = J.accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            if (s.running) Text("That was me", color = J.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { JarvisService.send(ctx, JarvisService.MARK_ME) })
        }
        Spacer(Modifier.height(8.dp))
        s.lines.takeLast(4).forEach { l ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(if (l.speaker == "ME") "You" else l.speaker.removePrefix("THEM ").let { if (it == "SPEAKER") "•" else "Them $it" },
                    color = if (l.speaker == "ME") J.accent else J.accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(64.dp))
                Text(l.text, color = J.text.copy(alpha = .9f), fontSize = 15.sp)
            }
        }
        if (s.partial.isNotBlank()) Text(s.partial, color = J.dim, fontSize = 15.sp, fontStyle = FontStyle.Italic)
        if (s.lines.isEmpty() && s.partial.isBlank()) Text(
            if (s.running) "Listening. Press Answer after their question, Ask before yours, or Just me for a one-to-one." else "Not listening.",
            color = J.dim, fontSize = 14.sp)
    }
}

@Composable
private fun AskBar(s: UiState) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Ask Jarvis…") }, singleLine = true,
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (q.isNotBlank()) { JarvisService.send(ctx, JarvisService.ASK, q); q = "" } }),
            trailingIcon = { IconButton(onClick = { if (q.isNotBlank()) { JarvisService.send(ctx, JarvisService.ASK, q); q = "" } }) { Icon(Icons.Rounded.Send, "Ask", tint = J.accent) } })
        Spacer(Modifier.width(8.dp))
        if (s.running) FilledTonalButton(onClick = { JarvisService.send(ctx, JarvisService.END) }, shape = RoundedCornerShape(18.dp)) {
            Text("End")
        }
    }
}

@Composable
private fun MiniCard(a: Advice) {
    val col = J.label(a.label)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(J.surface).padding(14.dp)) {
        Box(Modifier.width(4.dp).height(40.dp).clip(CircleShape).background(col))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("${ACT_NAMES[a.act] ?: a.act} · ${J.labelText(a.label)}${if (a.pinned) " · 📌" else ""}", color = J.dim, fontSize = 12.sp)
            Text(a.card, color = J.text, fontSize = 15.sp)
        }
    }
}

// ================================================================ NOTES
@Composable
private fun NotesScreen() {
    val ctx = LocalContext.current
    val dir = File(ctx.filesDir, "meetings")
    val files = remember { mutableStateOf(dir.listFiles()?.sortedByDescending { it.name } ?: emptyList()) }
    var open by remember { mutableStateOf(files.value.firstOrNull()) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Meeting notes", color = J.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Written when you tap End. Audio is never stored.", color = J.dim, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        if (files.value.isEmpty()) Text("No meetings yet.", color = J.dim)
        files.value.forEach { f ->
            Chip(f.nameWithoutExtension.replace('_', ' '), open == f, J.accent2) { open = f }
            Spacer(Modifier.height(6.dp))
        }
        open?.let { f ->
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(J.surface).padding(16.dp)) {
                Text(f.readText(), color = J.text, fontSize = 15.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Meeting notes ${f.nameWithoutExtension}")
                        .putExtra(Intent.EXTRA_TEXT, f.readText()), "Share notes"))
                }) { Text("Share") }
            }
        }
    }
}

// ================================================================ SETUP
@Composable
private fun SetupScreen() {
    val ctx = LocalContext.current
    val p = remember { Prefs(ctx) }
    var claude by remember { mutableStateOf(p.anthropicKey) }
    var assembly by remember { mutableStateOf(p.assemblyKey) }
    var model by remember { mutableStateOf(p.model) }
    var whisper by remember { mutableStateOf(p.whisper) }
    var web by remember { mutableStateOf(p.web) }
    var ip by remember { mutableStateOf(p.ipSafe) }
    var rate by remember { mutableFloatStateOf(p.speechRate) }
    var brief by remember { mutableStateOf(p.context) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Setup", color = J.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Group("Keys (stored encrypted on this phone only)") {
            var showKey by remember { mutableStateOf(false) }
            var saved by remember { mutableStateOf(p.anthropicKey.isNotBlank()) }
            var test by remember { mutableStateOf<String?>(null) }
            var testing by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            OutlinedTextField(claude, { claude = it; saved = false; test = null }, Modifier.fillMaxWidth(), label = { Text("Claude API key") },
                visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), singleLine = true,
                trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = J.dim) } },
                supportingText = { Text(when { saved && p.anthropicKey.isNotBlank() -> "Saved ✓  ${p.anthropicKey.take(7)}…${p.anthropicKey.takeLast(4)}"; claude.isBlank() -> "Paste your key, then press Save"; else -> "Not saved yet — press Save" },
                    color = if (saved) J.verified else J.unverified) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { p.anthropicKey = claude.trim(); claude = p.anthropicKey; saved = true; test = null }, enabled = claude.isNotBlank() && !saved) { Text("Save") }
                FilledTonalButton(enabled = saved && p.ready && !testing, onClick = {
                    testing = true; test = "Testing…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            try { Brain(ClaimsGate(ctx.assets.open("facts_ledger.json").bufferedReader().readText()), "", p).testKey() } catch (e: Exception) { e.message?.take(100) ?: "Failed" }
                        }
                        test = r ?: "Key works ✓ — Claude answered"; testing = false
                    }
                }) { Text("Test") }
            }
            test?.let { Text(it, color = if (it.startsWith("Key works")) J.verified else if (it == "Testing…") J.dim else J.blocked, fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
            var aSaved by remember { mutableStateOf(p.assemblyKey.isNotBlank()) }
            OutlinedTextField(assembly, { assembly = it; aSaved = false }, Modifier.fillMaxWidth(), label = { Text("AssemblyAI key (live transcription)") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                supportingText = { Text(if (aSaved && p.assemblyKey.isNotBlank()) "Saved ✓" else if (assembly.isBlank()) "Optional — without it the phone's own recogniser is used (no speaker labels)" else "Press Save", color = if (aSaved) J.verified else J.dim) })
            if (!aSaved && assembly.isNotBlank()) Button(onClick = { p.assemblyKey = assembly.trim(); assembly = p.assemblyKey; aSaved = true }) { Text("Save") }
        }
        Group("Today's brief") {
            OutlinedTextField(brief, { brief = it; p.context = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = { Text("Who you're meeting, what you want from it, anything Jarvis should know…") })
        }
        Group("Brain") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("smart" to "Smart", "max" to "Max").forEach { (k, n) ->
                    Chip(n, model == k, J.accent) { model = k; p.model = k }
                }
            }
            Text("Depth for questions you ask (Answer / Ask / Just me). Live listening always uses the fast model.", color = J.dim, fontSize = 12.sp)
            Toggle("Live web intelligence", web) { web = it; p.web = it }
            Toggle("IP-safe wording", ip) { ip = it; p.ipSafe = it }
        }
        Group("Hearing aids") {
            Toggle("Whisper answers in pauses", whisper) { whisper = it; p.whisper = it }
            var readFull by remember { mutableStateOf(p.readFull) }
            Toggle("Read the whole answer when I ask (not just the opener)", readFull) { readFull = it; p.readFull = it }
            Text("Speech speed ${"%.2f".format(rate)}×", color = J.dim, fontSize = 13.sp)
            Slider(rate, { rate = it; p.speechRate = it }, valueRange = 0.8f..1.4f)
            var aheadRate by remember { mutableFloatStateOf(p.aheadRate) }
            Text("Whisper-ahead speed ${"%.2f".format(aheadRate)}×  (Cue / Phrase on the Live screen)", color = J.dim, fontSize = 13.sp)
            Slider(aheadRate, { aheadRate = it; p.aheadRate = it }, valueRange = 0.9f..1.6f)
            Text("Whisper-ahead puts the answer in your ear a phrase (or a cue) ahead of where you are, and waits if you pause. Phrase = the whole answer; Cue = just the first words of each point.", color = J.dim, fontSize = 12.sp)
            Text("Answers play as media audio to your Phonaks — never as a call, so your aids stay in normal hearing mode.",
                color = J.dim, fontSize = 12.sp)
        }
        Group("Facts") {
            val gate = remember { ClaimsGate(ctx.assets.open("facts_ledger.json").bufferedReader().readText()) }
            Text("Facts ledger ${gate.version} · ${gate.ventures.values.sumOf { it.facts.size }} approved facts · " +
                "${gate.ventures.values.sumOf { it.banned.size }} banned claims", color = J.text, fontSize = 14.sp)
        }
    }
}

// ================================================================ bits
@Composable private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(J.surface).padding(16.dp)) {
        Text(title.uppercase(), color = J.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp)); content()
    }
}

@Composable private fun Toggle(label: String, value: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = J.text, modifier = Modifier.weight(1f)); Switch(value, on)
    }
}

@Composable private fun Chip(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) color.copy(alpha = .22f) else J.surface, label = "chip")
    Box(Modifier.clip(RoundedCornerShape(50)).background(bg)
        .border(1.dp, if (selected) color else J.line, RoundedCornerShape(50)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = if (selected) J.text else J.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun Pill(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = .2f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable private fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(J.surface2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = J.text)
        }
        Text(label, color = J.dim, fontSize = 11.sp)
    }
}

@Composable private fun SectionTitle(t: String) =
    Text(t.uppercase(), color = J.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
