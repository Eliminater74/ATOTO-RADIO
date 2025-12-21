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
        const val MODULE_RADIO = 1 
        const val MODULE_MAIN  = 0
        const val APP_ID_RADIO = 1 // Standard Radio
        const val APP_ID_CAR_RADIO = 11 // Alternative Radio ID for some UIS7862
        
        // --- G_* Get/Notify Codes (From MCU) ---
        const val G_FREQ = 1          // Current frequency update
        const val G_BAND = 2          // Current band update
        const val G_AREA = 3          // Current area update
        const val G_SEARCH_STATE = 22 // Current search/seek state

        // --- U_* Update Codes (Direct MCU Commands) ---
        const val U_FREQ = 1          // Set frequency / tune
        const val U_AREA = 2          // Set region
        const val U_BAND = 0          // Set band
        const val U_SCAN = 20         // Start/Stop Scan
        const val U_SEARCH_STATE = 22 // Set search state (seek/scan)
        
        // --- Frequency Modes ---
        const val FREQ_BY_STEP = 0
        const val FREQ_DIRECT = 1
        
        // --- Search States ---
        const val SEARCH_STATE_NONE = 0
        const val SEARCH_STATE_FORE = 2  // Seek forward
        const val SEARCH_STATE_BACK = 3  // Seek backward
        
        // --- Regions ---
        const val AREA_USA = 0
        const val AREA_EUROPE = 2  // Default for many FYT units
        
        // --- Bands (example; may need adjustment) ---
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
                try {
                    remoteModule?.get(G_FREQ, null, null, null)
                    remoteModule?.get(G_BAND, null, null, null)
                    logCallback?.invoke("MCU Status Requested (get)")
                } catch (e: Exception) {
                    Log.e(TAG, "Get failed", e)
                }

                // Request Audio Focus
                requestRadioFocus()

                // Initialize radio settings after connection
                initializeRadio()
                
                // Start Source Switch Loop
                retryCount = 0
                handler.post(sourceSwitchRunnable)
                
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
        
        // 1. Force Source (Try 11 first, fallback to 1)
        setSource(APP_ID_CAR_RADIO)
        logCallback?.invoke("Requested App ID 11")
        
        handler.postDelayed({
            setSource(APP_ID_RADIO)
            logCallback?.invoke("Requested App ID 1")
        }, 500)

        // 2. Set region 
        sendCmd(U_AREA, AREA_USA)
        
        // 3. Set band
        sendCmd(U_BAND, BAND_FM1)

        // 4. Kickstart Service Helper (Found in NavRadio+/br1.java)
        try {
            val loggerIntent = Intent("com.syu.radio.Logger")
            loggerIntent.setPackage("com.syu.radio")
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(loggerIntent)
            } else {
                context.startService(loggerIntent)
            }
            logCallback?.invoke("Started Logger Service")
        } catch (e: Exception) {
            Log.e(TAG, "Logger start failed", e)
        }
        
        // 5. Broadcast Launch (Legacy wake-up)
        context.sendBroadcast(Intent("com.syu.radio.Launch"))
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
    private fun sendCmd(uKey: Int, vararg params: Int) {
        if (remoteModule == null) {
            Log.w(TAG, "Radio Module not connected!")
            return
        }
        try {
            val ints = if (params.isNotEmpty()) params else null
            remoteModule?.cmd(uKey, ints, null, null)
            Log.d(TAG, "Sent CMD: U_$uKey params=${params.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Remote Call Failed", e)
        }
    }

    fun tuneUp() {
        // Step up: U_FREQ, FREQ_BY_STEP, +1
        sendCmd(U_FREQ, FREQ_BY_STEP, 1)
        logCallback?.invoke("Sent Tune UP (Step +1)")
    }

    fun tuneDown() {
        // Step down: U_FREQ, FREQ_BY_STEP, -1
        sendCmd(U_FREQ, FREQ_BY_STEP, -1)
        logCallback?.invoke("Sent Tune DOWN (Step -1)")
    }
    
    fun tuneTo(freqInt: Int) {
        // Direct tune: U_FREQ, FREQ_DIRECT, freqInt (FM in 10kHz units, e.g., 10170)
        sendCmd(U_FREQ, FREQ_DIRECT, freqInt)
        logCallback?.invoke("Sent Direct Tune to $freqInt")
    }

    fun seekUp() {
        // Seek forward: U_SEARCH_STATE, SEARCH_STATE_FORE
        sendCmd(U_SEARCH_STATE, SEARCH_STATE_FORE)
        logCallback?.invoke("Sent Seek UP")
    }

    fun seekDown() {
        // Seek backward: U_SEARCH_STATE, SEARCH_STATE_BACK
        sendCmd(U_SEARCH_STATE, SEARCH_STATE_BACK)
        logCallback?.invoke("Sent Seek DOWN")
    }

    fun stopSeek() {
        // Stop seek/scan: U_SEARCH_STATE, SEARCH_STATE_NONE
        sendCmd(U_SEARCH_STATE, SEARCH_STATE_NONE)
        logCallback?.invoke("Sent Stop Seek")
    }

    // --- Additional methods (updated for U_* codes) ---
    fun setBand(band: Int) {
        sendCmd(U_BAND, band)
        logCallback?.invoke("Set Band to $band")
    }

    fun setRegion(region: Int) {
        sendCmd(U_AREA, region)
        logCallback?.invoke("Set Region to $region")
    }

    // Stubbed methods (implement as needed)
    fun scan() {
        // Implement scan if needed (U_SCAN = 20, param 1 to start)
    }
    
    fun autoSearch() {
        // Implement auto search/store
    }

    fun nextPreset() {
        tuneUp()  // Fallback
    }

    fun prevPreset() {
        tuneDown()  // Fallback
    }
    
    fun toggleStereo() {
        // U_STEREO = 21, param 0/1/2 (auto/mono/stereo)
        sendCmd(21, 0)  // Example: auto
    }
    
    fun setLocalDx(isLocal: Boolean) {
        // U_LOC = 23, param 0=DX, 1=LOCAL
        sendCmd(23, if (isLocal) 1 else 0)
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
