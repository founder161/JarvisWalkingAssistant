package com.example.jarviswalkingassistant.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.jarviswalkingassistant.core.Engine
import com.example.jarviswalkingassistant.core.Style
import com.example.jarviswalkingassistant.core.Whisper

/** Watch settings. Keys arrive from the phone over the Wear data layer and are stored encrypted on the watch. */
class WatchPrefs(context: Context) : Engine.EngineSettings {
    private val sp: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "jarvis_watch_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) { context.getSharedPreferences("jarvis_watch_plain", Context.MODE_PRIVATE) }

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    override var anthropicKey: String get() = str("anthropic_key"); set(v) = put("anthropic_key", v.trim())
    var assemblyKey: String get() = str("assembly_key"); set(v) = put("assembly_key", v.trim())
    override var venture: String get() = str("venture", "AGENTGUARD"); set(v) = put("venture", v)
    override var model: String get() = str("model", "smart"); set(v) = put("model", v)
    override var web: Boolean get() = sp.getBoolean("web", true); set(v) = put("web", v)
    override var ipSafe: Boolean get() = sp.getBoolean("ip_safe", true); set(v) = put("ip_safe", v)
    override var context: String get() = str("context"); set(v) = put("context", v)
    override var style: Style get() = Style.of(str("style", "CUES")); set(v) = put("style", v.name)
    override var solo: Boolean get() = sp.getBoolean("solo", false); set(v) = put("solo", v)
    override var ahead: Whisper get() = Whisper.of(str("ahead", "OFF")); set(v) = put("ahead", v.name)
    override var readFull: Boolean get() = sp.getBoolean("read_full", false); set(v) = put("read_full", v)
    override var whisper: Boolean get() = sp.getBoolean("whisper", true); set(v) = put("whisper", v)
    var speechRate: Float get() = sp.getFloat("speech_rate", 1.05f); set(v) = sp.edit().putFloat("speech_rate", v).apply()
    var aheadRate: Float get() = sp.getFloat("ahead_rate", 1.15f); set(v) = sp.edit().putFloat("ahead_rate", v).apply()
    val ready get() = anthropicKey.startsWith("sk-")
}
