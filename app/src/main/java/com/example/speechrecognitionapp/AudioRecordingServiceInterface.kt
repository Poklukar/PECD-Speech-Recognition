package com.example.speechrecognitionapp

interface AudioRecordingServiceInterface {
    // Basic service lifecycle
    fun setCallback(callback: RecordingCallback)
    fun foreground()
    fun background()
    fun startRecording()
    fun stopRecording()
    fun writeToFirebase(topKData: ArrayList<Result>)
    fun writeWordCountToFirebase()

    // Shared properties
    val isRecording: Boolean
    var energyThreshold: Double
    var probabilityThreshold: Float
    var windowSize: Int
    var topK: Int
}