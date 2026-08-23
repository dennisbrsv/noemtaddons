#!/usr/bin/env python3
"""
NoemtAddons Remote WebSocket & Control Server
============================================
Server for maintaining active WebSocket connections with NoemtAddons Minecraft mod.
Allows remote control, sending events, commanding pathfinder, showing titles, and triggering alerts.

Usage:
    python3 server.py [--host 0.0.0.0] [--port 8765] [--secret YOUR_SECRET]

Requirements:
    pip install websockets aiohttp
"""

import asyncio
import json
import logging
import argparse
import sys
from datetime import datetime
from typing import Dict, Optional

try:
    import websockets
except ImportError:
    print("Error: 'websockets' package is required. Install it using: pip install websockets")
    sys.exit(1)

logging.basicConfig(
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
    level=logging.INFO
)
logger = logging.getLogger("NoemtServer")

# Active connected clients: player_name -> websocket connection info
clients: Dict[str, dict] = {}
ws_to_player: Dict[object, str] = {}
AUTH_SECRET: Optional[str] = None


async def handle_client(websocket):
    client_ip = websocket.remote_address[0] if websocket.remote_address else "Unknown"
    logger.info(f"Incoming connection from {client_ip}")
    player_name = None

    try:
        async for message in websocket:
            try:
                data = json.loads(message)
            except json.JSONDecodeError:
                logger.warning(f"Received non-JSON message from {client_ip}: {message}")
                continue

            msg_type = data.get("type", "").upper()

            if msg_type == "HANDSHAKE":
                player_name = data.get("player", f"Player_{client_ip}")
                player_uuid = data.get("uuid", "Unknown")
                secret = data.get("secret", "")
                version = data.get("modVersion", "Unknown")

                if AUTH_SECRET and secret != AUTH_SECRET:
                    logger.warning(f"Auth failed for {player_name} ({client_ip}): Invalid secret key.")
                    await websocket.close(4001, "Invalid secret key")
                    return

                clients[player_name] = {
                    "ws": websocket,
                    "ip": client_ip,
                    "uuid": player_uuid,
                    "version": version,
                    "connected_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                }
                ws_to_player[websocket] = player_name

                logger.info(f"✅ Player '{player_name}' (UUID: {player_uuid}, Mod v{version}) authenticated successfully!")
                
                # Acknowledge handshake
                ack = {
                    "type": "HANDSHAKE_ACK",
                    "message": f"Connected to NoemtAddons Remote Server as '{player_name}'",
                    "serverTime": int(datetime.now().timestamp() * 1000)
                }
                await websocket.send(json.dumps(ack))

            elif msg_type == "PONG":
                logger.debug(f"PONG received from {player_name}")

            elif msg_type == "STATUS_RESPONSE":
                x = data.get("x", 0)
                y = data.get("y", 0)
                z = data.get("z", 0)
                hp = data.get("health", 0)
                nav = data.get("isNavigating", False)
                logger.info(f"📊 Status [{player_name}]: Pos=({x:.1f}, {y:.1f}, {z:.1f}) | HP={hp} | Navigating={nav}")

            elif msg_type == "EVENT":
                event_name = data.get("event", "UNKNOWN")
                event_data = data.get("data", {})
                logger.info(f"📢 Event from {player_name} [{event_name}]: {event_data}")

            else:
                logger.info(f"Received from {player_name} [{msg_type}]: {data}")

    except websockets.exceptions.ConnectionClosed:
        pass
    except Exception as e:
        logger.error(f"Error handling client {client_ip}: {e}")
    finally:
        if player_name and player_name in clients:
            del clients[player_name]
        if websocket in ws_to_player:
            del ws_to_player[websocket]
        logger.info(f"❌ Connection closed for {player_name or client_ip}")


async def send_to_target(target: str, payload: dict) -> int:
    """Send payload to a specific player name or 'all'."""
    raw = json.dumps(payload)
    count = 0
    if target.lower() == "all":
        for name, info in list(clients.items()):
            try:
                await info["ws"].send(raw)
                count += 1
            except Exception as e:
                logger.error(f"Failed to send to {name}: {e}")
    else:
        if target in clients:
            try:
                await clients[target]["ws"].send(raw)
                count = 1
            except Exception as e:
                logger.error(f"Failed to send to {target}: {e}")
        else:
            print(f"Player '{target}' not found in active clients.")
    return count


async def interactive_console():
    """Interactive CLI inside terminal to control connected Minecraft players."""
    await asyncio.sleep(1)
    print("\n" + "=" * 60)
    print(" 🚀 NoemtAddons Remote Control Console Ready")
    print(" Type 'help' for a list of commands.")
    print("=" * 60 + "\n")

    loop = asyncio.get_event_loop()
    while True:
        try:
            line = await loop.run_in_executor(None, input, "noemt-server> ")
            line = line.strip()
            if not line:
                continue

            parts = line.split(" ", 1)
            cmd = parts[0].lower()
            args = parts[1] if len(parts) > 1 else ""

            if cmd in ("help", "?"):
                print("""
Available Commands:
  list                                - List all connected players
  msg <player|all> <text>             - Send chat message / notification
  chat <player|all> <command>         - Execute command/chat as player (e.g. /warp hub)
  title <player|all> <title> [sub]    - Show screen title alert
  goto <player|all> <x> <y> <z>       - Direct player pathfinder to coordinates
  stop <player|all>                   - Cancel active pathfinder navigation
  discord <title> <desc>              - Trigger Discord bot notification on client
  status <player|all>                 - Query player position, health & status
  raw <player|all> <json_payload>     - Send raw custom JSON packet
  quit / exit                         - Shutdown server
""")

            elif cmd == "list":
                if not clients:
                    print("No players currently connected.")
                else:
                    print(f"\n--- Connected Players ({len(clients)}) ---")
                    for name, info in clients.items():
                        print(f"  • {name} | UUID: {info['uuid']} | IP: {info['ip']} | Mod: v{info['version']} | Joined: {info['connected_at']}")
                    print()

            elif cmd == "msg":
                sub = args.split(" ", 1)
                if len(sub) < 2:
                    print("Usage: msg <player|all> <text>")
                    continue
                target, text = sub[0], sub[1]
                n = await send_to_target(target, {"type": "MESSAGE", "message": text})
                print(f"Sent message to {n} client(s).")

            elif cmd == "chat":
                sub = args.split(" ", 1)
                if len(sub) < 2:
                    print("Usage: chat <player|all> <command_or_chat>")
                    continue
                target, text = sub[0], sub[1]
                n = await send_to_target(target, {"type": "CHAT", "text": text})
                print(f"Dispatched command to {n} client(s).")

            elif cmd == "title":
                sub = args.split(" ")
                if len(sub) < 2:
                    print("Usage: title <player|all> <title_text> [subtitle_text]")
                    continue
                target = sub[0]
                title_text = sub[1]
                sub_text = " ".join(sub[2:]) if len(sub) > 2 else ""
                n = await send_to_target(target, {"type": "TITLE", "title": title_text, "subtitle": sub_text})
                print(f"Sent title to {n} client(s).")

            elif cmd in ("goto", "pf"):
                sub = args.split(" ")
                if len(sub) < 4:
                    print("Usage: goto <player|all> <x> <y> <z>")
                    continue
                target = sub[0]
                try:
                    x, y, z = int(sub[1]), int(sub[2]), int(sub[3])
                except ValueError:
                    print("Coordinates x, y, z must be integers.")
                    continue
                n = await send_to_target(target, {"type": "PATHFIND", "x": x, "y": y, "z": z})
                print(f"Sent pathfinder destination ({x}, {y}, {z}) to {n} client(s).")

            elif cmd == "stop":
                target = args.strip() if args else "all"
                n = await send_to_target(target, {"type": "PATHFIND_STOP"})
                print(f"Sent pathfinder stop signal to {n} client(s).")

            elif cmd == "status":
                target = args.strip() if args else "all"
                n = await send_to_target(target, {"type": "STATUS_REQUEST"})
                print(f"Requested status from {n} client(s).")

            elif cmd == "discord":
                sub = args.split(" ", 1)
                title = sub[0] if len(sub) > 0 else "Remote Alert"
                desc = sub[1] if len(sub) > 1 else ""
                n = await send_to_target("all", {"type": "DISCORD_NOTIFY", "title": title, "description": desc})
                print(f"Triggered Discord notification across {n} client(s).")

            elif cmd == "raw":
                sub = args.split(" ", 1)
                if len(sub) < 2:
                    print("Usage: raw <player|all> <json_payload>")
                    continue
                target, raw_str = sub[0], sub[1]
                try:
                    payload = json.loads(raw_str)
                    n = await send_to_target(target, payload)
                    print(f"Sent raw payload to {n} client(s).")
                except json.JSONDecodeError as err:
                    print(f"Invalid JSON: {err}")

            elif cmd in ("quit", "exit"):
                print("Stopping server...")
                sys.exit(0)

            else:
                print(f"Unknown command '{cmd}'. Type 'help' for options.")

        except (EOFError, KeyboardInterrupt):
            print("\nShutting down server.")
            break
        except Exception as e:
            print(f"Console error: {e}")


async def main():
    parser = argparse.ArgumentParser(description="NoemtAddons Remote Control WebSocket Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host address to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8765, help="Port to listen on (default: 8765)")
    parser.add_argument("--secret", default=None, help="Optional authentication secret key")
    args = parser.parse_args()

    global AUTH_SECRET
    AUTH_SECRET = args.secret

    logger.info(f"Starting NoemtAddons WebSocket Server on ws://{args.host}:{args.port}")
    if AUTH_SECRET:
        logger.info("Secret authentication key is ENABLED.")

    server = await websockets.serve(handle_client, args.host, args.port)

    # Run interactive console in parallel with server
    await asyncio.gather(
        server.wait_closed(),
        interactive_console()
    )


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer terminated.")
