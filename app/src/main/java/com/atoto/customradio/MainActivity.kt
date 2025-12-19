package com.atoto.customradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var radioManager: RadioManager
    private val snifferReceiver = RadioSniffer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        radioManager = RadioManager(this)

        statusText = findViewById(R.id.tv_status)
        logText = findViewById(R.id.tv_log)
        logText.movementMethod = ScrollingMovementMethod()

        val btnNext = findViewById<Button>(R.id.btn_next)
        val btnPrev = findViewById<Button>(R.id.btn_prev)
        val btnSeekUp = findViewById<Button>(R.id.btn_seek_up)
        val btnSeekDown = findViewById<Button>(R.id.btn_seek_down)

        btnNext.setOnClickListener {
            updateStatus("Action: NEXT Presets")
            radioManager.nextStation()
        }

        btnPrev.setOnClickListener {
            updateStatus("Action: PREV Presets")
            radioManager.prevStation()
        }
        
        btnSeekUp.setOnClickListener {
            updateStatus("Action: SEEK UP")
            radioManager.seekUp()
        }
        
        btnSeekDown.setOnClickListener {
            updateStatus("Action: SEEK DOWN")
            radioManager.seekDown()
        }

        // Start Sniffer for debugging
        registerSniffer()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(snifferReceiver)
    }

    private fun updateStatus(text: String) {
        statusText.text = text
    }

    private fun registerSniffer() {
        val filter = IntentFilter()
        // Listen for everything that might be relevant
        filter.addAction("com.syu.radio.next")
        filter.addAction("com.syu.radio.update") // Hypothetical
        filter.addAction("com.microntek.radio.next") // Just in case
        // Add more wildcards if possible (not possible with standard Manifest, must be specific)
        registerReceiver(snifferReceiver, filter)
        appendLog("Sniffer started...")
    }

    fun appendLog(msg: String) {
        logText.append("\n$msg")
    }

    inner class RadioSniffer : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val action = it.action
                // Log content
                val logMsg = "RX: $action"
                Log.d("RadioSniffer", logMsg)
                appendLog(logMsg)
            }
        }
    }
}
