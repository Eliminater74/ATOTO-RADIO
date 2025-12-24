package com.atoto.customradio

enum class RadioBand {
    FM1, FM2, FM3, AM1, AM2
}

enum class RadioRegion {
    USA, EUROPE, LATIN, JAPAN, OIRT, CHINA
}

enum class StereoMode {
    AUTO, MONO, STEREO
}

enum class SearchState {
    NONE, AUTO, SEEK_FORWARD, SEEK_BACKWARD
}

interface RadioBackend {
    // Lifecycle
    fun start()
    fun stop()

    // Basic tuning
    fun tuneTo(freq: Int)          // e.g. 10170 for 101.7 MHz
    fun tuneStepUp()
    fun tuneStepDown()

    // Seek / scan
    fun seekUp()
    fun seekDown()
    fun stopSeek()
    fun startScan()
    fun stopScan()

    // Bands / Region
    fun setBand(band: RadioBand)
    fun setRegion(region: RadioRegion)

    // Presets
    fun selectPreset(index: Int)
    fun savePreset(index: Int)

    // Audio / mode
    fun setStereoMode(mode: StereoMode)
    fun setLocalDx(isLocal: Boolean)

    // Callbacks / Events
    var onFrequencyChanged: ((Int) -> Unit)?
    var onBandChanged: ((RadioBand) -> Unit)?
    var onRegionChanged: ((RadioRegion) -> Unit)?
    var onRdsTextChanged: ((String) -> Unit)?
    var onStationNameChanged: ((String) -> Unit)?
    var onSearchStateChanged: ((SearchState) -> Unit)?

    // Debugging
    fun setDebugLog(logger: (String) -> Unit)
    var onStereoModeChanged: ((StereoMode) -> Unit)?
    var onConnectionStateChange: ((Boolean) -> Unit)?
}
