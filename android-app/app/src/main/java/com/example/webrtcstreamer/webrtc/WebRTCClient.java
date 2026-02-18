package com.example.webrtcstreamer.webrtc;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WebRTCClient {
    private static final String TAG = "WebRTCClient";

    private final Application application;
    private final PeerConnectionObserver observer;

    public final EglBase eglBase;
    private final PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private CameraVideoCapturer videoCapturer;

    public interface OnIceCandidateListener {
        void onIceCandidate(String candidateJson);
    }

    public OnIceCandidateListener onIceCandidate;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static class PeerConnectionObserver {
        public void onStreamReady() {}
        public void onStreamError(String error) {}
    }

    public WebRTCClient(Application application, PeerConnectionObserver observer) {
        this.application = application;
        this.observer = observer;
        this.eglBase = EglBase.create();
        
        initPeerConnectionFactory(application);
        this.peerConnectionFactory = createPeerConnectionFactory();
    }

    private void initPeerConnectionFactory(Context context) {
        PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(options);
    }

    private PeerConnectionFactory createPeerConnectionFactory() {
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableEncryption = false;
        options.disableNetworkMonitor = false;

        return PeerConnectionFactory.builder()
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setOptions(options)
                .createPeerConnectionFactory();
    }

    public void startLocalVideoCapture(SurfaceViewRenderer localView) {
        try {
            Log.d(TAG, "Starting video capture...");
            SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create(
                    Thread.currentThread().getName(),
                    eglBase.getEglBaseContext()
            );

            Log.d(TAG, "Creating camera capturer...");
            videoCapturer = createCameraCapturer();
            Log.d(TAG, "Camera capturer created: " + videoCapturer);

            VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
            Log.d(TAG, "Video source created");

            videoCapturer.initialize(surfaceTextureHelper, application, videoSource.getCapturerObserver());
            Log.d(TAG, "Camera initialized");

            videoCapturer.startCapture(640, 480, 24);
            Log.d(TAG, "Camera capture started at 640x480@24fps");

            localVideoTrack = peerConnectionFactory.createVideoTrack("local_video_track", videoSource);
            localVideoTrack.addSink(localView);
            Log.d(TAG, "Video track created and sink added");

            // Create audio track
            AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
            localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio_track", audioSource);
            Log.d(TAG, "Audio track created");

            observer.onStreamReady();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start video capture", e);
            observer.onStreamError("Camera error: " + e.getMessage());
        }
    }

    private CameraVideoCapturer createCameraCapturer() {
        Camera2Enumerator enumerator = new Camera2Enumerator(application);
        String[] deviceNames = enumerator.getDeviceNames();
        
        Log.d(TAG, "Found " + deviceNames.length + " cameras");

        // Try BACK camera first
        for (String deviceName : deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    Log.d(TAG, "Using back camera: " + deviceName);
                    return capturer;
                }
            }
        }

        // Try FRONT camera
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer capturer = enumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    Log.d(TAG, "Using front camera: " + deviceName);
                    return capturer;
                }
            }
        }

        throw new RuntimeException("No camera found on device");
    }

    public PeerConnection createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turn:13.234.129.181:3478")
                .setUsername("dome")
                .setPassword("domepass123")
                .createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                if (candidate != null && onIceCandidate != null) {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("candidate", candidate.sdp);
                        json.put("sdpMid", candidate.sdpMid);
                        json.put("sdpMLineIndex", candidate.sdpMLineIndex);
                        onIceCandidate.onIceCandidate(json.toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {}

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "ICE connection state changed to: " + iceConnectionState);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "ICE gathering state changed to: " + iceGatheringState);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {}

            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {}

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

            @Override
            public void onRemoveStream(MediaStream mediaStream) {}

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {}
        });

        if (localVideoTrack != null) {
            peerConnection.addTrack(localVideoTrack, Collections.singletonList("local_stream"));
        }
        if (localAudioTrack != null) {
            peerConnection.addTrack(localAudioTrack, Collections.singletonList("local_stream"));
        }

        return peerConnection;
    }

    public void createOffer(Callback<String> callback) {
        if (peerConnection != null) {
            peerConnection.close();
        }
        peerConnection = createPeerConnection(); // Returns null? No, implementation above returns object.

        if (peerConnection == null) {
            callback.onError("Failed to create peer connection");
            return;
        }

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "Offer created successfully");
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {}

                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Local description set successfully");
                        try {
                            JSONObject json = new JSONObject();
                            json.put("type", sdp.type.canonicalForm());
                            json.put("sdp", sdp.description);
                            callback.onSuccess(json.toString());
                        } catch (JSONException e) {
                            callback.onError(e.getMessage());
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {}

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "Failed to set local description: " + s);
                        callback.onError(s);
                    }
                }, sdp);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "Failed to create offer: " + s);
                callback.onError(s);
            }

            @Override
            public void onSetFailure(String s) {}
        }, constraints);
    }

    public void setRemoteAnswer(String answerJson, Callback<Void> callback) {
        String answerSdp = answerJson;
        try {
            JSONObject json = new JSONObject(answerJson);
            answerSdp = json.getString("sdp");
        } catch (JSONException e) {
            // fallback to raw string
        }

        SessionDescription sdp = new SessionDescription(SessionDescription.Type.ANSWER, answerSdp);
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {}

            @Override
            public void onSetSuccess() {
                Log.d(TAG, "setRemoteAnswer SUCCESS");
                callback.onSuccess(null);
            }

            @Override
            public void onCreateFailure(String s) {}

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "setRemoteAnswer FAILED: " + s);
                callback.onError(s);
            }
        }, sdp);
    }

    public void addRemoteIceCandidate(String candidateJson) {
        try {
            JSONObject json = new JSONObject(candidateJson);
            String sdp = json.getString("candidate");
            String sdpMid = json.getString("sdpMid");
            int sdpMLineIndex = json.getInt("sdpMLineIndex");

            IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
            if (peerConnection != null) {
                peerConnection.addIceCandidate(iceCandidate);
            }
        } catch (JSONException e) {
            observer.onStreamError("Failed to add ICE candidate: " + e.getMessage());
        }
    }

    public void close() {
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            videoCapturer.dispose();
            videoCapturer = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            // peerConnectionFactory = null; // final field
        }
        if (eglBase != null) {
            eglBase.release();
        }
    }
}
