package com.example.jarviswalkingassistant.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class MessageListenerService : WearableListenerService() {

    companion object {
        var onMessageReceived: ((String) -> Unit)? = null
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/jarvis/response") {
            val message = String(messageEvent.data)
            onMessageReceived?.invoke(message)
        }
    }
}
