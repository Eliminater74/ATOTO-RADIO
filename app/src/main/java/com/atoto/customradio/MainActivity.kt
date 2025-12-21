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
        
        // --- Connect MCU Callbacks to UI ---
        radioManager.logCallback = { msg ->
            runOnUiThread { appendLog(msg) }
        }
        
        val frequencyText = findViewById<TextView>(R.id.tv_frequency)
        
        radioManager.onFreqChange = { freq ->
            // freq is in 10kHz units for FM (e.g. 10170 = 101.7 MHz)
            val displayFreq = freq / 100.0
            frequencyText.text = "%.1f MHz".format(displayFreq)
        }
        
        radioManager.onBandChange = { band ->
            updateStatus("Current Band: $band")
        }
        
        // Initialize Views FIRST


        statusText = findViewById(R.id.tv_status)
        logText = findViewById(R.id.tv_log)
        logText.movementMethod = ScrollingMovementMethod()

        val btnNext = findViewById<Button>(R.id.btn_next)
        val btnPrev = findViewById<Button>(R.id.btn_prev)
        val btnSeekUp = findViewById<Button>(R.id.btn_seek_up)
        val btnSeekDown = findViewById<Button>(R.id.btn_seek_down)

        var currentFreq = 101.5

        // --- Configured with Correct C_ Codes ---
        btnNext.setOnClickListener {
            updateStatus("Action: Tune Up")
            radioManager.tuneUp()
        }

        btnPrev.setOnClickListener {
            updateStatus("Action: Tune Down")
            radioManager.tuneDown()
        }
        
        btnSeekUp.setOnClickListener {
            updateStatus("Action: Seek Up")
            radioManager.seekUp() 
        }
        
        btnSeekDown.setOnClickListener {
            updateStatus("Action: Seek Down")
            radioManager.seekDown() 
        }
        
        // --- Debug Controls Wiring (Restored) ---
        findViewById<Button>(R.id.btn_debug_src1).setOnClickListener { 
            radioManager.setSource(1) 
        }
        findViewById<Button>(R.id.btn_debug_src11).setOnClickListener { 
            radioManager.setSource(11) 
        }
        findViewById<Button>(R.id.btn_debug_hide).setOnClickListener { 
            radioManager.sendFytIntent("android.fyt.action.HIDE") 
        }
        findViewById<Button>(R.id.btn_debug_show).setOnClickListener { 
            radioManager.sendFytIntent("android.fyt.action.SHOW") 
        }
        findViewById<Button>(R.id.btn_debug_995).setOnClickListener { 
            radioManager.tuneTo(10470) // Direct Tune to 104.7 MHz (Q105)
        }

        // Start Sniffer for debugging
        registerSniffer()
        
        // Try to wake up hardware on launch (Moved after View Init to prevent Crash)
        radioManager.startRadio()
    }

    override fun onDestroy() {
        super.onDestroy()
        radioManager.stopRadio()
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
