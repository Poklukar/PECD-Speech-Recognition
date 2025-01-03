package com.example.speechrecognitionapp

interface RecordingCallback {
    fun onDataUpdated(data: ArrayList<Result>)
    fun onDataClear()
    fun updateSoundIntensity(dB: Double)
}