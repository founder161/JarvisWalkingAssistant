package com.example.jarviswalkingassistant

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the mode-toggle command sent from the watch's button
 * (WatchActivity -> "/jarvis/set_mode" -> here -> JarvisForegroundService.setMode).
 */
class PhoneMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/jarvis/set_mode") {
            val mode = String(messageEvent.data)
            JarvisForegroundService.setMode(mode)
        }
    }
}
