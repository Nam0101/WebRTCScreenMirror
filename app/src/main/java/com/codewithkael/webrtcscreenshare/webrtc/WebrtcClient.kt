package com.codewithkael.webrtcscreenshare.webrtc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.codewithkael.webrtcscreenshare.utils.DataModel
import com.codewithkael.webrtcscreenshare.utils.DataModelType
import com.google.gson.Gson
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.Observer
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import javax.inject.Inject

class WebrtcClient @Inject constructor(
    private val context: Context, private val gson: Gson
) {

    private lateinit var username: String
    private lateinit var observer: Observer
    private lateinit var localSurfaceView: SurfaceViewRenderer
    var listener: Listener? = null
    private var permissionIntent:Intent?=null

    private var peerConnection: PeerConnection? = null
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val peerConnectionFactory by lazy { createPeerConnectionFactory() }
    private val mediaConstraint = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    }
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private lateinit var audioDeviceModule: AudioDeviceModule
    private var isRecording = false
    private lateinit var recordingThread: Thread
    private val iceServer = listOf(
        PeerConnection.IceServer(
            "turn:openrelay.metered.ca:443?transport=udp", "openrelayproject", "openrelayproject"
        )
    )

    private var screenCapturer : VideoCapturer?=null
    private val localVideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val localTrackId="local_track"
    private val localStreamId="local_stream"
    private val audioTrackId="audio_track"
    private var localVideoTrack:VideoTrack?=null
    private var localStream: MediaStream?=null


    init {
        initPeerConnectionFactory(context)
    }

    fun initializeWebrtcClient(
        username: String, view: SurfaceViewRenderer, observer: Observer
    ) {
        this.username = username
        this.observer = observer
        peerConnection = createPeerConnection(observer)
        initSurfaceView(view)
    }

    fun setPermissionIntent(intent: Intent) {
        this.permissionIntent = intent
    }

    private fun initSurfaceView(view: SurfaceViewRenderer){
        this.localSurfaceView = view
        view.run {
            setMirror(false)
            setEnableHardwareScaler(true)
            init(eglBaseContext,null)
        }
    }

    fun startScreenCapturing(view:SurfaceViewRenderer){
        val displayMetrics = DisplayMetrics()
        val windowsManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowsManager.defaultDisplay.getMetrics(displayMetrics)

        val screenWidthPixels = displayMetrics.widthPixels
        val screenHeightPixels = displayMetrics.heightPixels

        val surfaceTextureHelper = SurfaceTextureHelper.create(
            Thread.currentThread().name,eglBaseContext
        )

        screenCapturer = createScreenCapturer()
        screenCapturer!!.initialize(surfaceTextureHelper,context,localVideoSource.capturerObserver)
        screenCapturer!!.startCapture(screenWidthPixels,screenHeightPixels,15)

        localVideoTrack = peerConnectionFactory.createVideoTrack(localTrackId+"_video",localVideoSource)
        Log.d("TAG", "startScreenCapturing: $localVideoTrack")
        localVideoTrack?.addSink(view)
        localStream = peerConnectionFactory.createLocalMediaStream(localStreamId)
        localStream?.addTrack(localVideoTrack)

        startAudioRecording()

        peerConnection?.addStream(localStream)

    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecording() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION // Sử dụng chế độ thoại

        val minBufferSize = AudioRecord.getMinBufferSize(
            44100,  // Tần số lấy mẫu
            AudioFormat.CHANNEL_IN_MONO,  // Kênh âm thanh
            AudioFormat.ENCODING_PCM_16BIT // Độ sâu bit
        )
        val audioDeviceModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule() as JavaAudioDeviceModule
        val audioTrackJni = audioDeviceModule.nativeAudioDeviceModulePointer
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize
        )
        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack(audioTrackId, audioSource)

        isRecording = true
        recordingThread = Thread {
            val buffer = ByteBuffer.allocateDirect(minBufferSize)
            audioRecord.startRecording()
            while (isRecording) {
                val bytesRead = audioRecord.read(buffer, minBufferSize)
                if (bytesRead > 0) {
                    buffer.rewind()
                    val bytes = ByteArray(bytesRead)
                    buffer.get(bytes)
                    localAudioTrack?.setEnabled(true)
                    localAudioTrack?.setVolume(1.0)
                    Log.i("AudioRecorder", "Đã ghi âm: $bytesRead")
                }
            }
            audioRecord.stop()
        }
        localStream?.addTrack(localAudioTrack)

        recordingThread.start()
    }


    private fun createScreenCapturer(): VideoCapturer {
        return ScreenCapturerAndroid(permissionIntent, object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d("TAG", "onStop: stopped screen casting permission")
            }
        })
    }

    private fun initPeerConnectionFactory(application: Context) {
        val options = PeerConnectionFactory.InitializationOptions.builder(application)
            .setEnableInternalTracer(true).setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder().setVideoDecoderFactory(
            DefaultVideoDecoderFactory(eglBaseContext)
        ).setVideoEncoderFactory(
            DefaultVideoEncoderFactory(
                eglBaseContext, true, true
            )
        ).setOptions(PeerConnectionFactory.Options().apply {
            disableEncryption = false
            disableNetworkMonitor = false
        }).createPeerConnectionFactory()
    }

    private fun createPeerConnection(observer: Observer): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(
            iceServer, observer
        )
    }

    fun call(target: String) {
        peerConnection?.createOffer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        listener?.onTransferEventToSocket(
                            DataModel(
                                type = DataModelType.Offer, username, target, desc?.description
                            )
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    fun answer(target: String) {
        peerConnection?.createAnswer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        listener?.onTransferEventToSocket(
                            DataModel(
                                type = DataModelType.Answer,
                                username = username,
                                target = target,
                                data = desc?.description
                            )
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    fun onRemoteSessionReceived(sessionDescription: SessionDescription){
        peerConnection?.setRemoteDescription(MySdpObserver(),sessionDescription)
    }

    fun addIceCandidate(iceCandidate: IceCandidate){
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun sendIceCandidate(candidate: IceCandidate,target: String){
        addIceCandidate(candidate)
        listener?.onTransferEventToSocket(
            DataModel(
                type = DataModelType.IceCandidates,
                username = username,
                target = target,
                data = gson.toJson(candidate)
            )
        )
    }

    fun closeConnection(){
        try {
            isRecording = false
            recordingThread.interrupt()
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            localStream?.dispose()
            peerConnection?.close()
        }catch (e:Exception){
            e.printStackTrace()
        }
    }

    fun restart(){
        closeConnection()
        localSurfaceView.let {
            it.clearImage()
            it.release()
            initializeWebrtcClient(username,it,observer)
        }
    }




    interface Listener {
        fun onTransferEventToSocket(data: DataModel)
    }
}