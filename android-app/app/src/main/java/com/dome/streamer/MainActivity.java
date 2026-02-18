package com.dome.streamer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.dome.streamer.databinding.ActivityMainBinding;
import com.dome.streamer.webrtc.SignalingClient;
import com.dome.streamer.webrtc.WebRTCClient;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

    private ActivityMainBinding binding;
    private WebRTCClient webRTCClient;
    private SignalingClient signalingClient;

    private boolean isStreaming = false;
    private String currentViewerId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupUI();

        if (checkPermissions()) {
            initializeWebRTC();
        } else {
            requestPermissions();
        }
    }

    private boolean checkPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                initializeWebRTC();
            } else {
                Toast.makeText(this, "Permissions required for streaming", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void initializeWebRTC() {
        webRTCClient = new WebRTCClient(getApplication(), new WebRTCClient.PeerConnectionObserver() {
            @Override
            public void onStreamReady() {
                runOnUiThread(() -> updateStatus("Stream ready"));
            }

            @Override
            public void onStreamError(String error) {
                runOnUiThread(() -> {
                    updateStatus("Error: " + error);
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                });
            }
        });

        // Initialize surface view
        binding.localView.init(webRTCClient.eglBase.getEglBaseContext(), null);
        binding.localView.setMirror(true);
        binding.localView.setEnableHardwareScaler(true);
        binding.localView.setZOrderMediaOverlay(false);

        // Start local preview
        binding.localView.post(() -> {
            try {
                Log.d("MainActivity", "Starting local video capture...");
                webRTCClient.startLocalVideoCapture(binding.localView);
                Log.d("MainActivity", "Local video capture started successfully");
                
                // AUTO-START
                if (!isStreaming) {
                    Log.d("MainActivity", "Auto-starting stream...");
                    SharedPreferences prefs = getSharedPreferences("start_settings", MODE_PRIVATE);
                    String serverUrl = prefs.getString("server_url", "ws://20.244.82.40:3004");
                    if (serverUrl == null) serverUrl = "ws://20.244.82.40:3004";

                    String streamId = prefs.getString("stream_id", null);
                    if (streamId == null) {
                        int randomNum = new Random().nextInt(9000) + 1000;
                        streamId = "cam-" + randomNum;
                        prefs.edit().putString("stream_id", streamId).apply();
                    }

                    startStreaming(serverUrl, streamId);
                }

            } catch (Exception e) {
                Log.e("MainActivity", "Failed to start camera", e);
                runOnUiThread(() -> {
                    updateStatus("CAMERA ERROR: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Camera failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });

        webRTCClient.onIceCandidate = candidate -> {
            if (currentViewerId != null) {
                Log.d("MainActivity", "Sending ICE candidate to viewer: " + currentViewerId);
                signalingClient.sendIceCandidate(candidate, currentViewerId);
            } else {
                Log.e("MainActivity", "No viewer ID set, cannot send ICE candidate");
            }
        };
    }

    private void setupUI() {
        SharedPreferences prefs = getSharedPreferences("start_settings", MODE_PRIVATE);
        String savedServerUrl = prefs.getString("server_url", "ws://20.244.82.40:3004");
        binding.serverUrlInput.setText(savedServerUrl);

        String savedStreamId = prefs.getString("stream_id", null);
        if (savedStreamId == null) {
            int randomNum = new Random().nextInt(9000) + 1000;
            savedStreamId = "cam-" + randomNum;
            prefs.edit().putString("stream_id", savedStreamId).apply();
        }
        binding.streamIdInput.setText(savedStreamId);

        binding.startButton.setOnClickListener(v -> {
            if (!isStreaming) {
                String serverUrl = binding.serverUrlInput.getText().toString().trim();
                String streamId = binding.streamIdInput.getText().toString().trim();

                if (serverUrl.isEmpty()) {
                    Toast.makeText(this, "Please enter Server URL", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!streamId.isEmpty()) {
                    prefs.edit()
                            .putString("server_url", serverUrl)
                            .putString("stream_id", streamId)
                            .apply();
                    startStreaming(serverUrl, streamId);
                } else {
                    Toast.makeText(this, "Please enter a Camera ID", Toast.LENGTH_SHORT).show();
                }
            } else {
                stopStreaming();
            }
        });

        binding.copyUrlButton.setOnClickListener(v -> {
            String embedUrl = binding.embedUrlText.getText().toString();
            if (!embedUrl.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Embed URL", embedUrl);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        updateStatus("Ready");
    }

    private void startStreaming(String serverUrl, String streamId) {
        signalingClient = new SignalingClient(serverUrl, new SignalingClient.Listener() {
            @Override
            public void onConnected(String clientId) {
                runOnUiThread(() -> updateStatus("Connected: " + clientId));
            }

            @Override
            public void onStreamRegistered(String streamId, String embedUrl) {
                runOnUiThread(() -> {
                    updateStatus("Streaming");
                    binding.streamIdText.setText("Stream ID: " + streamId);
                    binding.embedUrlText.setText("Embed URL: " + embedUrl);
                });
            }

            @Override
            public void onViewerJoined(String viewerId) {
                currentViewerId = viewerId;
                webRTCClient.createOffer(new WebRTCClient.Callback<String>() {
                    @Override
                    public void onSuccess(String offer) {
                        signalingClient.sendOffer(offer, viewerId);
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Offer error: " + error, Toast.LENGTH_SHORT).show());
                    }
                });
            }

            @Override
            public void onAnswer(String answer, String senderId) {
                webRTCClient.setRemoteAnswer(answer, new WebRTCClient.Callback<Void>() {
                    @Override
                    public void onSuccess(Void result) {}

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Answer error: " + error, Toast.LENGTH_SHORT).show());
                    }
                });
            }

            @Override
            public void onIceCandidate(String candidate, String senderId) {
                webRTCClient.addRemoteIceCandidate(candidate);
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    updateStatus("Error: " + error);
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_SHORT).show();
                    
                    // Reset UI
                    isStreaming = false;
                    binding.startButton.setText("Start Streaming");
                    binding.serverUrlInput.setEnabled(true);
                    binding.streamIdInput.setEnabled(true);
                });
            }
        });

        signalingClient.setStreamId(streamId);
        signalingClient.connect();
        
        isStreaming = true;
        binding.startButton.setText("Stop Streaming");
        binding.serverUrlInput.setEnabled(false);
        binding.streamIdInput.setEnabled(false);
    }

    private void stopStreaming() {
        if (signalingClient != null) {
            signalingClient.disconnect();
        }
        isStreaming = false;
        binding.startButton.setText("Start Streaming");
        binding.serverUrlInput.setEnabled(true);
        binding.streamIdInput.setEnabled(true);
        binding.streamIdText.setText("");
        binding.embedUrlText.setText("");
        updateStatus("Ready");
    }

    private void updateStatus(String status) {
        binding.statusText.setText("Status: " + status);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webRTCClient != null) {
            webRTCClient.close();
        }
    }
}
