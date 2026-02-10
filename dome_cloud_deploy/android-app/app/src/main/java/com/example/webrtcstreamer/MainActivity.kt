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
        // Production cloud deployment URL
        val signalingServerUrl = "wss://webrtc-signaling-88sp.onrender.com"
        
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
        binding.startButton.setOnClickListener {
            if (!isStreaming) {
                startStreaming()
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
    
    private fun startStreaming() {
        val serverUrl = binding.serverUrlInput.text.toString().ifEmpty { "ws://10.0.2.2:3000" }
        
        lifecycleScope.launch {
            try {
                signalingClient.connect(serverUrl)
                isStreaming = true
                binding.startButton.text = "Stop Streaming"
                binding.serverUrlInput.isEnabled = false
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
