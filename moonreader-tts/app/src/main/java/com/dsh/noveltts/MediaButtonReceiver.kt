package com.dsh.noveltts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Receives headset/earphone media-button presses (play/pause/stop/next/prev)
 * routed to our MediaSession, and forwards the key code to the engine service
 * so it can control the currently-playing TTS "just like music".
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        }
        if (event == null || event.action != KeyEvent.ACTION_DOWN) return
        // Route to whichever player is active: the standalone audiobook player
        // if the reader started it, otherwise the TTS engine service.
        if (AudioBookService.instanceHandle() != null) {
            AudioBookService.handleKey(event.keyCode)
        } else {
            TtsEngineService.handleMediaKey(event.keyCode)
        }
    }
}
