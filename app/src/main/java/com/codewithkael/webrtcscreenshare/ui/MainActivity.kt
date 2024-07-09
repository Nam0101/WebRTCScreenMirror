package com.codewithkael.webrtcscreenshare.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.codewithkael.webrtcscreenshare.databinding.ActivityMainBinding
import com.codewithkael.webrtcscreenshare.repository.MainRepository
import com.codewithkael.webrtcscreenshare.service.WebrtcService
import com.codewithkael.webrtcscreenshare.service.WebrtcServiceRepository
import dagger.hilt.android.AndroidEntryPoint
import org.webrtc.MediaStream
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), MainRepository.Listener {

    private var username:String?=null
    private lateinit var views: ActivityMainBinding
    private var isRecording = false
    private lateinit var recordingThread: Thread
    @Inject lateinit var webrtcServiceRepository: WebrtcServiceRepository
    private val capturePermissionRequestCode = 1
    private lateinit var audioRecord: AudioRecord
    private lateinit var audioManager: AudioManager
    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        views= ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)
        init()
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1000)
        }
    }

    private fun init(){
        username = intent.getStringExtra("username")
        if (username.isNullOrEmpty()){
            finish()
        }
        WebrtcService.surfaceView = views.surfaceView
        WebrtcService.listener = this
        webrtcServiceRepository.startIntent(username!!)
        views.requestBtn.setOnClickListener {
            startScreenCapture()
            startAudioCapture()
            views.requestLayout.isVisible = false
        }

    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != capturePermissionRequestCode) return
        WebrtcService.screenPermissionIntent = data
        webrtcServiceRepository.requestConnection(
            views.targetEt.text.toString()
        )
    }
    private fun startAudioCapture() {
        val audioSource = MediaRecorder.AudioSource.MIC
        val sampleRateInHz = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSizeInBytes =
            AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            return
        }
        audioRecord =
            AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes)
        audioRecord.startRecording()

        isRecording = true
        recordingThread = Thread({
            val audioData = ByteArray(bufferSizeInBytes)
            while (isRecording) {
                val readSize = audioRecord.read(audioData, 0, audioData.size)
                if (readSize < 0) {
                    return@Thread
                }
                Log.i("AudioRecorder", "Audio data size: $readSize")
            }
        }, "AudioRecorder Thread")
        recordingThread.start()
    }

    private fun stopAudioCapture() {
        isRecording = false
        if (::audioRecord.isInitialized) {
            audioRecord.stop()
            audioRecord.release()
        }
        if (::recordingThread.isInitialized && recordingThread.isAlive) {
            recordingThread.join()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioCapture()
    }
    private fun startScreenCapture(){
        val mediaProjectionManager = application.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(), capturePermissionRequestCode
        )
    }

    override fun onConnectionRequestReceived(target: String) {
        runOnUiThread{
            views.apply {
                notificationTitle.text = "$target is requesting for connection"
                notificationLayout.isVisible = true
                notificationAcceptBtn.setOnClickListener {
                    webrtcServiceRepository.acceptCAll(target)
                    notificationLayout.isVisible = false
                }
                notificationDeclineBtn.setOnClickListener {
                    notificationLayout.isVisible = false
                }
            }
        }
    }

    override fun onConnectionConnected() {
        runOnUiThread {
            views.apply {
                requestLayout.isVisible = false
                disconnectBtn.isVisible = true
                disconnectBtn.setOnClickListener {
                    webrtcServiceRepository.endCallIntent()
                    restartUi()
                }
            }
        }
    }

    override fun onCallEndReceived() {
        runOnUiThread {
            restartUi()
        }
    }

    override fun onRemoteStreamAdded(stream: MediaStream) {
        runOnUiThread {
            views.surfaceView.isVisible = true
            stream.videoTracks[0].addSink(views.surfaceView)

            if (stream.audioTracks.isNotEmpty()) {
                val audioTrack = stream.audioTracks[0]
                audioTrack.setEnabled(true)

                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
            }
            else {
                Log.d("TAG", "onRemoteStreamAdded: No audio track found")
            }
        }
    }

    private fun restartUi(){
        views.apply {
            disconnectBtn.isVisible=false
            requestLayout.isVisible = true
            notificationLayout.isVisible = false
            surfaceView.isVisible = false
        }
    }

}