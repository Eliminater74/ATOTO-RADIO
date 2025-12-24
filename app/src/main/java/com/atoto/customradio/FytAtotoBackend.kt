package com.atoto.customradio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.syu.ipc.IModuleCallback
import com.syu.ipc.IRemoteModule
import com.syu.ipc.IRemoteToolkit

class FytAtotoBackend(private val context: Context) : RadioBackend {

    companion object {
        const val TAG = "FytAtotoBackend"

        // === IPC Constants ===
        const val MODULE_RADIO      = 1
        const val MODULE_MAIN       = 0

        // === MCU Command/Update Codes ===
        const val G_FREQ            = 13   // Frequency update (ints[0])
        const val G_BAND            = 11   // Band update (ints[0])
        const val G_AREA            = 12   // Region update (ints[0])

        const val C_FREQ            = 13   // Set Frequency
        const val U_SEARCH_STATE    = 22   // Seek/Search command (Update channel)

        const val SEARCH_STATE_NONE = 0
        const val SEARCH_FORE       = 2 // Seek Up
        const val SEARCH_BACK       = 3 // Seek Down
        
        const val C_FREQ_DIRECT     = 0 // Parameter for direct tune
        const val C_FREQ_BY_STEP    = 0 // Parameter for step tune
        
        // Command 11 = Band
        const val C_BAND            = 11 

        // Area Codes
        const val AREA_USA          = 0
        const val AREA_EUROPE       = 1
        const val AREA_CHINA        = 2
        const val AREA_JAPAN        = 3

        // Apps
        const val APP_ID_RADIO      = 1
    }

    // --- System & IPC Properties ---
    private val handler = Handler(Looper.getMainLooper())
    // AudioManager REMOVED - We do not touch audio focus in Controller Mode.
    
    // Remote Toolkit Service
    private var remoteToolkit: IRemoteToolkit? = null
    private var remoteModule: IRemoteModule? = null
    
    // Callbacks
    override var onFrequencyChanged: ((Int) -> Unit)? = null
    override var onBandChanged: ((RadioBand) -> Unit)? = null
    override var onRegionChanged: ((RadioRegion) -> Unit)? = null
    override var onStereoModeChanged: ((StereoMode) -> Unit)? = null
    override var onRdsTextChanged: ((String) -> Unit)? = null
    override var onStationNameChanged: ((String) -> Unit)? = null
    override var onSearchStateChanged: ((SearchState) -> Unit)? = null
    override var onConnectionStateChange: ((Boolean) -> Unit)? = null
    
    private var debugLog: ((String) -> Unit)? = null
    override fun setDebugLog(logger: (String) -> Unit) {
        debugLog = logger
    }
    
    // Helper to log to both Logcat and internal debugger
    private fun log(msg: String) {
        Log.d(TAG, msg)
        debugLog?.invoke(msg)
    }

    // --- MCU Callback Implementation ---
    private val moduleCallback = object : IModuleCallback.Stub() {
        // Called when MCU data changes
        override fun update(updateCode: Int, ints: IntArray?, floats: FloatArray?, strs: Array<String>?) {
            handler.post {
                when (updateCode) {
                    G_FREQ -> {
                        val freq = ints?.getOrNull(0) ?: return@post
                        log("MCU update: Freq -> $freq")
                        onFrequencyChanged?.invoke(freq)
                    }
                    G_BAND -> {
                        val bandInt = ints?.getOrNull(0) ?: return@post
                        val bandEnum = mapBandIntToEnum(bandInt)
                        log("MCU update: Band -> $bandInt")
                        onBandChanged?.invoke(bandEnum)
                    }
                    G_AREA -> {
                         // Optional region logic
                    }
                    else -> {
                        // Sniffer logic (commented out)
                        /* if (ints != null && ints.isNotEmpty()) log("MCU update: $updateCode [${ints.joinToString()}]") */
                    }
                }
            }
        }
    }

    // --- Service Connection ---
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            log("Connected to Toolkit Service")
            try {
                remoteToolkit = IRemoteToolkit.Stub.asInterface(service)
                remoteModule = remoteToolkit?.getRemoteModule(MODULE_RADIO)
                
                log("Got RemoteModule: $remoteModule")
                onConnectionStateChange?.invoke(true)
                
                // Register Callbacks to listen to stock radio state
                remoteModule?.register(moduleCallback, G_FREQ, 1)
                remoteModule?.register(moduleCallback, G_BAND, 1)
                
                // Pull initial state
                remoteModule?.cmd(G_FREQ, null, null, null)
                remoteModule?.cmd(G_BAND, null, null, null)
                
            } catch (e: Exception) {
                log("Service init failed: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log("Service Disconnected")
            onConnectionStateChange?.invoke(false)
            remoteToolkit = null
            remoteModule = null
        }
    }

    // --- RadioBackend Implementation (Controller logic) ---

    override fun start() {
        log("Starting Backend (Controller Mode - No Audio Claim)")
        
        // 1. Bind to FYT Service
        try {
            val intent = Intent("com.syu.ms.toolkit")
            intent.setPackage("com.syu.ms")
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            log("Binding to com.syu.ms.toolkit...")
        } catch (e: Exception) {
            log("Bind failed: ${e.message}")
        }
        
        // 2. Ensuring the System Radio is "Awake"
        // We poke the service but assume it owns the session
        try {
            context.sendBroadcast(Intent("com.navimods.radio.start.logger"))
        } catch(e: Exception) {}
    }

    override fun stop() {
        log("Stopping Backend")
        
        // Unbind only. Do NOT stop audio.
        try {
            remoteModule?.unregister(moduleCallback, G_FREQ)
            remoteModule?.unregister(moduleCallback, G_BAND)
            context.unbindService(connection)
        } catch (e: Exception) { 
            log("Unbind error: ${e.message}")
        }
        
        remoteToolkit = null
        remoteModule = null
    }

    override fun tuneTo(frequency: Int) {
        log("CTLR: TuneTo $frequency")
        // Controller Mode: Trust system to handle the hardware
        sendCmdC(C_FREQ, frequency)
    }

    override fun tuneStepUp() {
        log("CTLR: Tune Step Up")
        sendCmdC(C_FREQ, 0, 1)
    }

    override fun tuneStepDown() {
        log("CTLR: Tune Step Down")
        sendCmdC(C_FREQ, 0, -1)
    }

    override fun seekUp() {
        log("CTLR: Seek Up")
        sendCmdU(U_SEARCH_STATE, SEARCH_FORE)
    }

    override fun seekDown() {
        log("CTLR: Seek Down")
        sendCmdU(U_SEARCH_STATE, SEARCH_BACK)
    }

    override fun stopSeek() {
        log("CTLR: Stop Seek")
        sendCmdU(U_SEARCH_STATE, SEARCH_STATE_NONE)
    }

    override fun setBand(band: RadioBand) {
        val bandInt = mapBandEnumToInt(band)
        log("CTLR: Set Band $bandInt")
        sendCmdC(C_BAND, bandInt)
    }

    override fun setRegion(region: RadioRegion) {
        // System managed
    }
    
    override fun selectPreset(index: Int) {
        // Controller mode usually implies app-side preset logic that calls tuneTo
    }
    
    override fun savePreset(index: Int) {
         // App local logic
    }

    override fun setStereoMode(mode: StereoMode) {
        // System managed
    }

    override fun setLocalDx(local: Boolean) {
        // System managed
    }
    
    override fun startScan() {
         // Not impl
    }
    
    override fun stopScan() {
         // Not impl
    }

    // --- Helper Methods ---

    private fun sendCmdC(cKey: Int, vararg params: Int) {
        try {
            remoteModule?.cmd(cKey, params, null, null)
        } catch (e: Exception) {
            log("Cmd failed: ${e.message}")
        }
    }
    
    private fun sendCmdU(uKey: Int, vararg params: Int) {
        try {
            remoteModule?.cmd(uKey, params, null, null)
        } catch (e: Exception) {
            log("Cmd failed: ${e.message}")
        }
    }

    // --- Enums / Mappers ---
    private fun mapBandIntToEnum(band: Int): RadioBand {
        return when (band) {
            0 -> RadioBand.FM1
            1 -> RadioBand.FM2
            2 -> RadioBand.FM3
            3 -> RadioBand.AM1
            4 -> RadioBand.AM2
            else -> RadioBand.FM1
        }
    }

    private fun mapBandEnumToInt(band: RadioBand): Int {
        return when (band) {
            RadioBand.FM1 -> 0
            RadioBand.FM2 -> 1
            RadioBand.FM3 -> 2
            RadioBand.AM1 -> 3
            RadioBand.AM2 -> 4
        }
    }

    // Unused in this simplified version
    private fun mapRegionIntToEnum(area: Int): RadioRegion {
        return when (area) {
            AREA_USA -> RadioRegion.USA
            AREA_EUROPE -> RadioRegion.EUROPE
            else -> RadioRegion.USA
        }
    }
    
     private fun mapRegionEnumToInt(region: RadioRegion): Int {
        return when (region) {
            RadioRegion.USA -> AREA_USA
            RadioRegion.EUROPE -> AREA_EUROPE
            else -> AREA_USA
        }
    }
}
