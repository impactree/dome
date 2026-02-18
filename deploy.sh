#!/bin/bash

# Deployment Script for Dome Android Streaming Server
# Use this script on an Ubuntu server. It will install dependencies,
# clone the repo, and launch the signaling server on port 3004 and
# the web client on port 3005.

set -euo pipefail

# Accept server IP as first argument or environment variable
SERVER_IP=${1:-${SERVER_IP:-20.244.82.40}}
SIGNALING_PORT=3004
WEB_PORT=3005

echo "🚀 Starting deployment (server IP=${SERVER_IP})..."

# 1. Update system
sudo apt update && sudo apt upgrade -y

# 2. Install Node.js v18
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install -y nodejs

# 3. Install Git and PM2
sudo apt install -y git
sudo npm install -g pm2

# 4. Clone repository
if [ -d "dome" ]; then
    echo "Removing existing dome directory..."
    rm -rf dome
fi

git clone https://github.com/impactree/dome.git dome
cd dome

# 5. Setup signaling server
cd signaling-server
npm install

export PORT=$SIGNALING_PORT
export PUBLIC_URL="http://${SERVER_IP}:$SIGNALING_PORT"
pm2 delete signaling 2>/dev/null || true
pm2 start server.js --name signaling --env production -- --port $PORT
unset PUBLIC_URL

# 6. Setup web client
cd ../web-client
npm install
export PUBLIC_URL="http://${SERVER_IP}:$WEB_PORT"
npm run build
unset PUBLIC_URL

sudo npm install -g serve
pm2 delete web-client 2>/dev/null || true
pm2 start "serve -s build -l $WEB_PORT" --name web-client

# 7. Save PM2 config
pm2 save
pm2 startup | tail -n 1 > startup_script.sh
chmod +x startup_script.sh
./startup_script.sh
rm startup_script.sh

echo "✅ deployment complete!"
echo "Signaling Server: http://${SERVER_IP}:$SIGNALING_PORT"
echo "Web Client: http://${SERVER_IP}:$WEB_PORT"
