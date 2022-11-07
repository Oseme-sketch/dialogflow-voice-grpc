package com.example.dialogflowvoicegrpc

import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.dialogflowvoicegrpc.databinding.ActivityMainBinding
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.dialogflow.cx.v3beta1.*
import com.google.protobuf.ByteString
import io.grpc.CompositeChannelCredentials
import io.grpc.Grpc
import io.grpc.TlsChannelCredentials
import io.grpc.auth.MoreCallCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
private const val BUFFER_SIZE_FACTOR = 2
private const val LOG_TAG = "MainActivity"
private const val RECORD_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val REQUEST_CODE = 200
private const val SAMPLE_RATE = 44100

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionName: String
    private lateinit var stub: SessionsGrpcKt.SessionsCoroutineStub

    private val recordBufferSize: Int = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        RECORD_CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    private var audioFilePath: String = ""
    private var hasPermission: Boolean = false
    private var isRecording: Boolean = false
    private var mode: Boolean = false
    private var permissions: Array<String> = arrayOf(RECORD_AUDIO)
    private var recorder: AudioRecord? = null
    private val sessionId: String = UUID.randomUUID().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bulkButtonView.setOnClickListener {
            mode = false
            when (isRecording) {
                true -> {
                    stopRecording()
                    lifecycleScope.launch {
                        sendAudioToBot(mode)
                    }
                    binding.streamingButtonView.visibility = View.VISIBLE
                }
                false -> {
                    binding.streamingButtonView.visibility = View.INVISIBLE
                    binding.audioHintView.text = ""
                    startRecording(mode)
                }
            }
        }

        binding.streamingButtonView.setOnClickListener {
            mode = true
            when (isRecording) {
                true -> {
                    stopRecording()
                    lifecycleScope.launch {
                        sendAudioToBot(mode)
                    }
                    binding.bulkButtonView.visibility = View.VISIBLE
                }
                false -> {
                    binding.bulkButtonView.visibility = View.INVISIBLE
                    binding.audioHintView.text = ""
                    startRecording(mode)
                }
            }
        }

        hasPermission = (ActivityCompat.checkSelfPermission(this, permissions[0]) ==
                PackageManager.PERMISSION_GRANTED)
        if (!hasPermission) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE)
        }

        audioFilePath = "${cacheDir.absolutePath}/audio_record.pcm"

        setupBot()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        hasPermission = if (requestCode == REQUEST_CODE) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
    }

    override fun onStop() {
        super.onStop()

        isRecording = false

        recorder?.apply {
            stop()
            release()
        }
        recorder = null
    }

    private fun startRecording(mode: Boolean) {
        if (ActivityCompat.checkSelfPermission(
                this,
                permissions[0]
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE)
            return
        }

        when (mode) {
            true -> {
                binding.streamingButtonView.setImageResource(R.drawable.ic_baseline_mic_48)
                binding.streamingButtonView.setColorFilter(
                    ContextCompat.getColor(this, R.color.teal_700),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
            false -> {
                binding.bulkButtonView.setImageResource(R.drawable.ic_baseline_mic_48)
                binding.bulkButtonView.setColorFilter(
                    ContextCompat.getColor(this, R.color.teal_700),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
        }

        lifecycleScope.launch {
            recordAudio()
        }
    }

    @RequiresPermission(RECORD_AUDIO)
    private suspend fun recordAudio() {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(recordBufferSize / 2)
            val outputStream: FileOutputStream?
            var totalDataRead = 0

            try {
                outputStream = FileOutputStream(audioFilePath)
            } catch (e: FileNotFoundException) {
                Log.e(LOG_TAG, "AudioRecord: Audio file not found!")
                return@withContext
            }

            recorder = AudioRecord(
                AUDIO_SOURCE,
                SAMPLE_RATE,
                RECORD_CHANNEL_CONFIG,
                AUDIO_FORMAT,
                recordBufferSize
            )

            if (recorder!!.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(LOG_TAG, "Error initializing AudioRecord")
                return@withContext
            }

            recorder!!.startRecording()

            isRecording = true

            Log.v(LOG_TAG, "Recording started!")

            while (isRecording) {
                val numberOfDataRead = recorder!!.read(buffer,0,buffer.size)
                totalDataRead += numberOfDataRead

                try {
                    outputStream.write(buffer,0,numberOfDataRead)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            Log.v(LOG_TAG, "Total data read: $totalDataRead")

            try {
                outputStream.flush()
                outputStream.close()
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Exception while closing output stream")
                e.printStackTrace()
            }
        }
    }

    private fun stopRecording() {
        isRecording = false

        recorder?.apply {
            stop()
            release()
        }
        recorder = null

        when (mode) {
            true -> {
                binding.streamingButtonView.setImageResource(R.drawable.ic_baseline_mic_off_48)
                binding.streamingButtonView.setColorFilter(
                    ContextCompat.getColor(this, R.color.black),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
            false -> {
                binding.bulkButtonView.setImageResource(R.drawable.ic_baseline_mic_off_48)
                binding.bulkButtonView.setColorFilter(
                    ContextCompat.getColor(this, R.color.black),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
        }

        Log.v(LOG_TAG, "Recording stopped!")
    }

    private suspend fun sendAudioToBot(mode: Boolean) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(recordBufferSize / 2)
            val inputStream: FileInputStream?
            var totalDataWrite = 0
            var numberOfDataRead = 0

            try {
                inputStream = FileInputStream(audioFilePath)
            } catch (e: FileNotFoundException) {
                Log.e(LOG_TAG, "sendAudioToBot: Audio file not found!")
                return@withContext
            }

            when (mode) {
                true -> {
                    val inputAudioConfig: InputAudioConfig = InputAudioConfig.newBuilder()
                        .setAudioEncoding(AudioEncoding.AUDIO_ENCODING_LINEAR_16)
                        .setSampleRateHertz(SAMPLE_RATE)
                        .build()

                    val audioInput: AudioInput = AudioInput.newBuilder()
                        .setConfig(inputAudioConfig)
                        .build()

                    val queryInput: QueryInput = QueryInput.newBuilder()
                        .setAudio(audioInput)
                        .setLanguageCode("en-US")
                        .build()

                    val request: StreamingDetectIntentRequest = StreamingDetectIntentRequest
                        .newBuilder()
                        .setSession(sessionName)
                        .setQueryInput(queryInput)
                        .build()

                    val requestList: MutableList<StreamingDetectIntentRequest> = mutableListOf(request)

                    while (numberOfDataRead != -1) {
                        numberOfDataRead = inputStream.read(buffer,0,buffer.size)
                        if (numberOfDataRead != -1) {
                            val subAudioInput: AudioInput = AudioInput.newBuilder()
                                .setAudio(ByteString.copyFrom(buffer,0,numberOfDataRead))
                                .build()

                            val subQueryInput: QueryInput = QueryInput.newBuilder()
                                .setAudio(subAudioInput)
                                .setLanguageCode("en-US")
                                .build()

                            val subRequest: StreamingDetectIntentRequest = StreamingDetectIntentRequest
                                .newBuilder()
                                .setQueryInput(subQueryInput)
                                .build()

                            requestList.add(subRequest)

                            // Log.d(LOG_TAG, "Audio bytes: ${buffer.contentToString()}")
                            totalDataWrite += numberOfDataRead
                        }
                    }

                    Log.v(LOG_TAG, "Total data write: $totalDataWrite")
                    Log.v(LOG_TAG, "Number of streams: ${requestList.size}")

                    val requests = requestList.asFlow()

                    val responses = stub.streamingDetectIntent(requests)
                    responses.collect {
                        runOnUiThread {
                            updateUI(it.detectIntentResponse)
                        }
                    }

                    // Log.d(LOG_TAG, "recordBufferSize: $recordBufferSize")
                }
                false -> {
                    var audioBytes: ByteArray = byteArrayOf()
                    while (numberOfDataRead != -1) {
                        numberOfDataRead = inputStream.read(buffer,0,buffer.size)
                        if (numberOfDataRead != -1) {
                            audioBytes += buffer
                            // Log.d(LOG_TAG, "Audio bytes: ${buffer.contentToString()}")
                            totalDataWrite += numberOfDataRead
                        }
                    }

                    Log.v(LOG_TAG, "Total data write: $totalDataWrite")
                    Log.v(LOG_TAG, "Actual audio size: ${audioBytes.size}")

                    val inputAudioConfig: InputAudioConfig = InputAudioConfig.newBuilder()
                        .setAudioEncoding(AudioEncoding.AUDIO_ENCODING_LINEAR_16)
                        .setSampleRateHertz(SAMPLE_RATE)
                        .build()

                    val audioInput: AudioInput = AudioInput.newBuilder()
                        .setConfig(inputAudioConfig)
                        .setAudio(ByteString.copyFrom(audioBytes,0,audioBytes.size))
                        .build()

                    val queryInput: QueryInput = QueryInput.newBuilder()
                        .setAudio(audioInput)
                        .setLanguageCode("en-US")
                        .build()

                    val request: DetectIntentRequest = DetectIntentRequest.newBuilder()
                        .setSession(sessionName)
                        .setQueryInput(queryInput)
                        .build()

                    val response: DetectIntentResponse = stub.detectIntent(request)

                    runOnUiThread {
                        updateUI(response)
                    }
                }
            }

            try {
                inputStream.close()
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Exception while closing input stream")
                e.printStackTrace()
            }
        }
    }

    private fun updateUI(response: DetectIntentResponse) {
        val botReply = response.queryResult.responseMessagesList.size
        binding.audioHintView.text = botReply.toString()
    }

    private fun setupBot() {
        try {
            val details = applicationContext.resources.openRawResource(R.raw.detail)
                .bufferedReader()
                .use { it.readText() }

            val detailsString = JSONObject(details)
            val projectId = detailsString.getString("projectId")
            val locationId = detailsString.getString("locationId")
            val agentId = detailsString.getString("agentId")
            val sessionNameComponent = listOf(
                "projects", projectId,
                "locations", locationId,
                "agents", agentId,
                "sessions", sessionId)
            sessionName = sessionNameComponent.joinToString(separator = "/")

            val stream = this.resources.openRawResource(R.raw.credential)
            val googleCredentials: GoogleCredentials = GoogleCredentials.fromStream(stream)
                .createScoped("https://www.googleapis.com/auth/dialogflow")
            val credentials = CompositeChannelCredentials.create(
                TlsChannelCredentials.create(),
                MoreCallCredentials.from(googleCredentials)
            )
            val channel = Grpc.newChannelBuilder(
                "dialogflow.googleapis.com:443",
                credentials
            ).build()
            stub = SessionsGrpcKt.SessionsCoroutineStub(channel)

            Log.d(LOG_TAG, "setupBot: Setup successful!")
        }catch (e: Exception) {
            Log.d(LOG_TAG, "setupBot: " + e.message)
        }
    }
}
