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

class RadioManager(private val context: Context) {

    var logCallback: ((String) -> Unit)? = null
    var onFreqChange: ((Int) -> Unit)? = null
    var onBandChange: ((Int) -> Unit)? = null

    companion object {
        const val TAG = "RadioManager"

        const val MODULE_RADIO = 1        // FinalRadio.MODULE_RADIO_BY_MCU
        const val MODULE_MAIN  = 0
        const val APP_ID_RADIO = 1        // Standard Radio
        const val APP_ID_CAR_RADIO = 11   // Not used (mutes on your unit)

        // --- G_* Get/Notify Codes (From MCU / FinalRadio) ---
        const val G_BAND = 0
        const val G_FREQ = 1          // Current frequency update
        const val G_AREA = 2          // Region update
        // const val G_CHANNEL = 3
        // const val G_CHANNEL_FREQ = 4
        // const val G_PTY_ID = 5
        // If MCU sends search state, it'll likely use U_SEARCH_STATE as updateCode (22), not a G_*.

        // --- U_* Update Codes (FinalRadio) ---
        const val U_BAND = 0
        const val U_FREQ = 1
        const val U_AREA = 2
        const val U_CHANNEL = 3
        const val U_CHANNEL_FREQ = 4
        const val U_PTY_ID = 5
        const val U_RDS_AF_ENABLE = 6
        const val U_RDS_TA = 7
        const val U_RDS_TP = 8
        const val U_RDS_TA_ENABLE = 9
        const val U_RDS_PI_SEEK = 10
        const val U_RDS_TA_SEEK = 11
        const val U_RDS_PTY_SEEK = 12
        const val U_RDS_TEXT = 13
        const val U_RDS_CHANNEL_TEXT = 14
        const val U_RDS_ENABLE = 15
        const val U_EXTRA_FREQ_INFO = 16
        const val U_SENSITY_AM = 17
        const val U_SENSITY_FM = 18
        const val U_AUTO_SENSITY = 19
        const val U_SCAN = 20
        const val U_STEREO = 21
        const val U_SEARCH_STATE = 22
        const val U_LOC = 23

        // --- C_* Command Codes (FinalRadio) ---
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
        const val C_FREQ              = 13 // Direct/step tune (mode + value)
        const val C_SENSITY           = 14
        const val C_AUTO_SENSITY      = 15
        const val C_RDS_ENABLE        = 16
        const val C_STEREO            = 17 // FinalRadio.C_STERO
        const val C_LOC               = 18
        const val C_RDS_TA_ENABLE     = 19
        const val C_RDS_AF_ENABLE     = 20
        const val C_RDS_PTY_ENABLE    = 21
        const val C_SEARCH            = 22

        // --- Frequency Modes (FinalRadio) ---
        const val FREQ_BY_STEP  = 0
        const val FREQ_DIRECT   = 1
        const val FREQ_BY_RATIO = 2

        // --- Search States (FinalRadio) ---
        const val SEARCH_STATE_NONE = 0
        const val SEARCH_STATE_AUTO = 1
        const val SEARCH_STATE_FORE = 2
        const val SEARCH_STATE_BACK = 3

        // --- Regions (FinalRadio) ---
        const val AREA_USA    = 0
        const val AREA_LATIN  = 1
        const val AREA_EUROPE = 2
        const val AREA_CHINA  = 2
        const val AREA_OIRT   = 3
        const val AREA_JAPAN  = 4

        // --- Bands (this mapping comes from radio UI, not FinalRadio; keep your own if known) ---
        const val BAND_FM1 = 0
    }

    private var remoteToolkit: IRemoteToolkit? = null
    private var remoteModule: IRemoteModule? = null
    private var remoteMain: IRemoteModule? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // --- MCU Callback Implementation ---
    private val moduleCallback = object : IModuleCallback.Stub() {
        override fun update(updateCode: Int, ints: IntArray?, floats: FloatArray?, strs: Array<String>?) {
            val intsStr = ints?.joinToString() ?: "null"
            // VERBOSE LOGGING: Log everything to help identify correct IDs
            if (updateCode != 0) { // Skip periodic heartbeat if it's too noisy
                val logMsg = "MCU Debug: Code=$updateCode, ints=[$intsStr]"
                Log.d(TAG, logMsg)
                handler.post { logCallback?.invoke(logMsg) }
            }
            
            handler.post {
                when (updateCode) {
                    G_FREQ -> {
                        val freq = ints?.getOrNull(0) ?: return@post
                        onFreqChange?.invoke(freq)
                    }
                    G_BAND -> {
                        val band = ints?.getOrNull(0) ?: return@post
                        onBandChange?.invoke(band)
                    }
                }
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Audio Focus Change: $focusChange")
        logCallback?.invoke("Audio Focus: $focusChange")
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryCount = 0
    private val sourceSwitchRunnable = object : Runnable {
        override fun run() {
            if (remoteMain != null && retryCount < 5) {
                try {
                    val payload = intArrayOf(APP_ID_RADIO)
                    remoteMain?.cmd(0, payload, null, null)
                    val msg = "Requested App ID 1 (Switch) - Try ${retryCount + 1}"
                    Log.d(TAG, msg)
                    logCallback?.invoke(msg)
                    retryCount++
                    handler.postDelayed(this, 1000)
                } catch (e: Exception) {
                    val err = "Switch Failed: ${e.message}"
                    Log.e(TAG, err, e)
                    logCallback?.invoke(err)
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val msg = "Connected to Toolkit Service"
            Log.d(TAG, msg)
            logCallback?.invoke(msg)
            try {
                remoteToolkit = IRemoteToolkit.Stub.asInterface(service)
                remoteModule = remoteToolkit?.getRemoteModule(MODULE_RADIO)
                remoteMain = remoteToolkit?.getRemoteModule(MODULE_MAIN)
                
                logCallback?.invoke("Modules Acquired: Radio=$remoteModule, Main=$remoteMain")
                
                // Register Callbacks (0-100 as per OEM source)
                for (i in 0..100) {
                    remoteModule?.register(moduleCallback, i, 1)
                }
                logCallback?.invoke("Callbacks Registered")

                // FORCE UPDATE: Request current state
                // Note: IRemoteModule does not have a get() method. 
                // Status should be updated via callbacks on register().
                /*
                try {
                    remoteModule?.get(G_FREQ, null, null, null)
                    remoteModule?.get(G_BAND, null, null, null)
                    logCallback?.invoke("MCU Status Requested (get)")
                } catch (e: Exception) {
                    Log.e(TAG, "Get failed", e)
                }
                */

                // Request Audio Focus
                requestRadioFocus()

                // Initialize radio settings after connection
                initializeRadio()
                
                // Start Source Switch Loop
                // User requirement: "Start clean ... DO NOT SEND repeatedly switching app ID"
                // Disabling redundant loop as setSource is called in initializeRadio
                // retryCount = 0
                // handler.post(sourceSwitchRunnable)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Modules", e)
                logCallback?.invoke("Error getting modules: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Disconnected from Syu Service")
            logCallback?.invoke("Service Disconnected")
            remoteToolkit = null
            remoteModule = null
            remoteMain = null
            handler.removeCallbacks(sourceSwitchRunnable)
        }
    }

    private fun initializeRadio() {
        logCallback?.invoke("Initializing Radio module...")
        
        // Recommended initialization order (Start Clean):
        // 1. Force Source to Radio
        setSource(APP_ID_RADIO)
        
        // 2. Set region 
        setRegion(AREA_USA)
        
        // 3. Set band (0 = FM1)
        setBand(BAND_FM1)

        // 4. Initialize Tuner State (Required for MCU to accept commands)
        sendCmdC(C_STEREO, 0) // Auto
        sendCmdC(C_LOC, 0)    // DX
        
        // 5. Ensure no seek is active
        stopSeek()

        logCallback?.invoke("Radio Initialized (Source, Region, Band, Stereo, Loc set)")
        
        // Legacy/NavRadio helper intents commented out:
        /*
        context.sendBroadcast(Intent("com.syu.radio.Launch"))
        */
    }

    private fun requestRadioFocus() {
        logCallback?.invoke("Requesting Audio Focus...")
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
                 remoteMain?.cmd(0, intArrayOf(appId), null, null)
                 val msg = "Manual Source Switch: ID $appId"
                 Log.d(TAG, msg)
                 logCallback?.invoke(msg)
             } catch(e: Exception) {
                 logCallback?.invoke("Source Switch Err: ${e.message}")
             }
        } else {
             logCallback?.invoke("Main Module Not Connected")
        }
    }
    
    fun sendFytIntent(action: String) {
        logCallback?.invoke("Sending Intent: $action")
        try {
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)
        } catch (e: Exception) {}
    }

    fun startRadio() {
        Log.d(TAG, "Binding to com.syu.ms.toolkit ...")
        logCallback?.invoke("Binding to Service...")
        try {
            // Correct Intent Action verified from Stock Source
            val intent = Intent("com.syu.ms.toolkit")
            intent.setPackage("com.syu.ms")
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            
            // FYT specific intents to wake up the system/UI
            // context.sendBroadcast(Intent("com.syu.radio.Launch")) // Removed to prevent conflict
            context.sendBroadcast(Intent("android.fyt.action.HIDE"))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind", e)
            logCallback?.invoke("Bind Failed: ${e.message}")
        }
    }

    fun stopRadio() {
        Log.d(TAG, "Stopping Radio...")
        logCallback?.invoke("Stopping Radio...")
        handler.removeCallbacks(sourceSwitchRunnable)
        
        // Unregister callbacks
        if (remoteModule != null) {
            for (i in 0..100) {
                try {
                    remoteModule?.unregister(moduleCallback, i)
                } catch (e: Exception) {}
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
        logCallback?.invoke("Radio Stopped")
    }
    
    // --- Helper to send Binder Commands ---
    // For C_* commands (what the MCU actually expects in cmd)
    private fun sendCmdC(cKey: Int, vararg params: Int) {
        if (remoteModule == null) {
            Log.w(TAG, "Radio Module not connected!")
            return
        }
        try {
            val ints = if (params.isNotEmpty()) params else null
            remoteModule?.cmd(cKey, ints, null, null)
            Log.d(TAG, "Sent CMD C_$cKey params=${params.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Remote C_* Call Failed", e)
        }
    }

    // Keep this ONLY for U_* stuff if you later find you need it
    private fun sendCmdU(uKey: Int, vararg params: Int) {
        if (remoteModule == null) {
            Log.w(TAG, "Radio Module not connected!")
            return
        }
        try {
            val ints = if (params.isNotEmpty()) params else null
            remoteModule?.cmd(uKey, ints, null, null)
            Log.d(TAG, "Sent CMD: U_$uKey params=${params.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Remote U_* Call Failed", e)
        }
    }

    fun tuneUp() {
        // simple step up: C_FREQ_UP, no params
        sendCmdC(C_FREQ_UP)
        logCallback?.invoke("Sent Tune UP (C_FREQ_UP)")
    }

    fun tuneDown() {
        // simple step down: C_FREQ_DOWN, no params
        sendCmdC(C_FREQ_DOWN)
        logCallback?.invoke("Sent Tune DOWN (C_FREQ_DOWN)")
    }
    
    fun tuneTo(freqInt: Int) {
        // Direct tune via C_FREQ with mode + value:
        // [FREQ_DIRECT, freqInt, 0 (bandIndex optional/dummy)]
        sendCmdC(C_FREQ, FREQ_DIRECT, freqInt, 0)
        logCallback?.invoke("Sent Direct Tune to $freqInt")
    }

    fun seekUp() {
        sendCmdC(C_SEEK_UP)
        logCallback?.invoke("Sent Seek UP (C_SEEK_UP)")
    }

    fun seekDown() {
        sendCmdC(C_SEEK_DOWN)
        logCallback?.invoke("Sent Seek DOWN (C_SEEK_DOWN)")
    }

    fun stopSeek() {
        // Stop seek/scan: U_SEARCH_STATE, SEARCH_STATE_NONE
        // keep using U_SEARCH_STATE if you’ve seen it in stock code
        sendCmdU(U_SEARCH_STATE, SEARCH_STATE_NONE)
        logCallback?.invoke("Sent Stop Seek (U_SEARCH_STATE=NONE)")
    }

    // --- Additional methods (updated for C_* codes) ---
    fun setBand(band: Int) {
        sendCmdC(C_BAND, band)
        logCallback?.invoke("Set Band to $band (C_BAND)")
    }

    fun setRegion(region: Int) {
        sendCmdC(C_AREA, region)
        logCallback?.invoke("Set Region to $region (C_AREA)")
    }

    fun scan() {
        // Most firmwares: C_SCAN toggles or starts scan
        sendCmdC(C_SCAN)
        logCallback?.invoke("Sent Scan (C_SCAN)")
    }
    
    fun autoSearch() {
        // Implement auto search/store
         sendCmdC(C_SEARCH) // Or C_AUTO_SENSITY? keeping generic for now or stub
    }

    fun nextPreset() {
        tuneUp()  // Fallback
    }

    fun prevPreset() {
        tuneDown()  // Fallback
    }
    
    fun toggleStereo() {
        // You can do a simple toggle with C_STEREO and mode if stock does that.
        // Start with forcing "auto" or "mono" and see how MCU responds.
        sendCmdC(C_STEREO, 0) // e.g. 0 = auto; confirm via logs/behaviour
        logCallback?.invoke("Sent Stereo toggle (C_STEREO)")
    }
    
    fun setLocalDx(isLocal: Boolean) {
        // C_LOC (18) usually takes 0 = DX, 1 = Local
        sendCmdC(C_LOC, if (isLocal) 1 else 0)
        logCallback?.invoke("Set Local/DX via C_LOC: ${if (isLocal) "LOCAL" else "DX"}")
    }

    fun powerOn() {
        // No explicit power command; handled by source switch
    }

    fun powerOff() {
        // Same
    }

    private fun sendIntent(action: String) {
        try {
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)
        } catch (e: Exception) {}
    }
}
