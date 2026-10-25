package com.example.jarviswalkingassistant.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One small diagnostic log shared by the phone and the watch. Kept in memory (last 400 lines),
 * appended to a file in the app's private storage, and shown on screen so a failure says why.
 * Never contains keys or transcripts of what people said — only what the app did.
 */
object JLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    val latest = MutableStateFlow("")
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)
    @Volatile private var installed = false

    /** Call once per process (Application/Service/Activity onCreate are all fine — it's idempotent). */
    fun install(ctx: Context, who: String) {
        if (installed) return
        installed = true
        val dir = File(ctx.filesDir, "log").apply { mkdirs() }
        file = File(dir, "jarvis.log")
        // keep the file small
        file?.let { if (it.length() > 300_000) it.writeText(it.readText().takeLast(150_000)) }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sb = StringBuilder("CRASH in ${t.name}: $e\n")
                e.stackTrace.take(12).forEach { sb.append("    at $it\n") }
                var c = e.cause; var n = 0
                while (c != null && n++ < 3) { sb.append("  caused by: $c\n"); c.stackTrace.take(4).forEach { sb.append("    at $it\n") }; c = c.cause }
                d("crash", sb.toString())
                ctx.getSharedPreferences("jarvis_log", Context.MODE_PRIVATE).edit().putString("last_crash", sb.toString()).apply()
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
        d("boot", "$who ${Build.MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    fun lastCrash(ctx: Context): String? = ctx.getSharedPreferences("jarvis_log", Context.MODE_PRIVATE).getString("last_crash", null)
    fun clearCrash(ctx: Context) = ctx.getSharedPreferences("jarvis_log", Context.MODE_PRIVATE).edit().remove("last_crash").apply()

    @Synchronized fun d(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        android.util.Log.i("Jarvis", "[$tag] $msg")
        lines.addLast(line); while (lines.size > MAX) lines.removeFirst()
        latest.value = line
        try { file?.appendText(line + "\n") } catch (_: Exception) {}
    }

    @Synchronized fun recent(n: Int = 80): List<String> = lines.takeLast(n)
    @Synchronized fun text(): String = lines.joinToString("\n")

    /** Whole log as text, from the file when there is one (survives restarts). */
    fun dump(): String = try { file?.takeIf { it.exists() }?.readText()?.takeLast(120_000) ?: text() } catch (_: Exception) { text() }

    /** Phone only: write a text file into public Downloads so it can be attached to a chat. Returns the file name or the error. */
    fun saveToDownloads(ctx: Context, name: String, body: String): String = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JarvisNova")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw RuntimeException("no uri")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(body.toByteArray()) }
            "Downloads/JarvisNova/$name"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "JarvisNova").apply { mkdirs() }
            File(dir, name).writeText(body); "Downloads/JarvisNova/$name"
        }
    } catch (e: Exception) { "Couldn't save: ${e.message}" }
}
