package com.atoto.customradio

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manages communication with the ATOTO/FYT Hardware.
 * Based on common FYT (UIS7862) Broadcast Intents.
 */
class RadioManager(private val context: Context) {

    companion object {
        const val TAG = "RadioManager"
        
        // Common FYT (Synecore) Radio Intents
        // Note: These are "best guess" based on NavRadio community info.
        private const val ACTION_FYT_RADIO_NEXT = "com.syu.radio.next"
        private const val ACTION_FYT_RADIO_PREV = "com.syu.radio.prev"
        private const val ACTION_FYT_RADIO_SEEK_UP = "com.syu.radio.seekup"
        private const val ACTION_FYT_RADIO_SEEK_DOWN = "com.syu.radio.seekdown"
        // Sometimes key events are used instead
        private const val ACTION_FYT_WIDGET_NEXT = "com.syu.music.next" 
    }

    fun nextStation() {
        sendIntent(ACTION_FYT_RADIO_NEXT)
    }

    fun prevStation() {
        sendIntent(ACTION_FYT_RADIO_PREV)
    }
    
    fun seekUp() {
        sendIntent(ACTION_FYT_RADIO_SEEK_UP)
    }
    
    fun seekDown() {
        sendIntent(ACTION_FYT_RADIO_SEEK_DOWN)
    }

    private fun sendIntent(action: String) {
        try {
            Log.d(TAG, "Sending Intent: $action")
            val intent = Intent(action)
            // Some head units require specific flags or permissions
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send intent: $action", e)
        }
    }
}
