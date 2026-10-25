package com.example.jarviswalkingassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.example.jarviswalkingassistant.core.JLog
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Watch → phone commands: /jarvis/cmd with payload "next", "replay", "toggle_pause", "venture:JARVIS" …  and /jarvis/log = the watch's diagnostic log. */
class PhoneMessageListenerService : WearableListenerService() {
    override fun onCreate() { super.onCreate(); JLog.install(this, "phone-listener") }

    override fun onMessageReceived(e: MessageEvent) {
        if (e.path == "/jarvis/log") { saveWatchLog(String(e.data)); return }
        if (e.path != "/jarvis/cmd") return
        val cmd = String(e.data)
        JLog.d("watch→phone", cmd)
        if (cmd.startsWith("start_")) return // microphone can only be started from the phone itself
        try {
            when {
                cmd.startsWith("venture:") -> JarvisService.send(this, JarvisService.VENTURE, cmd.removePrefix("venture:"))
                cmd.startsWith("style:") -> JarvisService.send(this, JarvisService.STYLE, cmd.removePrefix("style:"))
                cmd.startsWith("ahead:") -> JarvisService.send(this, JarvisService.AHEAD, cmd.removePrefix("ahead:"))
                cmd.startsWith("ask:") -> JarvisService.send(this, JarvisService.ASK, cmd.removePrefix("ask:"))
                cmd.startsWith("set:") -> JarvisService.send(this, JarvisService.SET, cmd.removePrefix("set:"))
                else -> JarvisService.send(this, cmd)
            }
        } catch (ex: Exception) {
            // Not running and Android blocked a background start — open the phone app to start Jarvis.
            JLog.d("watch→phone", "couldn't act on '$cmd': $ex")
        }
    }

    private fun saveWatchLog(body: String) {
        val name = "jarvis-watch-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.UK).format(java.util.Date()) + ".txt"
        val where = JLog.saveToDownloads(this, name, body)
        JLog.d("watch→phone", "watch log received (${body.length} chars) → $where")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis_log", "Jarvis diagnostics", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(77, NotificationCompat.Builder(this, "jarvis_log").setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Watch log saved").setContentText(where).setStyle(NotificationCompat.BigTextStyle().bigText("$where\nAttach it in the Claude chat.")).setAutoCancel(true).build())
    }
}
