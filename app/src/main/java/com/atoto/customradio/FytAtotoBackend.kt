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

    // --- Callbacks ---
    override var onFrequencyChanged: ((Int) -> Unit)? = null
    override var onBandChanged: ((RadioBand) -> Unit)? = null
    override var onRegionChanged: ((RadioRegion) -> Unit)? = null
    override var onRdsTextChanged: ((String) -> Unit)? = null
    override var onStationNameChanged: ((String) -> Unit)? = null
    override var onSearchStateChanged: ((SearchState) -> Unit)? = null

    // --- Configuration / State ---
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
        const val FREQ_DIRECT = 1
        const val SEARCH_STATE_NONE = 0
        
        const val AREA_USA    = 0
        const val AREA_LATIN  = 1
        const val AREA_EUROPE = 2
        const val AREA_CHINA  = 2
        const val AREA_OIRT   = 3
        const val AREA_JAPAN  = 4
        
        const val BAND_FM1 = 0 
    }
    
    // --- Internal State ---
    private var remoteToolkit: IRemoteToolkit? = null
    private var remoteModule: IRemoteModule? = null
    private var remoteMain: IRemoteModule? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // --- MCU Callback Implementation ---
    private val moduleCallback = object : IModuleCallback.Stub() {
        override fun update(updateCode: Int, ints: IntArray?, floats: FloatArray?, strs: Array<String>?) {
            handler.post {
                when (updateCode) {
                    G_FREQ -> {
                        val freq = ints?.getOrNull(0) ?: return@post
                        onFrequencyChanged?.invoke(freq)
                    }
                    G_BAND -> {
                        val band = ints?.getOrNull(0) ?: return@post
                        onBandChanged?.invoke(mapBandIntToEnum(band))
                    }
                }
            }
        }
    }

    // --- Audio Focus ---
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Audio Focus Change: $focusChange")
    }

    // --- Service Connection ---
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Connected to Toolkit Service")
            try {
                remoteToolkit = IRemoteToolkit.Stub.asInterface(service)
                remoteModule = remoteToolkit?.getRemoteModule(MODULE_RADIO)
                remoteMain = remoteToolkit?.getRemoteModule(MODULE_MAIN)
                
                // Register Callbacks
                for (i in 0..100) {
                    remoteModule?.register(moduleCallback, i, 1)
                }

                // Initialize
                requestRadioFocus()
                initializeRadio()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Modules", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service Disconnected")
            remoteToolkit = null
            remoteModule = null
            remoteMain = null
        }
    }

    override fun start() {
        Log.d(TAG, "Starting Backend...")
        try {
            val intent = Intent("com.syu.ms.toolkit")
            intent.setPackage("com.syu.ms")
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            context.sendBroadcast(Intent("android.fyt.action.HIDE"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind", e)
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping Backend...")
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
        } catch (e: Exception) {}
        
        remoteToolkit = null
        remoteModule = null
        remoteMain = null
    }

    // --- Core Logic ---
    private fun initializeRadio() {
        setSource(APP_ID_RADIO)
        // Handshake/Init
        setRegion(RadioRegion.USA)
        setBand(RadioBand.FM1)
        sendCmdC(C_STEREO, 0)
        sendCmdC(C_LOC, 0)
        stopSeek()
    }
    
    private fun requestRadioFocus() {
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
             } catch(e: Exception) {}
        }
    }

    // --- Helpers ---
    private fun sendCmdC(cKey: Int, vararg params: Int) {
        if (remoteModule == null) return
        try {
            val ints = if (params.isNotEmpty()) params else null
            remoteModule?.cmd(cKey, ints, null, null)
            Log.d(TAG, "Sent CMD C_$cKey params=${params.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Remote C_* Call Failed", e)
        }
    }

    private fun sendCmdU(uKey: Int, vararg params: Int) {
        if (remoteModule == null) return
        try {
            val ints = if (params.isNotEmpty()) params else null
            remoteModule?.cmd(uKey, ints, null, null)
        } catch (e: Exception) {}
    }

    // --- Interface Implementation ---

    override fun tuneTo(freq: Int) {
        // [FREQ_DIRECT, freq, 0]
        sendCmdC(C_FREQ, FREQ_DIRECT, freq, 0)
    }

    override fun tuneStepUp() {
        sendCmdC(C_FREQ_UP)
    }

    override fun tuneStepDown() {
        sendCmdC(C_FREQ_DOWN)
    }

    override fun seekUp() {
        sendCmdC(C_SEEK_UP)
    }

    override fun seekDown() {
        sendCmdC(C_SEEK_DOWN)
    }

    override fun stopSeek() {
        sendCmdU(U_SEARCH_STATE, SEARCH_STATE_NONE)
    }

    override fun startScan() {
        sendCmdC(C_SCAN)
    }

    override fun stopScan() {
        stopSeek() // Or C_SCAN again
    }

    override fun setBand(band: RadioBand) {
        sendCmdC(C_BAND, mapBandEnumToInt(band))
    }

    override fun setRegion(region: RadioRegion) {
        sendCmdC(C_AREA, mapRegionEnumToInt(region))
    }

    override fun selectPreset(index: Int) {
         sendCmdC(C_SELECT_CHANNEL, index)
    }

    override fun savePreset(index: Int) {
         sendCmdC(C_SAVE_CHANNEL, index)
    }

    override fun setStereoMode(mode: StereoMode) {
        val valInt = when(mode) {
            StereoMode.AUTO -> 0
            StereoMode.MONO -> 1
            StereoMode.STEREO -> 2
        }
        sendCmdC(C_STEREO, valInt)
    }

    override fun setLocalDx(isLocal: Boolean) {
        sendCmdC(C_LOC, if(isLocal) 1 else 0)
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
