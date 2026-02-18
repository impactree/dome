package com.dome.streamer.webrtc;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class SignalingClient {
    private static final String TAG = "SignalingClient";
    
    private final String serverUrl;
    private final Listener listener;
    private WebSocket webSocket;
    private final OkHttpClient client;
    private final Gson gson = new Gson();
    
    private String clientId;
    private String streamId;

    public interface Listener {
        void onConnected(String clientId);
        void onStreamRegistered(String streamId, String embedUrl);
        void onViewerJoined(String viewerId);
        void onAnswer(String answer, String senderId);
        void onIceCandidate(String candidate, String senderId);
        void onError(String error);
    }

    public SignalingClient(String serverUrl, Listener listener) {
        this.serverUrl = serverUrl;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public void setStreamId(String id) {
        this.streamId = id;
    }

    public void connect() {
        connect(this.serverUrl);
    }

    public void connect(String url) {
        Request request = new Request.Builder()
                .url(url)
                .build();

        Log.d(TAG, "Attempting to connect to " + url);
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.d(TAG, "WebSocket connected successfully");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d(TAG, "Received message: " + text);
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure", t);
                listener.onError("Connection failed: " + t.getMessage());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.d(TAG, "WebSocket closing code: " + code + " reason: " + reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.d(TAG, "WebSocket closed code: " + code + " reason: " + reason);
            }
        });
    }

    private void handleMessage(String message) {
        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);
            String type = json.has("type") ? json.get("type").getAsString() : null;

            if (type == null) return;

            switch (type) {
                case "connected":
                    clientId = json.has("clientId") ? json.get("clientId").getAsString() : null;
                    if (clientId != null) {
                        listener.onConnected(clientId);
                        registerAsStreamer();
                    }
                    break;

                case "registered":
                    streamId = json.has("streamId") ? json.get("streamId").getAsString() : null;
                    String embedUrl = json.has("embedUrl") ? json.get("embedUrl").getAsString() : null;
                    if (streamId != null && embedUrl != null) {
                        listener.onStreamRegistered(streamId, embedUrl);
                    }
                    break;

                case "viewer-joined":
                    String viewerId = json.has("viewerId") ? json.get("viewerId").getAsString() : null;
                    if (viewerId != null) {
                        listener.onViewerJoined(viewerId);
                    }
                    break;

                case "answer":
                    String answer = null;
                    try {
                        if (json.has("answer")) {
                            if (json.get("answer").isJsonObject()) {
                                answer = json.get("answer").getAsJsonObject().toString();
                            } else {
                                answer = json.get("answer").getAsString();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing answer", e);
                    }
                    String senderId = json.has("senderId") ? json.get("senderId").getAsString() : null;
                    if (answer != null && senderId != null) {
                        listener.onAnswer(answer, senderId);
                    }
                    break;

                case "ice-candidate":
                    String candidate = json.has("candidate") ? json.get("candidate").toString() : null; // Keep as string or object depending on usage
                    // Actually, getting as String from JsonElement might give quoted string if it's a string, or json repr if object
                    // Looking at Kotlin code: json.get("candidate")?.toString()
                    // If candidate is a JSON object in the message, toString() returns the JSON string.
                    String candidateSenderId = json.has("senderId") ? json.get("senderId").getAsString() : null;
                    if (candidate != null && candidateSenderId != null) {
                        listener.onIceCandidate(candidate, candidateSenderId);
                    }
                    break;

                case "error":
                    String errorMsg = json.has("message") ? json.get("message").getAsString() : "Unknown error";
                    listener.onError(errorMsg);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse message", e);
            listener.onError("Failed to parse message: " + e.getMessage());
        }
    }

    private void registerAsStreamer() {
        JsonObject message = new JsonObject();
        message.addProperty("type", "register-streamer");
        if (streamId != null) {
            message.addProperty("streamId", streamId);
        }
        send(message.toString());
    }

    public void sendOffer(String offer, String targetId) {
        // offer is already a JSON string
        JsonObject offerJson = gson.fromJson(offer, JsonObject.class);
        JsonObject message = new JsonObject();
        message.addProperty("type", "offer");
        message.add("offer", offerJson);
        message.addProperty("targetId", targetId);
        send(message.toString());
    }

    public void sendAnswer(String answer, String targetId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "answer");
        message.addProperty("answer", answer);
        message.addProperty("targetId", targetId);
        send(message.toString());
    }

    public void sendIceCandidate(String candidate, String targetId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "ice-candidate");
        // candidate might be a JSON object string, we want to add it as a property or nested object?
        // In Kotlin: addProperty("candidate", candidate) where candidate was a string.
        // But in WebRTCClient.kt it constructs a JSON string: val candidateJson = """ ... """
        // So passing that string as property value is correct if we want it to be a string in the JSON payload
        // OR if it's a JSON structure we might want to add(..., jsonElement).
        // Let's stick to adding as property if uncertain, or parse and add.
        // The server relays it. If receiver expects JSON object, we should probably send it as one or string.
        // Kotlin: addProperty("candidate", candidate) -> adds as String.
        message.addProperty("candidate", candidate); 
        message.addProperty("targetId", targetId);
        send(message.toString());
    }

    private void send(String message) {
        if (webSocket != null) {
            webSocket.send(message);
        }
    }

    public void disconnect() {
        if (streamId != null) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "stop-stream");
            send(message.toString());
        }
        if (webSocket != null) {
            webSocket.close(1000, "Closing connection");
            webSocket = null;
        }
    }
}
