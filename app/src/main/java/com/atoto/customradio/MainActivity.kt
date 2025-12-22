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
    private lateinit var backend: RadioBackend
    private val snifferReceiver = RadioSniffer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Views FIRST (Before any logging or status updates)
        statusText = findViewById(R.id.tv_status)
        logText = findViewById(R.id.tv_log)
        logText.movementMethod = ScrollingMovementMethod()

        val btnNext = findViewById<Button>(R.id.btn_next)
        val btnPrev = findViewById<Button>(R.id.btn_prev)
        val btnSeekUp = findViewById<Button>(R.id.btn_seek_up)
        val btnSeekDown = findViewById<Button>(R.id.btn_seek_down)

        // Initialize Backend (FORCE FYT for troubleshooting)
        // backend = RadioBackendFactory.create(this)
        backend = FytAtotoBackend(this)
        
        updateStatus("Backend: ${backend.javaClass.simpleName} (Connecting...)")
        
        // --- Connect Backend Signals to UI ---
        // Add a simple callback for connection state if we can add it to Interface later, 
        // for now relies on side-effect logs or we can hack it:
        if (backend is FytAtotoBackend) {
             val fytBackend = backend as FytAtotoBackend
             fytBackend.onConnectionStateChange = { isConnected ->
                 runOnUiThread {
                     val state = if(isConnected) "Connected" else "Disconnected"
                     findViewById<TextView>(R.id.tv_status).append("\nIPC: $state")
                 }
             }
             // Wiring the new Debug Logger
             fytBackend.debugLog = { msg ->
                 runOnUiThread { appendLog(msg) }
             }
        }

        backend.onFrequencyChanged = { freq ->
            // freq is in 10kHz units for FM (e.g. 10170 = 101.7 MHz)
            val displayFreq = freq / 100.0
            runOnUiThread {
                findViewById<TextView>(R.id.tv_frequency).text = "%.1f MHz".format(displayFreq)
            }
        }
        
        backend.onBandChanged = { band ->
            runOnUiThread { updateStatus("Current Band: $band") }
        }

        // --- Configured with Backend Calls ---
        btnNext.setOnClickListener {
            updateStatus("Action: Tune Up")
            backend.tuneStepUp()
        }

        btnPrev.setOnClickListener {
            updateStatus("Action: Tune Down")
            backend.tuneStepDown()
        }
        
        btnSeekUp.setOnClickListener {
            updateStatus("Action: Seek Up")
            backend.seekUp() 
        }
        
        btnSeekDown.setOnClickListener {
            updateStatus("Action: Seek Down")
            backend.seekDown() 
        }
        
        // --- Debug Controls Wiring ---
        findViewById<Button>(R.id.btn_debug_src1).setOnClickListener { 
            // In universal backend, we typically don't expose raw ID source switching freely, 
            // but for debug we might re-impl or assume backend handles it.
            updateStatus("Debug: Backend Start (Set Source)")
            backend.start()
        }
        // Other debug buttons might need specific backend extensions or be removed/stubbed 
        findViewById<Button>(R.id.btn_debug_src11).setOnClickListener { 
             if (backend is FytAtotoBackend) {
                 (backend as FytAtotoBackend).setSource(11) // Mute/Aux
                 updateStatus("Debug: Force App ID 11")
             }
        }
        findViewById<Button>(R.id.btn_debug_hide).setOnClickListener { 
             sendBroadcast(Intent("android.fyt.action.HIDE"))
             updateStatus("Debug: Sent HIDE")
        }
        findViewById<Button>(R.id.btn_debug_show).setOnClickListener { 
             sendBroadcast(Intent("android.fyt.action.SHOW"))
             updateStatus("Debug: Sent SHOW")
        }
        findViewById<Button>(R.id.btn_debug_995).setOnClickListener { 
            backend.tuneTo(10470) // Direct Tune to 104.7 MHz
        }

        // Start Sniffer for debugging
        registerSniffer()
        
        // Start the Backend (Connects to service, initializes radio)
        backend.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        backend.stop()
        unregisterReceiver(snifferReceiver)
    }

    private fun updateStatus(text: String) {
        statusText.text = text
    }

    private fun registerSniffer() {
        val filter = IntentFilter()
        filter.addAction("com.syu.radio.next")
        filter.addAction("com.syu.radio.update")
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
                val logMsg = "RX: $action"
                Log.d("RadioSniffer", logMsg)
                appendLog(logMsg)
            }
        }
    }
}
