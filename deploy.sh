#!/bin/bash

# Deployment Script for Dome Android Streaming Server
# Run this on your Azure Ubuntu VM

echo "🚀 Starting Deployment..."

# 1. Update System
echo "📦 Updating system packages..."
sudo apt update && sudo apt upgrade -y

# 2. Install Node.js (v18)
echo "🟢 Installing Node.js v18..."
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install -y nodejs

# 3. Install Git and PM2
echo "🛠️ Installing Git and PM2..."
sudo apt install -y git
sudo npm install -g pm2

# 4. Clone Repository
echo "📂 Cloning repository..."
# remove any existing checkout and clone the official repo
if [ -d "dome" ]; then
    echo "   Removing existing dome directory..."
    rm -rf dome
fi

if [ -d "dome" ]; then
    echo "   Repo exists, pulling latest..."
    cd dome
    git pull
else
    git clone https://github.com/impactree/dome.git dome
    cd dome
fi

# 5. Setup Signaling Server
echo "📡 Setting up Signaling Server..."
cd signaling-server
npm install
# use custom port (3004) and public url environment variables
export PORT=3004
export PUBLIC_URL="http://$(curl -s ifconfig.me):$PORT"
# Start with PM2; provide the env vars so the process can read them
pm2 delete signaling 2>/dev/null || true
pm2 start server.js --name signaling --env production -- --port $PORT
# (alternatively use pm2 start --name signaling --node-args="-r dotenv/config" server.js)


# 6. Setup Web Client
echo "🌐 Setting up Web Client..."
cd ../web-client
npm install
npm run build
# Serve with PM2 (using 'serve' package) on port 3005
sudo npm install -g serve
pm2 delete web-client 2>/dev/null || true
pm2 start "serve -s build -l 3005" --name web-client

# 7. Save PM2 processes
pm2 save
pm2 startup | tail -n 1 > startup_script.sh
chmod +x startup_script.sh
./startup_script.sh
rm startup_script.sh

echo "✅ deployment complete!"
echo "Signaling Server: http://$(curl -s ifconfig.me):3004"  # listening port may differ from inside container
echo "Web Client: http://$(curl -s ifconfig.me):3005"
