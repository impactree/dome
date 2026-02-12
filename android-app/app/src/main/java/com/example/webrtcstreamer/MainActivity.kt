package com.example.webrtcstreamer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.webrtcstreamer.databinding.ActivityMainBinding
import com.example.webrtcstreamer.webrtc.WebRTCClient
import com.example.webrtcstreamer.webrtc.SignalingClient
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var signalingClient: SignalingClient
    
    private var isStreaming = false
    private var currentViewerId: String? = null
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        
        if (checkPermissions()) {
            initializeWebRTC()
        } else {
            requestPermissions()
        }
    }
    
    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeWebRTC()
            } else {
                Toast.makeText(this, "Permissions required for streaming", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private fun initializeWebRTC() {
        // Get signaling server URL from EditText or use default
        // For dev container/Codespace: Use port forwarding or public URL
        // For Android Emulator: ws://10.0.2.2:3000
        // For Physical Device: wss://YOUR_PUBLIC_URL
        val signalingServerUrl = "ws://20.244.29.48:3000"  // Azure Public IP
        
        webRTCClient = WebRTCClient(
            application = application,
            observer = object : WebRTCClient.PeerConnectionObserver() {
                override fun onStreamReady() {
                    runOnUiThread {
                        updateStatus("Stream ready")
                    }
                }
                
                override fun onStreamError(error: String) {
                    runOnUiThread {
                        updateStatus("Error: $error")
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        
        signalingClient = SignalingClient(signalingServerUrl, object : SignalingClient.Listener {
            override fun onConnected(clientId: String) {
                runOnUiThread {
                    updateStatus("Connected: $clientId")
                }
            }
            
            override fun onStreamRegistered(streamId: String, embedUrl: String) {
                runOnUiThread {
                    updateStatus("Streaming")
                    binding.streamIdText.text = "Stream ID: $streamId"
                    binding.embedUrlText.text = "Embed URL: $embedUrl"
                }
            }
            
            override fun onViewerJoined(viewerId: String) {
                currentViewerId = viewerId
                lifecycleScope.launch {
                    // Create offer for new viewer
                    val offer = webRTCClient.createOffer()
                    signalingClient.sendOffer(offer, viewerId)
                }
            }
            
            override fun onAnswer(answer: String, senderId: String) {
                lifecycleScope.launch {
                    webRTCClient.setRemoteAnswer(answer)
                }
            }
            
            override fun onIceCandidate(candidate: String, senderId: String) {
                lifecycleScope.launch {
                    webRTCClient.addRemoteIceCandidate(candidate)
                }
            }
            
            override fun onError(error: String) {
                runOnUiThread {
                    updateStatus("Error: $error")
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                    
                    // Reset streaming state on error (e.g., ID already taken)
                    isStreaming = false
                    binding.startButton.text = "Start Streaming"
                    binding.serverUrlInput.isEnabled = true
                    binding.streamIdInput.isEnabled = true
                }
            }
        })
        
        // Initialize surface view
        binding.localView.init(webRTCClient.eglBase.eglBaseContext, null)
        binding.localView.setMirror(true)
        binding.localView.setEnableHardwareScaler(true)
        binding.localView.setZOrderMediaOverlay(false)
        
        // Start local preview after a short delay to ensure view is ready
        binding.localView.post {
            try {
                println("DEBUG: Starting local video capture...")
                webRTCClient.startLocalVideoCapture(binding.localView)
                println("DEBUG: Local video capture started successfully")
                
                // AUTO-START: Start streaming immediately after camera is ready
                if (!isStreaming) {
                    println("DEBUG: Auto-starting stream...")
                    val prefs = getSharedPreferences("start_settings", MODE_PRIVATE)
                    val serverUrl = prefs.getString("server_url", "ws://20.244.29.48:3000") ?: "ws://20.244.29.48:3000"
                    
                    // Random ID if none exists
                    var streamId = prefs.getString("stream_id", null)
                    if (streamId == null) {
                        streamId = "cam-${(1000..9999).random()}"
                        prefs.edit().putString("stream_id", streamId).apply()
                    }
                    
                    startStreaming(serverUrl, streamId)
                }
            } catch (e: Exception) {
                println("ERROR: Failed to start camera: ${e.message}")
                e.printStackTrace()
                runOnUiThread {
                    updateStatus("CAMERA ERROR: ${e.message}")
                    Toast.makeText(this, "Camera failed: ${e.message}\n\nCheck Settings > Apps > Permissions", Toast.LENGTH_LONG).show()
                }
            }
        }
        
        // Set ICE candidate callback
        webRTCClient.onIceCandidate = { candidate ->
            lifecycleScope.launch {
                currentViewerId?.let { viewerId ->
                    println("DEBUG: Sending ICE candidate to viewer: $viewerId")
                    signalingClient.sendIceCandidate(candidate, viewerId)
                } ?: println("ERROR: No viewer ID set, cannot send ICE candidate")
            }
        }
    }
    
    private fun setupUI() {
        // Load settings
        val prefs = getSharedPreferences("start_settings", MODE_PRIVATE)
        
        // Default Server URL
        val savedServerUrl = prefs.getString("server_url", "ws://20.244.29.48:3000")
        binding.serverUrlInput.setText(savedServerUrl)

        // Generate a random unique ID for first-time use
        var savedStreamId = prefs.getString("stream_id", null)
        if (savedStreamId == null) {
            val randomNum = (1000..9999).random()
            savedStreamId = "cam-$randomNum"
            prefs.edit().putString("stream_id", savedStreamId).apply()
        }
        binding.streamIdInput.setText(savedStreamId)

        binding.startButton.setOnClickListener {
            if (!isStreaming) {
                // Save settings
                val serverUrl = binding.serverUrlInput.text.toString().trim()
                val streamId = binding.streamIdInput.text.toString().trim()
                
                if (serverUrl.isEmpty()) {
                    Toast.makeText(this, "Please enter Server URL", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                if (streamId.isNotEmpty()) {
                    prefs.edit()
                        .putString("server_url", serverUrl)
                        .putString("stream_id", streamId)
                        .apply()
                    startStreaming(serverUrl, streamId)
                } else {
                    Toast.makeText(this, "Please enter a Camera ID", Toast.LENGTH_SHORT).show()
                }
            } else {
                stopStreaming()
            }
        }
        
        binding.copyUrlButton.setOnClickListener {
            val embedUrl = binding.embedUrlText.text.toString()
            if (embedUrl.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Embed URL", embedUrl)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        
        updateStatus("Ready")
    }
    
    private fun startStreaming(serverUrl: String, streamId: String) {
        lifecycleScope.launch {
            try {
                signalingClient.setStreamId(streamId)
                signalingClient.connect(serverUrl)
                isStreaming = true
                binding.startButton.text = "Stop Streaming"
                binding.serverUrlInput.isEnabled = false
                binding.streamIdInput.isEnabled = false
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to connect: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun stopStreaming() {
        lifecycleScope.launch {
            signalingClient.disconnect()
            isStreaming = false
            binding.startButton.text = "Start Streaming"
            binding.serverUrlInput.isEnabled = true
            binding.streamIdInput.isEnabled = true
            binding.streamIdText.text = ""
            binding.embedUrlText.text = ""
            updateStatus("Ready")
        }
    }
    
    private fun updateStatus(status: String) {
        binding.statusText.text = "Status: $status"
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Only stop streaming if user explicitly requested it
        // Don't auto-stop when activity is destroyed (e.g., screen rotation)
        webRTCClient.close()
    }
}
