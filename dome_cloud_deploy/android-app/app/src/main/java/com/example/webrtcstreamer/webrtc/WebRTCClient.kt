package com.example.webrtcstreamer.webrtc

import android.app.Application
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRTCClient(
    private val application: Application,
    private val observer: PeerConnectionObserver
) {
    
    val eglBase: EglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    
    var onIceCandidate: ((String) -> Unit)? = null
    
    init {
        initPeerConnectionFactory(application)
        peerConnectionFactory = createPeerConnectionFactory()
    }
    
    private fun initPeerConnectionFactory(context: Context) {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }
    
    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    true,
                    true
                )
            )
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
    }
    
    fun startLocalVideoCapture(localView: SurfaceViewRenderer) {
        try {
            println("DEBUG: Starting video capture...")
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                Thread.currentThread().name,
                eglBase.eglBaseContext
            )
            
            println("DEBUG: Creating camera capturer...")
            videoCapturer = createCameraCapturer()
            println("DEBUG: Camera capturer created: $videoCapturer")
            
            val videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
            println("DEBUG: Video source created")
            
            videoCapturer!!.initialize(
                surfaceTextureHelper,
                application,
                videoSource.capturerObserver
            )
            println("DEBUG: Camera initialized")
            
            videoCapturer!!.startCapture(640, 480, 24)
            println("DEBUG: Camera capture started at 640x480@24fps")
            
            localVideoTrack = peerConnectionFactory.createVideoTrack("local_video_track", videoSource)
            localVideoTrack?.addSink(localView)
            println("DEBUG: Video track created and sink added")
            
            // Create audio track
            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio_track", audioSource)
            println("DEBUG: Audio track created")
            
            observer.onStreamReady()
        } catch (e: Exception) {
            println("ERROR: Failed to start video capture: ${e.message}")
            e.printStackTrace()
            observer.onStreamError("Camera error: ${e.message}")
        }
    }
    
    private fun createCameraCapturer(): CameraVideoCapturer {
        val enumerator = Camera2Enumerator(application)
        
        val deviceNames = enumerator.deviceNames
        println("DEBUG: Found ${deviceNames.size} cameras: ${deviceNames.joinToString()}")
        
        // Try BACK camera first (usually more reliable)
        for (deviceName in deviceNames) {
            println("DEBUG: Checking camera: $deviceName, isBack=${enumerator.isBackFacing(deviceName)}")
            if (enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    println("DEBUG: ✅ Using back camera: $deviceName")
                    return capturer
                }
            }
        }
        
        // If no back camera, try front camera
        for (deviceName in deviceNames) {
            println("DEBUG: Checking camera: $deviceName, isFront=${enumerator.isFrontFacing(deviceName)}")
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    println("DEBUG: ✅ Using front camera: $deviceName")
                    return capturer
                }
            }
        }
        
        throw RuntimeException("No camera found on device")
    }
    
    fun createPeerConnection(): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer()
            )
        ).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }
        
        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        val candidateJson = """
                            {
                                "candidate": "${it.sdp}",
                                "sdpMid": "${it.sdpMid}",
                                "sdpMLineIndex": ${it.sdpMLineIndex}
                            }
                        """.trimIndent()
                        onIceCandidate?.invoke(candidateJson)
                    }
                }
                
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    println("DEBUG: ICE connection receiving change: $receiving")
                }
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    println("DEBUG: ICE connection state changed to: $newState")
                }
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                    println("DEBUG: ICE gathering state changed to: $newState")
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                    println("DEBUG: Signaling state changed to: $newState")
                }
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onRenegotiationNeeded() {
                    println("DEBUG: Renegotiation needed")
                }
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
        
        // Add local tracks to peer connection
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("local_stream")) }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("local_stream")) }
        
        return peerConnection
    }
    
    suspend fun createOffer(): String {
        // Close existing peer connection if any
        peerConnection?.close()
        
        // Create new peer connection
        val pc = createPeerConnection() ?: throw RuntimeException("Failed to create peer connection")
        
        println("DEBUG: Created peer connection, tracks added: video=${localVideoTrack != null}, audio=${localAudioTrack != null}")
        
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        
        return suspendCancellableCoroutine { continuation ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    println("DEBUG: Offer created successfully")
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            println("DEBUG: Local description set successfully")
                            sdp?.let {
                                // Return JSON with type and sdp
                                val jsonOffer = """{"type":"${it.type.canonicalForm()}","sdp":"${it.description}"}"""
                                println("DEBUG: Sending offer (length: ${jsonOffer.length})")
                                continuation.resume(jsonOffer)
                            }
                        }
                        override fun onSetFailure(error: String?) {
                            println("ERROR: Failed to set local description: $error")
                            continuation.resumeWithException(RuntimeException(error))
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sdp)
                }
                
                override fun onCreateFailure(error: String?) {
                    println("ERROR: Failed to create offer: $error")
                    continuation.resumeWithException(RuntimeException(error))
                }
                
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }
    
    suspend fun setRemoteAnswer(answerJson: String) {
        println("DEBUG: setRemoteAnswer called with: ${answerJson.take(100)}")
        // Parse answer JSON to extract SDP
        val answerSdp = try {
            val json = org.json.JSONObject(answerJson)
            json.getString("sdp")
        } catch (e: Exception) {
            println("DEBUG: Failed to parse as JSON, using raw string")
            // Fallback: if it's already just the SDP string
            answerJson
        }
        
        println("DEBUG: Setting remote answer SDP (length: ${answerSdp.length})")
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        
        return suspendCancellableCoroutine { continuation ->
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    println("DEBUG: setRemoteAnswer SUCCESS!")
                    continuation.resume(Unit)
                }
                
                override fun onSetFailure(error: String?) {
                    println("ERROR: setRemoteAnswer FAILED: $error")
                    continuation.resumeWithException(RuntimeException(error))
                }
                
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, sdp)
        }
    }
    
    suspend fun addRemoteIceCandidate(candidateJson: String) {
        try {
            // Parse the ICE candidate JSON
            val json = org.json.JSONObject(candidateJson)
            val sdp = json.getString("candidate")
            val sdpMid = json.getString("sdpMid")
            val sdpMLineIndex = json.getInt("sdpMLineIndex")
            
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
            peerConnection?.addIceCandidate(iceCandidate)
        } catch (e: Exception) {
            observer.onStreamError("Failed to add ICE candidate: ${e.message}")
        }
    }
    
    fun close() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        peerConnection?.close()
        peerConnectionFactory.dispose()
        eglBase.release()
    }
    
    open class PeerConnectionObserver {
        open fun onStreamReady() {}
        open fun onStreamError(error: String) {}
    }
}
