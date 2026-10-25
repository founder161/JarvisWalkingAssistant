package com.example.jarviswalkingassistant.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.jarviswalkingassistant.core.JLog
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class MessageListenerService : WearableListenerService() {
    override fun onCreate() { super.onCreate(); JLog.install(this, "watch-listener") }
    override fun onMessageReceived(e: MessageEvent) {
        if (e.path == "/jarvis/keys") { WatchLink.receiveKeys(this, String(e.data)); return }
        if (e.path != "/jarvis/advice") return
        WatchLink.receive(this, String(e.data))
        if (WatchService.running) return   // watch is running on its own: screen shows the watch engine
        val s = WatchLink.state.value
        if (s.card.isNotBlank() && !WatchActivity.visible) notify(s)
    }

    private fun notify(s: WatchState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(false); setSound(null, null)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun act(c: String, code: Int) = PendingIntent.getBroadcast(this, code,
            Intent(this, CmdReceiver::class.java).putExtra("cmd", c), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(1, NotificationCompat.Builder(this, "jarvis")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(WatchActivity.title(s)).setContentText(s.card)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.card))
            .setContentIntent(open).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Replay", act("replay", 1))
            .addAction(0, "Older", act("next", 2))
            .addAction(0, "Pause", act("toggle_pause", 3))
            .build())
    }
}

class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) { i.getStringExtra("cmd")?.let { WatchLink.cmd(ctx, it) } }
}
