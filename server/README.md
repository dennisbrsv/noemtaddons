# NoemtAddons Remote Control Server & WebSocket Guide

This directory contains the Python WebSocket server for communicating with and controlling the **NoemtAddons** Minecraft client mod remotely.

---

## 1. Quick Start

### Install Dependencies
```bash
pip install websockets
```

### Run Locally
```bash
python3 server/server.py --port 8765
```

---

## 2. Interactive CLI Commands

Once the server is running, you can manage connected Minecraft clients directly in the terminal:

| Command | Description | Example |
|---|---|---|
| `list` | List all connected players, UUIDs, IPs, and mod versions | `list` |
| `msg <player\|all> <text>` | Send a mod chat message to the player's screen | `msg all &aHello from server!` |
| `chat <player\|all> <command>` | Execute a command or chat message as the player | `chat Noemt /warp hub` |
| `title <player\|all> <title> [subtitle]` | Display a Minecraft title/subtitle alert | `title Noemt &c&lALERT &eDungeon Ready!` |
| `goto <player\|all> <x> <y> <z>` | Direct the pathfinder to navigate to coordinates | `goto Noemt 6 184 53` |
| `stop <player\|all>` | Cancel active pathfinder navigation | `stop all` |
| `discord <title> <desc>` | Trigger a Discord notification through the client | `discord "Boss Spawned" "F7 Blood Done"` |
| `status <player\|all>` | Query player location, health, and navigation status | `status Noemt` |
| `raw <player\|all> <json>` | Send custom JSON payloads | `raw all {"type":"CUSTOM"}` |

---

## 3. Production Deployment with SSL (`wss://addons.noemt.dev`)

To host on your server with SSL/TLS using **Nginx**:

### Nginx Configuration
Add this to your domain's Nginx server block:

```nginx
server {
    server_name addons.noemt.dev;

    listen 443 ssl http2;
    ssl_certificate /etc/letsencrypt/live/addons.noemt.dev/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/addons.noemt.dev/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```

Then run the Python script in the background using `tmux`, `screen`, or `systemd`:
```bash
python3 server/server.py --port 8765
```

---

## 4. Minecraft Mod Configuration (`/noemt config`)

Under **Discord & Remote**:
* **Discord Notifications**: Toggle ON/OFF
* **Discord Bot Token**: Your Discord Bot Token (from Discord Developer Portal)
* **Discord Channel ID**: Destination Discord channel ID
* **Remote WebSocket Enabled**: Toggle ON (maintains connection)
* **WebSocket Server URL**: `wss://addons.noemt.dev` (or `ws://localhost:8765` for local testing)
* **WebSocket Secret Key**: (Optional authentication key)

In-game commands:
* `/noemt discord test` — Sends a test notification to your Discord channel.
* `/noemt remote status` — Shows current WebSocket connection state and server URL.
* `/noemt remote connect` — Force reconnects to the WebSocket server.
* `/noemt remote disconnect` — Closes the active WebSocket connection.
