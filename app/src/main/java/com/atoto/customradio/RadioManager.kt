package com.atoto.customradio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.syu.ipc.IRemoteModule
import com.syu.ipc.IRemoteToolkit

class RadioManager(private val context: Context) {

    var logCallback: ((String) -> Unit)? = null

    companion object {
        const val TAG = "RadioManager"
        const val MODULE_RADIO = 1 
        const val MODULE_MAIN  = 0
        const val MODULE_CANBUS = 7
        const val APP_ID_RADIO = 1 // User confirmed ID 1 gives sound
        const val APP_ID_NULL  = 0
        
        // --- C_* Command Codes (Actionable) ---
        const val C_FREQ_UP      = 3  // Tune Up (Step)
        const val C_FREQ_DOWN    = 4  // Tune Down (Step)
        const val C_SEEK_UP      = 5  // Seek Up (Search)
        const val C_SEEK_DOWN    = 6  // Seek Down (Search)
        const val C_SCAN         = 9  // Scan/Browse
        const val C_SAVE         = 10 // Store/Save
        const val C_BAND         = 11 // Change Band
        const val C_FREQ         = 13 // Set Frequency Direct
        const val C_SEARCH       = 22 // Auto Search/Store (AMS)
        
        // --- U_* Update Codes (For reference/callbacks) ---
        const val U_BAND         = 0
        const val U_FREQ         = 1
        
        const val REGION_USA = 1 
    }

    private var remoteToolkit: IRemoteToolkit? = null
    private var remoteModule: IRemoteModule? = null
    private var remoteMain: IRemoteModule? = null
    private var remoteCanbus: IRemoteModule? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryCount = 0
    private val sourceSwitchRunnable = object : Runnable {
        override fun run() {
            if (remoteMain != null && retryCount < 5) {
                try {
                    val payload = intArrayOf(APP_ID_RADIO)
                    remoteMain?.cmd(0, payload, null, null)
                    val msg = "Requested App ID 11 (Switch) - Try ${retryCount + 1}"
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
                remoteCanbus = remoteToolkit?.getRemoteModule(MODULE_CANBUS)
                
                logCallback?.invoke("Modules: Radio=$remoteModule, Main=$remoteMain, Canbus=$remoteCanbus")
                
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
            remoteCanbus = null
            handler.removeCallbacks(sourceSwitchRunnable)
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
        sendIntent(action)
    }

    fun startRadio() {
        Log.d(TAG, "Binding to com.syu.ms.toolkit ...")
        logCallback?.invoke("Binding to Service...")
        try {
            // Correct Intent Action verified from Stock Source
            val intent = Intent("com.syu.ms.toolkit")
            intent.setPackage("com.syu.ms")
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            
            // FYT specific intents
            // Stock uses HIDE in onResume (Active), SHOW in onPause. 
            // So we send HIDE to say "We are Active".
            context.startService(Intent("android.fyt.action.HIDE"))
            
            // Request Audio Focus (Crucial for Audio Mux)
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val res = am.requestAudioFocus(
                android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { /* Handle focus change */ }
                .build()
            )
            val focusMsg = if (res == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "Focus GRANTED" else "Focus FAILED"
            Log.d(TAG, focusMsg)
            logCallback?.invoke(focusMsg)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind or focus", e)
            logCallback?.invoke("Init Error: ${e.message}")
        }
    }

    fun stopRadio() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.abandonAudioFocus(null)
            
            // Return to "Background" state
            context.startService(Intent("android.fyt.action.SHOW"))
            
            if (remoteMain != null) {
                // Return to Null/Release state (Safer than force 11)
                remoteMain?.cmd(0, intArrayOf(APP_ID_NULL), null, null)
                logCallback?.invoke("Radio Stopped (Sent AppID 0)")
            }
        } catch(e: Exception) {
            Log.e(TAG, "Stop Failed", e)
        }
    }
    
    // --- Helper to send Binder Commands ---
    private fun sendCmd(cKey: Int, vararg params: Int) {
        if (remoteModule == null) {
            Log.w(TAG, "Radio Module not connected!")
            return
        }
        try {
            val ints = if (params.isNotEmpty()) params else null
            
            // This maps strictly to: remoteModule.cmd(cKey, ints, null, null)
            // For Tune: cKey=1, ints=[Mode, Value]
            remoteModule?.cmd(cKey, ints, null, null)
            
            val msg = "Sent CMD: C_$cKey params=${params.joinToString()}"
            Log.d(TAG, msg)
            logCallback?.invoke(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Remote Call Failed", e)
            logCallback?.invoke("Cmd Failed: ${e.message}")
        }
    }

    fun tuneUp() {
        // Universal: Code 1 (U_FREQ), Step 0, +1
        sendCmd(1, 0, 1)
        logCallback?.invoke("Sent Tune UP (Step +1)")
    }

    fun tuneDown() {
        // Universal: Code 1 (U_FREQ), Step 0, -1
        sendCmd(1, 0, -1)
        logCallback?.invoke("Sent Tune DOWN (Step -1)")
    }
    
    fun tuneTo(freqInt: Int) {
        // Universal: Code 1 (U_FREQ), Direct 1, Value
        sendCmd(1, 1, freqInt)
        logCallback?.invoke("Sent Direct Tune to $freqInt")
    }

    fun seekUp() {
        sendCmd(C_SEEK_UP)
    }

    fun seekDown() {
        sendCmd(C_SEEK_DOWN)
    }

    fun setBand(band: Int) {
        // C_BAND takes parameter? WCLisRadio sends cmd(11, -1) for toggle?
        // Let's assume sending the band index might work, or just toggle.
        // For now, toggle if no param needed, or send band.
        sendCmd(C_BAND, band)
    }

    fun setFrequency(freqOfBand: Int) {
        sendCmd(C_FREQ, freqOfBand)
    }
    
    fun scan() {
        sendCmd(C_SCAN)
    }
    
    fun autoSearch() {
        sendCmd(C_SEARCH)
    }

    // --- Stubbed or Logic-only methods ---
    fun nextPreset() {
        // Logic to jump to next preset frequency
        // Needs state awareness (which we don't have yet from callbacks)
        // Fallback: Tune Up
        tuneUp()
    }

    fun prevPreset() {
        tuneDown()
    }
    
    fun toggleStereo() {
        // C_STERO = 17 (from FinalRadio)
        sendCmd(17) 
    }
    
    fun setLocalDx(isLocal: Boolean) {
        // C_LOC = 18
        sendCmd(18, if(isLocal) 1 else 0)
    }
    // Normally handled by Audio Focus/Source Switching, but we can try generic
    fun powerOn() {
        // No explicit U_POWER in reference, relying on bind/focus
    }
    fun powerOff() {}



    private fun sendIntent(action: String) {
        try {
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)
        } catch (e: Exception) {}
    }
}
