#!/usr/bin/env python3
"""
NoemtAddons Control Plane & Discord Bot Main Entrypoint
======================================================
Unified runtime combining the WebSocket/HTTP Control Server
and the Discord Management Bot on a shared asyncio event loop.
"""

import os
import sys
import json
import socket
import argparse
import traceback
from pathlib import Path
from contextlib import closing

# Ensure server/ directory is in sys.path
SERVER_ROOT = Path(__file__).parent.resolve()
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from server import create_server
from bot import create_bot

# Load .env configuration if present
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    env_file = SERVER_ROOT / ".env"
    if env_file.exists():
        try:
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
        except Exception:
            pass


def get_available_port() -> int:
    with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as s:
        s.bind(('', 0))
        return s.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description="NoemtAddons Integrated Control Plane & Discord Bot")
    parser.add_argument("--host", default="0.0.0.0", help="Host address (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=None, help="Port (default: 8765 or from ports.json / env)")
    parser.add_argument("--token", default=None, help="Discord Bot Token")
    parser.add_argument("--channel", default=None, help="Discord Channel ID for alerts")
    parser.add_argument("--secret", default=None, help="Optional client WebSocket authentication secret")
    args = parser.parse_args()

    if args.token:
        os.environ["DISCORD_BOT_TOKEN"] = args.token
    if args.channel:
        os.environ["DISCORD_CHANNEL_ID"] = args.channel
    if args.secret:
        os.environ["AUTH_SECRET"] = args.secret

    app = create_server()
    bot = create_bot()

    bot_name = os.path.basename(os.getcwd())
    ports_path = SERVER_ROOT / "ports.json"
    port = args.port

    if port is None:
        if ports_path.exists():
            try:
                with open(ports_path, "r") as f:
                    ports = json.load(f)
                port = ports.get(bot_name, 8765)
            except Exception:
                port = int(os.getenv("PORT", 8765))
        else:
            port = int(os.getenv("PORT", 8765))

    try:
        bot.run(app, port=port, host=args.host)
    except Exception as e:
        error_info = f"Fatal error starting NoemtAddons server & bot: {str(e)}\n{traceback.format_exc()}"
        print(error_info)
        raise


if __name__ == "__main__":
    main()
