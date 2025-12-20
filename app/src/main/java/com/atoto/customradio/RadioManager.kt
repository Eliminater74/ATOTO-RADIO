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
        
        // Strategy 1: Standard Android Media Keys (Universal)
        // Strategy 2: Common Chinese Head Unit Intents (FYT/MTC)
        private const val ACTION_FYT_RADIO_NEXT = "com.syu.radio.next"
        private const val ACTION_FYT_RADIO_PREV = "com.syu.radio.prev"
    }

    fun nextStation() {
        // Try generic Media Key first (works on many units)
        sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        // Also fire the intent just in case
        sendIntent(ACTION_FYT_RADIO_NEXT)
    }

    fun prevStation() {
        sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        sendIntent(ACTION_FYT_RADIO_PREV)
    }
    
    fun seekUp() {
        sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        sendIntent("com.syu.radio.seekup")
    }
    
    fun seekDown() {
        sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_REWIND)
        sendIntent("com.syu.radio.seekdown")
    }

    private fun sendMediaKey(keyCode: Int) {
        try {
            Log.d(TAG, "Injecting Media Key: $keyCode")
            val instrumentation = android.app.Instrumentation()
            // This requires standard inputs, might fail if not system app or lacking permissions
            // Note: Instrumentation cannot be run on main thread easily in standard apps without thread.
            // Using AudioManager is safer for non-system apps:
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
            )
            audioManager.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Media Key", e)
        }
    }

    fun startRadio() {
        // Try to wake up the radio hardware
        sendIntent("com.syu.radio.Launch")
        sendIntent("com.syu.radio.switch")
        // Request Audio Focus (Standard Android)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.requestAudioFocus(
            null, 
            android.media.AudioManager.STREAM_MUSIC, 
            android.media.AudioManager.AUDIOFOCUS_GAIN
        )
    }

    private fun sendIntent(action: String) {
        try {
            Log.d(TAG, "Sending Intent: $action")
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            // Some units need this flag to "Start" the service
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send intent: $action", e)
        }
    }
}
