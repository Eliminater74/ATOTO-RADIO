package com.atoto.customradio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.syu.ipc.IModuleCallback
import com.syu.ipc.IRemoteModule
import com.syu.ipc.IRemoteToolkit

class FytAtotoBackend(private val context: Context) : RadioBackend {

    // --- Callbacks to UI ---
    override var onFrequencyChanged: ((Int) -> Unit)? = null
    override var onBandChanged: ((RadioBand) -> Unit)? = null
    override var onRegionChanged: ((RadioRegion) -> Unit)? = null
    override var onRdsTextChanged: ((String) -> Unit)? = null
    override var onStationNameChanged: ((String) -> Unit)? = null
    override var onSearchStateChanged: ((SearchState) -> Unit)? = null

    // --- Debug log callback (for MainActivity.tv_log) ---
    var debugLog: ((String) -> Unit)? = null
    
    // Connection State Callback (added for UI feedback)
    var onConnectionStateChange: ((Boolean) -> Unit)? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        debugLog?.invoke(msg)
    }

    companion object {
        const val TAG = "FytAtotoBackend"

        const val MODULE_RADIO = 1        // FinalRadio.MODULE_RADIO_BY_MCU
        const val MODULE_MAIN  = 0
        const val APP_ID_RADIO = 1        // Standard Radio
        
        // --- G_* Get/Notify Codes (From MCU) ---
        const val G_BAND = 0
        const val G_FREQ = 1
        const val G_AREA = 2
        
        // --- C_* Command Codes (App -> MCU) ---
        const val C_NEXT_CHANNEL      = 0
        const val C_PREV_CHANNEL      = 1
        const val C_FREQ_UP           = 3
        const val C_FREQ_DOWN         = 4
        const val C_SEEK_UP           = 5
        const val C_SEEK_DOWN         = 6
        const val C_SELECT_CHANNEL    = 7
        const val C_SAVE_CHANNEL      = 8
        const val C_SCAN              = 9
        const val C_SAVE              = 10
        const val C_BAND              = 11
        const val C_AREA              = 12
        const val C_FREQ              = 13
        const val C_SENSITY           = 14
        const val C_AUTO_SENSITY      = 15
        const val C_RDS_ENABLE        = 16
        const val C_STEREO            = 17
        const val C_LOC               = 18
        
        // --- U_* Update Codes (App -> MCU) ---
        const val U_SEARCH_STATE = 22
        
        // --- Constants ---
        const val FREQ_BY_STEP  = 0
        const val FREQ_DIRECT   = 1
        
        const val SEARCH_STATE_NONE = 0
        const val SEARCH_FORE       = 2
        const val SEARCH_BACK       = 3
        
        const val AREA_USA    = 0
        const val AREA_LATIN  = 1
        const val AREA_EUROPE = 2
        const val AREA_CHINA  = 2
        const val AREA_OIRT   = 3
        const val AREA_JAPAN  = 4
        
        const val BAND_FM1 = 0 
    }
    
    private var currentFreq: Int = 8790 // Default fallback

// ...(keep existing callbacks)...

    // --- MCU Callback Implementation ---
    private val moduleCallback = object : IModuleCallback.Stub() {
        override fun update(updateCode: Int, ints: IntArray?, floats: FloatArray?, strs: Array<String>?) {
            val intsStr = ints?.joinToString() ?: "null"
            val logMsg = "MCU update: code=$updateCode ints=[$intsStr]"
            log(logMsg)

            handler.post {
                when (updateCode) {
                    // Standard FYT codes
                    G_FREQ -> {
                        val freq = ints?.getOrNull(0) ?: return@post
                        log("G_FREQ -> $freq")
                        onFrequencyChanged?.invoke(freq)
                    }
                    G_BAND -> {
                        val bandInt = ints?.getOrNull(0) ?: return@post
                        val bandEnum = mapBandIntToEnum(bandInt)
                        log("G_BAND -> $bandInt ($bandEnum)")
                        onBandChanged?.invoke(bandEnum)
                    }
                    G_AREA -> {
                        val areaInt = ints?.getOrNull(0) ?: return@post
                        val regionEnum = mapRegionIntToEnum(areaInt)
                        log("G_AREA -> $areaInt ($regionEnum)")
                        onRegionChanged?.invoke(regionEnum)
                    }
                    
                    // ATOTO Multiplexed Code
                    28 -> { 
                        if (ints != null && ints.isNotEmpty()) {
                            if (ints.size == 1) {
                                log("MCU ACK (28) -> Cmd: ${ints[0]}")
                            } else {
                                val cmdId = ints[0]
                                val val1 = ints.getOrNull(1)
                                
                                when (cmdId) {
                                    C_FREQ -> { // 13
                                        if (val1 != null) {
                                            log("Parsed FREQ from code 28 -> $val1")
                                            onFrequencyChanged?.invoke(val1)
                                        }
                                    }
                                    C_BAND -> { // 11
                                        if (val1 != null) {
                                            val bandEnum = mapBandIntToEnum(val1)
                                            log("Parsed BAND from code 28 -> $val1 ($bandEnum)")
                                            onBandChanged?.invoke(bandEnum)
                                        }
                                    }
                                    C_AREA -> { // 12
                                        if (val1 != null) {
                                            val regionEnum = mapRegionIntToEnum(val1)
                                            log("Parsed AREA from code 28 -> $val1 ($regionEnum)")
                                            onRegionChanged?.invoke(regionEnum)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Audio Focus & Service Connection (No changes) ---

    // ... (Dependencies and Startup) ...

    private fun registerRadioTransport() {
        val actions = listOf(
            "com.syu.radio.attach",
            "com.syu.radio.start",
            "com.syu.radio.takeover",
            "com.syu.radio.init" // Added initialization signal
        )
        
        actions.forEach { action ->
            try {
                val intent = Intent(action)
                intent.setPackage("com.syu.ms")
                context.sendBroadcast(intent)
                log("Handshake: $action")
            } catch (e: Exception) { }
        }
    }

    // --- RadioBackend methods ---

    override fun tuneTo(freq: Int) {
        log("tuneTo($freq)")
        sendCmdC(C_FREQ, FREQ_DIRECT, freq, 0)
        handler.postDelayed({ claimRadioAudioSession() }, 250)
    }

    override fun tuneStepUp() {
        log("tuneStepUp -> C_FREQ STEP +1")
        sendCmdC(C_FREQ, FREQ_BY_STEP, 1, 0)
        handler.postDelayed({ claimRadioAudioSession() }, 250)
    }

    override fun tuneStepDown() {
        log("tuneStepDown -> C_FREQ STEP -1")
        sendCmdC(C_FREQ, FREQ_BY_STEP, -1, 0)
        handler.postDelayed({ claimRadioAudioSession() }, 250)
    }

    override fun seekUp() {
        log("seekUp -> U_SEARCH_STATE FORE")
        sendCmdU(U_SEARCH_STATE, SEARCH_FORE)
        handler.postDelayed({ claimRadioAudioSession() }, 250)
    }

    override fun seekDown() {
        log("seekDown -> U_SEARCH_STATE BACK")
        sendCmdU(U_SEARCH_STATE, SEARCH_BACK)
        handler.postDelayed({ claimRadioAudioSession() }, 250)
    }

    override fun stopSeek() {
        log("stopSeek -> C_SCAN (Toggle)")
        sendCmdC(C_SCAN) // Safer stop than U_SEARCH_STATE on some FWs
    }

    override fun startScan() {
        log("startScan()")
        sendCmdC(C_SCAN)
        handler.postDelayed({ claimRadioAudioSession() }, 250)
    }

    override fun stopScan() {
        log("stopScan()")
        stopSeek() 
    }

    // --- Audio Focus ---
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        log("Audio Focus Change: $focusChange")
    }

    // --- Service Connection ---
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            log("Connected to Toolkit Service: $name")
            try {
                remoteToolkit = IRemoteToolkit.Stub.asInterface(service)
                remoteModule = remoteToolkit?.getRemoteModule(MODULE_RADIO)
                remoteMain = remoteToolkit?.getRemoteModule(MODULE_MAIN)
                
                log("Modules acquired: Radio=$remoteModule Main=$remoteMain")
                onConnectionStateChange?.invoke(true)
                
                // Register Callbacks
                for (i in 0..100) {
                    remoteModule?.register(moduleCallback, i, 1)
                }
                log("Callbacks registered 0..100")

                // Initialize
                requestRadioFocus()
                initializeRadio()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Modules", e)
                debugLog?.invoke("Error: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log("Service Disconnected: $name")
            onConnectionStateChange?.invoke(false)
            remoteToolkit = null
            remoteModule = null
            remoteMain = null
        }
    }

    // --- RadioBackend implementation ---

    override fun start() {
        log("Starting Backend (binding to com.syu.ms.toolkit)...")
        try {
            val intent = Intent("com.syu.ms.toolkit")
            intent.setPackage("com.syu.ms")
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            log("bindService result = $bound")
            context.sendBroadcast(Intent("android.fyt.action.HIDE"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind", e)
            debugLog?.invoke("Bind Failed: ${e.message}")
        }
    }

    override fun stop() {
        log("Stopping Backend...")
        // Unregister callbacks
        if (remoteModule != null) {
            for (i in 0..100) {
                try {
                    remoteModule?.unregister(moduleCallback, i)
                } catch (e: Exception) { }
            }
        }
        
        // Release Audio Focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            Log.e(TAG, "Unbind failed", e)
        }
        
        remoteToolkit = null
        remoteModule = null
        remoteMain = null
    }

    // --- Core Logic ---
    private fun initializeRadio() {
        log("Initializing Radio module with delayed sequence...")
        
        // Initial Startup Delay to let service stabilize
        handler.postDelayed({
            setSource(APP_ID_RADIO)
            claimRadioAudioSession() // [1] Audio Focus
            registerRadioTransport() // [2] Transport Ownership (The missing link)
            
            // Handshake/Init
            setRegion(RadioRegion.USA)
            setBand(RadioBand.FM1)
            setStereoMode(StereoMode.AUTO)
            setLocalDx(false)
            
            // Required Broadcasts
            try {
                context.sendBroadcast(Intent("com.syu.radio.Launch"))
            } catch(e: Exception) { log("Launch broadcast failed") }

            // Second Stage: Tune and Re-Claim
            handler.postDelayed({
                tuneTo(10470) // Force initial tune to known good freq (104.7)
                claimRadioAudioSession() // [3] Re-claim after tune to lock audio
                stopSeek() 
            }, 500)
            
        }, 300)
    }

    private fun registerRadioTransport() {
        // Required for FYT/ATOTO to "attach" the tuner to this app
        // sending all common variants to cover different firmwares
        val actions = listOf(
            "com.syu.radio.attach",
            "com.syu.radio.start",
            "com.syu.radio.takeover" // Common on newer UIS7862
        )
        
        actions.forEach { action ->
            try {
                val intent = Intent(action)
                intent.setPackage("com.syu.ms") // Target the backend service
                context.sendBroadcast(intent)
                log("Sent Transport Handshake: $action")
            } catch (e: Exception) {
                log("Failed to send $action: ${e.message}")
            }
        }
    }

    private fun claimRadioAudioSession() {
        try {
            val intent = Intent("request.radio.switch_st")
            intent.setPackage("com.syu.ms")
            context.sendBroadcast(intent)
            log("Audio Session Claimed (request.radio.switch_st)")
        } catch (e: Exception) {
            log("Failed to claim radio session: ${e.message}")
        }
    }
    
    private fun requestRadioFocus() {
        log("Requesting Audio Focus...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }
    
    fun setSource(appId: Int) {
        if (remoteMain != null) {
             try {
                 log("Sending source switch to appId=$appId")
                 remoteMain?.cmd(0, intArrayOf(appId), null, null)
             } catch(e: Exception) {
                 Log.e(TAG, "Source switch failed", e)
                 debugLog?.invoke("Source switch error: ${e.message}")
             }
        } else {
             log("Main module not connected (source switch ignored)")
        }
    }

    // --- Helpers ---
    private fun sendCmdC(cKey: Int, vararg params: Int) {
        if (remoteModule == null) {
            log("sendCmdC($cKey, ${params.joinToString()}) -> remoteModule is null")
            return
        }
        try {
            val ints = if (params.isNotEmpty()) params else null
            log("CMD C_$cKey params=${params.joinToString()}")
            remoteModule?.cmd(cKey, ints, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Remote C_* Call Failed", e)
            debugLog?.invoke("C_$cKey error: ${e.message}")
        }
    }

    private fun sendCmdU(uKey: Int, vararg params: Int) {
        if (remoteModule == null) {
            log("sendCmdU($uKey, ${params.joinToString()}) -> remoteModule is null")
            return
        }
        try {
            val ints = if (params.isNotEmpty()) params else null
            log("CMD U_$uKey params=${params.joinToString()}")
            remoteModule?.cmd(uKey, ints, null, null)
        } catch (e: Exception) {
            debugLog?.invoke("U_$uKey error: ${e.message}")
        }
    }

    // --- RadioBackend methods ---

    override fun tuneTo(freq: Int) {
        log("tuneTo($freq)")
        sendCmdC(C_FREQ, FREQ_DIRECT, freq, 0)
        handler.postDelayed({ claimRadioAudioSession() }, 200)
    }

    override fun tuneStepUp() {
        log("tuneStepUp -> C_FREQ STEP +1")
        // C_FREQ (13), mode=STEP(0), step=+1, unused=0
        sendCmdC(C_FREQ, FREQ_BY_STEP, 1, 0)
        handler.postDelayed({ claimRadioAudioSession() }, 200)
    }

    override fun tuneStepDown() {
        log("tuneStepDown -> C_FREQ STEP -1")
        // C_FREQ (13), mode=STEP(0), step=-1, unused=0
        sendCmdC(C_FREQ, FREQ_BY_STEP, -1, 0)
        handler.postDelayed({ claimRadioAudioSession() }, 200)
    }

    override fun seekUp() {
        log("seekUp -> U_SEARCH_STATE FORE")
        sendCmdU(U_SEARCH_STATE, SEARCH_FORE)
        handler.postDelayed({ claimRadioAudioSession() }, 200)
    }

    override fun seekDown() {
        log("seekDown -> U_SEARCH_STATE BACK")
        sendCmdU(U_SEARCH_STATE, SEARCH_BACK)
        handler.postDelayed({ claimRadioAudioSession() }, 200)
    }

    override fun stopSeek() {
        log("stopSeek -> U_SEARCH_STATE STOP")
        sendCmdU(U_SEARCH_STATE, SEARCH_STATE_NONE)
    }

    override fun startScan() {
        log("startScan()")
        sendCmdC(C_SCAN)
        handler.postDelayed({ claimRadioAudioSession() }, 200)
    }

    override fun stopScan() {
        log("stopScan()")
        stopSeek() 
    }

    override fun setBand(band: RadioBand) {
        val id = mapBandEnumToInt(band)
        log("setBand($band) -> id=$id")
        sendCmdC(C_BAND, id)
    }

    override fun setRegion(region: RadioRegion) {
        val area = mapRegionEnumToInt(region)
        log("setRegion($region) -> area=$area")
        sendCmdC(C_AREA, area)
    }

    override fun selectPreset(index: Int) {
        log("selectPreset($index)")
        sendCmdC(C_SELECT_CHANNEL, index)
    }

    override fun savePreset(index: Int) {
        log("savePreset($index)")
        sendCmdC(C_SAVE_CHANNEL, index)
    }

    override fun setStereoMode(mode: StereoMode) {
        val valInt = when(mode) {
            StereoMode.AUTO -> 0
            StereoMode.MONO -> 1
            StereoMode.STEREO -> 2
        }
        log("setStereoMode($mode) -> $valInt")
        sendCmdC(C_STEREO, valInt)
    }

    override fun setLocalDx(isLocal: Boolean) {
        val v = if (isLocal) 1 else 0
        log("setLocalDx($isLocal) -> $v")
        sendCmdC(C_LOC, v)
    }
    
    // --- Mappers ---
    private fun mapBandIntToEnum(band: Int): RadioBand =
        when (band) {
            0 -> RadioBand.FM1
            1 -> RadioBand.FM2
            2 -> RadioBand.FM3
            3 -> RadioBand.AM1
            4 -> RadioBand.AM2
            else -> RadioBand.FM1
        }

    private fun mapBandEnumToInt(band: RadioBand): Int =
        when (band) {
            RadioBand.FM1 -> 0
            RadioBand.FM2 -> 1
            RadioBand.FM3 -> 2
            RadioBand.AM1 -> 3
            RadioBand.AM2 -> 4
        }

    private fun mapRegionIntToEnum(region: Int): RadioRegion =
        when (region) {
            AREA_USA    -> RadioRegion.USA
            AREA_EUROPE -> RadioRegion.EUROPE
            AREA_LATIN  -> RadioRegion.LATIN
            AREA_JAPAN  -> RadioRegion.JAPAN
            AREA_OIRT   -> RadioRegion.OIRT
            AREA_CHINA  -> RadioRegion.CHINA
            else        -> RadioRegion.USA
        }

    private fun mapRegionEnumToInt(region: RadioRegion): Int =
        when (region) {
            RadioRegion.USA    -> AREA_USA
            RadioRegion.EUROPE -> AREA_EUROPE
            RadioRegion.LATIN  -> AREA_LATIN
            RadioRegion.JAPAN  -> AREA_JAPAN
            RadioRegion.OIRT   -> AREA_OIRT
            RadioRegion.CHINA  -> AREA_CHINA
        }
}
