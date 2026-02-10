import React, { useEffect, useRef, useState } from 'react';
import './StreamViewer.css';

const SIGNALING_SERVER = 'ws://20.244.29.48:3000';

const StreamViewer = ({ streamId }) => {
  const videoRef = useRef(null);
  const wsRef = useRef(null);
  const pcRef = useRef(null);
  const isCleanedUpRef = useRef(false);
  const [status, setStatus] = useState('connecting');
  const [error, setError] = useState(null);
  const [clientId, setClientId] = useState(null);
  const [needsManualPlay, setNeedsManualPlay] = useState(false);

  useEffect(() => {
    if (!streamId) {
      setError('No stream ID provided');
      return;
    }

    isCleanedUpRef.current = false;
    initializeWebRTC();

    return () => {
      isCleanedUpRef.current = true;
      cleanup();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [streamId]);

  const initializeWebRTC = () => {
    // Create WebSocket connection
    wsRef.current = new WebSocket(SIGNALING_SERVER);

    wsRef.current.onopen = () => {
      console.log('WebSocket connected');
      setStatus('connected');
    };

    wsRef.current.onmessage = async (event) => {
      const message = JSON.parse(event.data);
      console.log('Received message:', message.type);

      switch (message.type) {
        case 'connected':
          setClientId(message.clientId);
          // Register as viewer
          wsRef.current.send(JSON.stringify({
            type: 'register-viewer',
            streamId: streamId
          }));
          break;

        case 'registered':
          console.log('Registered as viewer');
          setStatus('waiting for stream');
          break;

        case 'offer':
          await handleOffer(message.offer, message.senderId);
          break;

        case 'ice-candidate':
          if (message.candidate && pcRef.current) {
            try {
              // Parse candidate if it's a string
              const candidateData = typeof message.candidate === 'string'
                ? JSON.parse(message.candidate)
                : message.candidate;
              console.log('Adding ICE candidate:', candidateData);
              await pcRef.current.addIceCandidate(new RTCIceCandidate(candidateData));
            } catch (e) {
              console.error('Failed to add ICE candidate:', e, message.candidate);
            }
          }
          break;

        case 'stream-ended':
          setStatus('stream ended');
          setError('The stream has ended');
          break;

        case 'error':
          setError(message.message);
          setStatus('error');
          break;

        default:
          console.log('Unknown message type:', message.type);
      }
    };

    wsRef.current.onerror = (error) => {
      console.error('WebSocket error:', error);
      setError('Connection error');
      setStatus('error');
    };

    wsRef.current.onclose = () => {
      console.log('WebSocket disconnected');
      setStatus('disconnected');
    };
  };

  const handleOffer = async (offer, senderId) => {
    try {
      // Parse offer if it's a string (Android sends it as a JSON string inside the message)
      const offerData = typeof offer === 'string' ? JSON.parse(offer) : offer;

      // Create RTCPeerConnection with TURN servers
      pcRef.current = new RTCPeerConnection({
        iceServers: [
          { urls: 'stun:stun.l.google.com:19302' },
          {
            urls: 'turn:20.244.29.48:3478',
            username: 'dome',
            credential: 'domepass123'
          }
        ]
      });

      // Handle incoming tracks
      pcRef.current.ontrack = (event) => {
        console.log('Received remote track', event.track.kind, 'readyState:', event.track.readyState);
        console.log('Stream:', event.streams[0]);
        console.log('Stream tracks:', event.streams[0].getTracks().map(t => `${t.kind}: ${t.readyState}`));

        if (isCleanedUpRef.current) return;

        if (videoRef.current && event.streams[0]) {
          const videoElement = videoRef.current;
          const stream = event.streams[0];

          // Set the stream if not already set
          if (videoElement.srcObject !== stream) {
            console.log('Setting srcObject to stream');
            videoElement.srcObject = stream;
            videoElement.muted = false;
            videoElement.volume = 1.0;

            // Wait a bit for both tracks, then try to play
            setTimeout(() => {
              if (!isCleanedUpRef.current && videoElement.srcObject) {
                console.log('Attempting to play video...');
                videoElement.play()
                  .then(() => {
                    console.log('✅ Video playing successfully');
                    setNeedsManualPlay(false);
                    setError(null);
                  })
                  .catch(e => {
                    console.error('❌ Autoplay failed:', e.name, e.message);
                    setNeedsManualPlay(true);
                    setError(null);
                  });
              }
            }, 500);
          }

          setStatus('streaming');
        }
      };

      // Handle ICE candidates
      pcRef.current.onicecandidate = (event) => {
        if (event.candidate && wsRef.current) {
          wsRef.current.send(JSON.stringify({
            type: 'ice-candidate',
            candidate: event.candidate,
            targetId: senderId
          }));
        }
      };

      // Handle connection state changes
      pcRef.current.onconnectionstatechange = () => {
        console.log('Connection state:', pcRef.current.connectionState);
        setStatus(pcRef.current.connectionState);
      };

      // Handle ICE connection state
      pcRef.current.oniceconnectionstatechange = () => {
        console.log('ICE connection state:', pcRef.current.iceConnectionState);
      };

      // Set remote description
      await pcRef.current.setRemoteDescription(new RTCSessionDescription(offerData));

      // Create answer
      const answer = await pcRef.current.createAnswer();
      await pcRef.current.setLocalDescription(answer);

      // Send answer
      wsRef.current.send(JSON.stringify({
        type: 'answer',
        answer: answer,
        targetId: senderId
      }));
    } catch (error) {
      console.error('Error handling offer:', error);
      setError('Failed to establish connection');
      setStatus('error');
    }
  };

  const cleanup = () => {
    if (pcRef.current) {
      pcRef.current.close();
      pcRef.current = null;
    }
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
  };

  const getEmbedCode = () => {
    const url = `${window.location.origin}?streamId=${streamId}`;
    return `<iframe src="${url}" width="640" height="480" frameborder="0" allowfullscreen></iframe>`;
  };

  const copyEmbedCode = () => {
    navigator.clipboard.writeText(getEmbedCode());
    alert('Embed code copied to clipboard!');
  };

  const handleManualPlay = () => {
    if (videoRef.current) {
      videoRef.current.play()
        .then(() => {
          console.log('Manual play successful');
          setNeedsManualPlay(false);
        })
        .catch(e => {
          console.error('Manual play failed:', e);
          setError('Failed to play video: ' + e.message);
        });
    }
  };

  return (
    <div className="stream-viewer">
      <div className="video-container">
        <video
          ref={videoRef}
          playsInline
          controls
          className="video-player"
        />
        {needsManualPlay && (
          <button
            className="play-button-overlay"
            onClick={handleManualPlay}
            title="Click to start video playback"
          >
            ▶️ Click to Play
          </button>
        )}
        <div className={`status-badge ${status.replace(' ', '-')}`}>
          {status}
        </div>
      </div>

      {error && (
        <div className="error-message">
          ⚠️ {error}
        </div>
      )}

      <div className="stream-info">
        <h2>Stream Information</h2>
        <div className="info-grid">
          <div className="info-item">
            <span className="label">Stream ID:</span>
            <span className="value">{streamId}</span>
          </div>
          <div className="info-item">
            <span className="label">Status:</span>
            <span className="value">{status}</span>
          </div>
          <div className="info-item">
            <span className="label">Your Client ID:</span>
            <span className="value">{clientId || 'Not connected'}</span>
          </div>
        </div>

        <div className="embed-section">
          <h3>Embed this stream</h3>
          <textarea
            className="embed-code"
            value={getEmbedCode()}
            readOnly
          />
          <button onClick={copyEmbedCode} className="copy-button">
            📋 Copy Embed Code
          </button>
        </div>
      </div>
    </div>
  );
};

export default StreamViewer;
