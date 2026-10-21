package com.example.jarviswalkingassistant

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Watch → phone commands: /jarvis/cmd with payload "next", "replay", "toggle_pause", "venture:JARVIS" … */
class PhoneMessageListenerService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != "/jarvis/cmd") return
        val cmd = String(e.data)
        if (cmd.startsWith("start_")) return // microphone can only be started from the phone itself
        try {
            when {
                cmd.startsWith("venture:") -> JarvisService.send(this, JarvisService.VENTURE, cmd.removePrefix("venture:"))
                cmd.startsWith("style:") -> JarvisService.send(this, JarvisService.STYLE, cmd.removePrefix("style:"))
                cmd.startsWith("ahead:") -> JarvisService.send(this, JarvisService.AHEAD, cmd.removePrefix("ahead:"))
                cmd.startsWith("ask:") -> JarvisService.send(this, JarvisService.ASK, cmd.removePrefix("ask:"))
                else -> JarvisService.send(this, cmd)
            }
        } catch (_: Exception) {
            // Not running and Android blocked a background start — open the phone app to start Jarvis.
        }
    }
}
