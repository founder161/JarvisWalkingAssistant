package com.example.jarviswalkingassistant

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.jarviswalkingassistant.core.CoreSettings
import com.example.jarviswalkingassistant.core.Style
import com.example.jarviswalkingassistant.core.Whisper

/** Settings + API keys. Keys live only on this phone, encrypted — never in the app file. */
class Prefs(context: Context) : CoreSettings {
    private val sp: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "jarvis_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("jarvis_plain", Context.MODE_PRIVATE)
    }

    override var anthropicKey: String
        get() = sp.getString("anthropic_key", "") ?: ""
        set(v) = sp.edit().putString("anthropic_key", v.trim()).apply()

    var assemblyKey: String
        get() = sp.getString("assembly_key", "") ?: ""
        set(v) = sp.edit().putString("assembly_key", v.trim()).apply()

    override var venture: String
        get() = sp.getString("venture", "AGENTGUARD") ?: "AGENTGUARD"
        set(v) = sp.edit().putString("venture", v).apply()

    /** smart | max — depth for questions you ask (live listening is always fast) */
    override var model: String
        get() = (sp.getString("model", "smart") ?: "smart").let { if (it == "fast") "smart" else it }
        set(v) = sp.edit().putString("model", v).apply()

    var whisper: Boolean
        get() = sp.getBoolean("whisper", true)
        set(v) = sp.edit().putBoolean("whisper", v).apply()

    override var web: Boolean
        get() = sp.getBoolean("web", true)
        set(v) = sp.edit().putBoolean("web", v).apply()

    override var ipSafe: Boolean
        get() = sp.getBoolean("ip_safe", true)
        set(v) = sp.edit().putBoolean("ip_safe", v).apply()

    /** Free-text brief for today: who you're meeting, what you want. Fed to the brain. */
    override var context: String
        get() = sp.getString("context", "") ?: ""
        set(v) = sp.edit().putString("context", v).apply()

    var speechRate: Float
        get() = sp.getFloat("speech_rate", 1.05f)
        set(v) = sp.edit().putFloat("speech_rate", v).apply()

    /** Whisper-ahead speaks a touch faster: the phrase has to land before you need it. */
    var aheadRate: Float
        get() = sp.getFloat("ahead_rate", 1.15f)
        set(v) = sp.edit().putFloat("ahead_rate", v).apply()

    override var style: Style
        get() = Style.of(sp.getString("style", "CUES"))
        set(v) = sp.edit().putString("style", v.name).apply()

    /** meeting = listen to everyone · solo = "Just me": everything I say is a question */
    var solo: Boolean
        get() = sp.getBoolean("solo", false)
        set(v) = sp.edit().putBoolean("solo", v).apply()

    var ahead: Whisper
        get() = Whisper.of(sp.getString("ahead", "OFF"))
        set(v) = sp.edit().putString("ahead", v.name).apply()

    /** Read the whole answer aloud (not just the opening line) for questions you ask. */
    var readFull: Boolean
        get() = sp.getBoolean("read_full", false)
        set(v) = sp.edit().putBoolean("read_full", v).apply()

    val ready: Boolean get() = anthropicKey.startsWith("sk-")
}
