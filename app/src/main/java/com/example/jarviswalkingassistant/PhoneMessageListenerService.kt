package com.example.jarviswalkingassistant

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class PhoneMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/jarvis/set_mode" -> {
                val mode = String(messageEvent.data)
                JarvisForegroundService.setMode(mode)
            }
            "/jarvis/toggle_listening" -> {
                val intent = Intent(applicationContext, JarvisForegroundService::class.java).apply {
                    putExtra("TOGGLE_LISTENING", true)
                }
                startService(intent)
            }
        }
    }
}
