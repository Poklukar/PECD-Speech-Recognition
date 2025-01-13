package com.example.speechrecognitionapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import kotlin.collections.ArrayList
import com.konovalov.vad.yamnet.config.FrameSize
import com.konovalov.vad.yamnet.config.Mode
import com.konovalov.vad.yamnet.config.SampleRate
import com.konovalov.vad.yamnet.VadYamnet
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat

class AudioRecordingService : Service() {

    companion object {
        private val TAG = AudioRecordingService::class.simpleName

        private const val SAMPLE_RATE = 16000
        private const val AUDIO_CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_INPUT = MediaRecorder.AudioSource.MIC


        private const val DESIRED_LENGTH_SECONDS = 1
        private const val RECORDING_LENGTH = SAMPLE_RATE * DESIRED_LENGTH_SECONDS // in seconds

        // MFCC parameters
        private const val NUM_MFCC = 13
        // private const val NUM_FILTERS = 26
        // private const val FFT_SIZE = 2048

        // Notifications
        private const val CHANNEL_ID = "word_recognition"
        private const val NOTIFICATION_ID = 202
    }
    // Tweak parameters
    private var energyThreshold = 0.1
    private var probabilityThreshold = 0.002f
    private var windowSize = SAMPLE_RATE / 2
    private var topK = 3

    private var firebaseUrl = "https://speechrecognitionapp-3477c-default-rtdb.europe-west1.firebasedatabase.app/"

    private var recordingBufferSize = 0

    private var audioRecord: AudioRecord? = null
    private var audioRecordingThread: Thread? = null

    var isRecording: Boolean = false
    var recordingBuffer: DoubleArray = DoubleArray(RECORDING_LENGTH)
    var audioData: ByteArray = ByteArray(RECORDING_LENGTH)
    var interpreter: Interpreter? = null

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var notification: Notification? = null

    private var callback: RecordingCallback? = null

    private var isBackground = true

    inner class RunServiceBinder : Binder() {
        val service: AudioRecordingService
            get() = this@AudioRecordingService
    }

    var serviceBinder = RunServiceBinder()

    private var wordCount: Int = 0


    override fun onCreate() {
        Log.d(TAG, "Creating service")
        super.onCreate()

        createNotificationChannel()

        recordingBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNELS, AUDIO_FORMAT)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        audioRecord = AudioRecord(AUDIO_INPUT, SAMPLE_RATE, AUDIO_CHANNELS, AUDIO_FORMAT, recordingBufferSize)
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Binding service")

        return serviceBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Starting service")

        if (intent != null) {
            val bundle = intent.extras
            if (bundle != null) {
                energyThreshold = bundle.getDouble("energyThreshold")
                probabilityThreshold = bundle.getFloat("probabilityThreshold")
                windowSize = bundle.getInt("windowSize")
                topK = bundle.getInt("topK")
            }
            Log.d(TAG, "Energy threshold: $energyThreshold")
            Log.d(TAG, "Probability threshold: $probabilityThreshold")
            Log.d(TAG, "Window size: $windowSize")
        }

        startRecording()

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
        channel.description = getString(R.string.channel_desc)
        channel.enableLights(true)
        channel.lightColor = Color.BLUE
        channel.enableVibration(true)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.baseline_notifications_24)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        val resultIntent = Intent(this, MainActivity::class.java)
        resultIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.setContentIntent(resultPendingIntent)

        notificationBuilder = builder
        return builder.build()
    }

    private fun updateNotification(label: String) {
        if (isBackground) return
        if (notificationBuilder == null) {
            return
        } else {
            notificationBuilder?.setContentText(getText(R.string.notification_prediction).toString() + " " + label)
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder?.build())
    }

    fun setCallback(callback: RecordingCallback) {
        this.callback = callback
    }

    private fun updateData(data: ArrayList<Result>) {
        // Sort results
        Collections.sort(data, object : Comparator<Result> {
            override fun compare(o1: Result, o2: Result): Int {
                return o2.confidence.compareTo(o1.confidence)
            }
        })

        // Keep top K results
        if (data.size > topK) {
            data.subList(topK, data.size).clear()
        }

        if (data.isNotEmpty() && data[0].confidence != 0.0) {
            callback?.onDataUpdated(data)
            writeToFirebase(data)
        } else {
            Log.d(TAG, "Top result's confidence is 0, not writing to Firebase")
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Access denied
            return
        }
        isRecording = true
        wordCount = 0
        // Launching a coroutine in the background
        CoroutineScope(Dispatchers.IO).launch {
            record()
        }
    }

    private fun record() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Access denied
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!")
            return
        }

        audioRecord?.startRecording()
        Log.v(TAG, "Start recording")

        var firstLoop = true
        var totalSamplesRead: Int
        val tempRecordingBuffer = DoubleArray(SAMPLE_RATE - windowSize)

        val vad = VadYamnet(
            this,
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_243,
            mode = Mode.NORMAL,
            silenceDurationMs = 30,
            speechDurationMs = 30
        )

        while (isRecording) {
            if (!firstLoop) {
                totalSamplesRead = SAMPLE_RATE - windowSize
            } else {
                totalSamplesRead = 0
                firstLoop = false
            }

            while (totalSamplesRead < SAMPLE_RATE) {
                val remainingSamples = SAMPLE_RATE - totalSamplesRead
                val samplesToRead = if (remainingSamples > recordingBufferSize) recordingBufferSize else remainingSamples
                val audioBuffer = ShortArray(samplesToRead)
                //val audioBuffer = ShortArray(FrameSize.FRAME_SIZE_512.value)
                val read = audioRecord?.read(audioBuffer, 0, samplesToRead)

                if (read != AudioRecord.ERROR_INVALID_OPERATION && read != AudioRecord.ERROR_BAD_VALUE) {
                    for (i in 0 until read!!) {
                        recordingBuffer[totalSamplesRead + i] = audioBuffer[i].toDouble() / Short.MAX_VALUE
                    }
                    totalSamplesRead += read
                }
            }


            val rms = calculateRMS(recordingBuffer)
            val dB = 20 * Math.log10(rms)

            // Update the dB value in HomeFragment
            callback?.updateSoundIntensity(dB)

            Log.d(TAG, dB.toString())
            if (dB > -40) {
                val shortBuffer = ShortArray(recordingBuffer.size)
                for (i in recordingBuffer.indices) {
                    shortBuffer[i] = (recordingBuffer[i] * Short.MAX_VALUE).toInt().toShort()
                }


                val sc = vad.classifyAudio(shortBuffer)
                when (sc.label) {
                    "Speech" -> {
                        computeBuffer(recordingBuffer)
                        Log.d(TAG, "Model predicted speech")
                    }
                    else -> {
                        Log.d(TAG, "Model DID NOT predict speech")
                        callback?.onDataClear()
                    }
                }

            } else {
                callback?.onDataClear()
            }

            Thread.sleep(50)

            // Use a circular buffer to avoid frequent array copying - less power consumption
            System.arraycopy(recordingBuffer, windowSize, tempRecordingBuffer, 0, recordingBuffer.size - windowSize)
            recordingBuffer = DoubleArray(RECORDING_LENGTH)
            System.arraycopy(tempRecordingBuffer, 0, recordingBuffer, 0, tempRecordingBuffer.size)
        }
        stopRecording()
    }

    private fun calculateRMS(buffer: DoubleArray): Double {
        var sum = 0.0
        for (sample in buffer) {
            sum += sample * sample
        }
        return Math.sqrt(sum / buffer.size)
    }

    private fun writeToFirebase(topKData: ArrayList<Result>) {
        val database = FirebaseDatabase.getInstance(firebaseUrl)
        val ref = database.getReference("topKResults")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        topKData.forEach { result ->
            val dataToWrite = mapOf(
                "label" to result.label,
                "confidence" to String.format(Locale.US,"%.3f", result.confidence).toDouble(),
                "timestamp" to dateFormat.format(Date())
            )

            ref.push().setValue(dataToWrite)
                .addOnSuccessListener {
                    Log.d("Firebase", "Top K data successfully written to Firebase")
                }
                .addOnFailureListener { e ->
                    Log.e("Firebase", "Failed to write top K data to Firebase", e)
                }
        }

    }

    private fun writeWordCountToFirebase() {
        val database = FirebaseDatabase.getInstance(firebaseUrl)
        val ref = database.getReference("wordCount")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val dataToWrite = mapOf(
            "wordCount" to wordCount,
            "timestamp" to dateFormat.format(Date())
        )

        ref.push().setValue(dataToWrite)
            .addOnSuccessListener {
                Log.d(TAG, "Word count successfully written to Firebase")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to write word count to Firebase", e)
            }
    }

    private fun computeBuffer(audioBuffer: DoubleArray) {
        val mfccConvert = MFCC()
        mfccConvert.setSampleRate(SAMPLE_RATE)
        val nMFCC = NUM_MFCC
        mfccConvert.setN_mfcc(nMFCC)
        val mfccInput = mfccConvert.process(audioBuffer)
        val nFFT = mfccInput.size / nMFCC
        val mfccValues = Array(nMFCC) { FloatArray(nFFT) }

        //loop to convert the mfcc values into multi-dimensional array
        for (i in 0 until nFFT) {
            var indexCounter = i * nMFCC
            val rowIndexValue = i % nFFT
            for (j in 0 until nMFCC) {
                mfccValues[j][rowIndexValue] = mfccInput[indexCounter]
                indexCounter++
            }
        }

        Log.d(TAG, "MFCC Shape: ${mfccValues.size}, ${mfccValues[0].size}")

        // Pass matrix to model
        loadAndPredict(mfccInput)
    }

    private fun loadAndPredict(mfccs: FloatArray) {
        val mappedByteBuffer = FileUtil.loadMappedFile(this, "model_16K_LR.tflite")
        interpreter = Interpreter(mappedByteBuffer)

        val imageTensorIndex = 0
        val imageShape = interpreter?.getInputTensor(imageTensorIndex)?.shape()
        val imageDataType = interpreter?.getInputTensor(imageTensorIndex)?.dataType()

        val probabilityTensorIndex = 0
        val probabilityShape = interpreter?.getOutputTensor(probabilityTensorIndex)?.shape()
        val probabilityDataType = interpreter?.getOutputTensor(probabilityTensorIndex)?.dataType()

        val imageInputBuffer: TensorBuffer = TensorBuffer.createFixedSize(imageShape, imageDataType)
        imageInputBuffer.loadArray(mfccs, imageShape)

        val outputTensorBuffer = TensorBuffer.createFixedSize(probabilityShape, probabilityDataType)

        interpreter?.run(imageInputBuffer.buffer, outputTensorBuffer.buffer)

        var axisLabels: List<String>? = null
        try {
            axisLabels = FileUtil.loadLabels(this, "labels.txt")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading label file", e)
        }

        val probabilityProcessor: TensorProcessor = TensorProcessor.Builder().build()
        if (axisLabels != null) {
            val labels = TensorLabel(axisLabels, probabilityProcessor.process(outputTensorBuffer)).mapWithFloatValue
            val results = ArrayList<Result>()
            for (label in labels) {
                results.add(Result(label.key, label.value.toDouble()))
            }
            Log.d(TAG, "Labels are: $labels")
            val result = labels.maxBy { it.value }.key
            val value = labels.maxBy { it.value }.value
            if (value!! > probabilityThreshold) {
                Log.d(TAG, "Result: $result")
                Log.d(TAG, "Result: ${labels.maxBy { it.value }}")

                if (value > 0.5) {
                    //if the prediction is high, pause for some time, so no false further predictions will be made
                    wordCount++
                    updateData(results)
                    notification = createNotification()
                    updateNotification(result!!)
                    Thread.sleep(500)
                } else {
                    //if the prediction is too low, discard it
                    val noWordResult = Result(" ¯\\_(ツ)_/¯", 0.0)
                    results.clear()
                    results.add(noWordResult)
                    updateData(results)
                }
            }


        }
    }

    @SuppressLint("ForegroundServiceType")
    fun foreground() {
        notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        isBackground = false
    }

    fun background() {
        isBackground = true
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun stopRecording() {
        isRecording = false
        callback?.updateSoundIntensity(0.0)
        audioRecord?.stop()
        //writeWordCountToFirebase()
        //wordCount = 0
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
        Log.d(TAG, "Destroying service")
    }
}