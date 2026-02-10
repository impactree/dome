# WebRTC Streaming Cloud Deployment

This folder contains the cloud-ready version for deployment testing.

## Deployment Plan

### 1. Signaling Server → Render
- **Platform**: Render.com (Free tier)
- **Type**: Web Service with WebSocket support
- **URL**: Will be `https://your-app-name.onrender.com`

### 2. Web Client → Vercel
- **Platform**: Vercel (Free tier)
- **Type**: Static React app
- **URL**: Will be `https://your-app-name.vercel.app`

## Deployment Steps

### Step 1: Deploy Signaling Server to Render

1. Go to https://render.com and sign up/login
2. Click "New" → "Web Service"
3. Connect your GitHub repo OR use "Deploy from Git"
4. Select the `signaling-server` folder
5. Configure:
   - **Name**: webrtc-signaling
   - **Environment**: Node
   - **Build Command**: `npm install`
   - **Start Command**: `npm start`
   - **Plan**: Free
6. Click "Create Web Service"
7. Wait for deployment (~2-3 minutes)
8. Copy the URL (e.g., `https://webrtc-signaling.onrender.com`)

### Step 2: Update Web Client with Render URL

Update `web-client/src/components/StreamViewer.js`:
```javascript
const SIGNALING_SERVER = 'wss://your-render-url.onrender.com';
```

Update `web-client/src/components/StreamList.js`:
```javascript
const API_URL = 'https://your-render-url.onrender.com';
```

### Step 3: Deploy Web Client to Vercel

1. Go to https://vercel.com and sign up/login
2. Click "Add New" → "Project"
3. Import your GitHub repo
4. Select `web-client` as root directory
5. Framework Preset: Create React App
6. Click "Deploy"
7. Wait for deployment (~1-2 minutes)
8. Copy the URL (e.g., `https://webrtc-viewer.vercel.app`)

### Step 4: Update Android App

Update `MainActivity.kt`:
```kotlin
val signalingServerUrl = "wss://your-render-url.onrender.com"
```

Rebuild APK and install on device.

## Testing

1. Open Android app → Camera should start streaming
2. Open `https://your-vercel-url.vercel.app` in browser
3. Click on stream → Should connect and show video

## Network Independence

✅ Android can be on mobile data (4G/5G)
✅ Viewer can be anywhere in the world
✅ No need for same WiFi network

## Files Modified for Cloud

- `signaling-server/`: Ready for Render deployment
- `web-client/`: Ready for Vercel deployment (after URL update)
- Added `render.yaml` for easy Render configuration
