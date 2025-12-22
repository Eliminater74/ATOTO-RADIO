package com.atoto.customradio

import android.content.Context
import android.util.Log

object RadioBackendFactory {

    fun create(context: Context): RadioBackend {
        return when {
            isFytAtoto(context) -> FytAtotoBackend(context)
            else -> DummyBackend()
        }
    }

    private fun isFytAtoto(context: Context): Boolean {
        return isPackageInstalled(context, "com.syu.ms")
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}

// Minimal dummy for non-FYT testing or fallback
class DummyBackend : RadioBackend {
    override fun start() { Log.d("DummyBackend", "Start") }
    override fun stop() { Log.d("DummyBackend", "Stop") }
    override fun tuneTo(freq: Int) { Log.d("DummyBackend", "TuneTo $freq") }
    override fun tuneStepUp() {}
    override fun tuneStepDown() {}
    override fun seekUp() {}
    override fun seekDown() {}
    override fun stopSeek() {}
    override fun startScan() {}
    override fun stopScan() {}
    override fun setBand(band: RadioBand) {}
    override fun setRegion(region: RadioRegion) {}
    override fun selectPreset(index: Int) {}
    override fun savePreset(index: Int) {}
    override fun setStereoMode(mode: StereoMode) {}
    override fun setLocalDx(isLocal: Boolean) {}

    override var onFrequencyChanged: ((Int) -> Unit)? = null
    override var onBandChanged: ((RadioBand) -> Unit)? = null
    override var onRegionChanged: ((RadioRegion) -> Unit)? = null
    override var onRdsTextChanged: ((String) -> Unit)? = null
    override var onStationNameChanged: ((String) -> Unit)? = null
    override var onSearchStateChanged: ((SearchState) -> Unit)? = null
}
