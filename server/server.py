#!/usr/bin/env python3
"""
NoemtAddons Mod-Loader, CI/CD Auto-Builder & Control Server
===========================================================
Unified server for NoemtAddons:
1. HTTP Endpoints:
   - GET /loaders/noemtaddons-legit.jar
   - GET /loaders/noemtaddons-cheat.jar
   - GET /changelog
   - GET /api/version
   - GET / (Interactive Web Dashboard)
   - POST /api/webhook (Instant GitHub / Git Webhook Trigger)
2. CI/CD Auto-Builder:
   - Periodically polls git for new commits (git pull)
   - Automatically parses commit messages into Minecraft colorized changelog
   - Executes './gradlew clean build'
   - Posts rich formatted updates to Discord Webhook
3. WebSocket Server & Interactive Terminal CLI:
   - Live telemetry, player monitoring, remote commands, pathfinding, and screen alerts.

Usage:
    python3 server.py [--host 0.0.0.0] [--port 8765] [--repo-dir ..] [--branch master]
                      [--poll-interval 60] [--discord-webhook URL] [--secret KEY]

Zero external pip dependencies required (pure Python 3.10+ standard library).
"""

import asyncio
import os
import sys
import json
import time
import base64
import hashlib
import struct
import logging
import argparse
import subprocess
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional, Tuple, List

logging.basicConfig(
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
    level=logging.INFO
)
logger = logging.getLogger("NoemtServer")

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# State
clients: Dict[str, dict] = {}
ws_to_player: Dict[asyncio.StreamWriter, str] = {}
AUTH_SECRET: Optional[str] = None
REPO_DIR: Path = Path(__file__).parent.parent
JARS_DIR: Path = REPO_DIR / "build" / "libs"
DISCORD_WEBHOOK: Optional[str] = os.getenv("DISCORD_WEBHOOK_URL")
GIT_BRANCH: str = "master"
POLL_INTERVAL: int = 60
IS_BUILDING: bool = False
LAST_BUILD_STATUS: str = "Ready"
LAST_BUILD_TIME: str = "N/A"


def get_jar_path(flavor: str) -> Optional[Path]:
    """Finds the path of the compiled jar for the given flavor (legit / cheat)."""
    candidates = [
        JARS_DIR / f"noemtaddons-1.0.0-{flavor}.jar",
        JARS_DIR / f"noemtaddons-{flavor}.jar",
        Path(__file__).parent / "jars" / f"noemtaddons-{flavor}.jar",
        Path(__file__).parent / "jars" / f"noemtaddons-1.0.0-{flavor}.jar",
    ]
    for p in candidates:
        if p.exists() and p.is_file():
            return p
    return None


def get_file_info(file_path: Path) -> dict:
    """Calculates file size and SHA-256 checksum."""
    if not file_path.exists():
        return {"exists": False, "size": 0, "sha256": ""}
    
    sha = hashlib.sha256()
    size = 0
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            sha.update(chunk)
            size += len(chunk)
    
    return {
        "exists": True,
        "size": size,
        "sha256": sha.hexdigest(),
        "modified": datetime.fromtimestamp(file_path.stat().st_mtime).strftime("%Y-%m-%d %H:%M:%S")
    }


def compute_version_metadata() -> dict:
    legit_p = get_jar_path("legit")
    cheat_p = get_jar_path("cheat")

    legit_info = get_file_info(legit_p) if legit_p else {"exists": False}
    cheat_info = get_file_info(cheat_p) if cheat_p else {"exists": False}

    return {
        "version": "1.0.0",
        "timestamp": int(datetime.now().timestamp()),
        "last_build": LAST_BUILD_TIME,
        "build_status": LAST_BUILD_STATUS,
        "endpoints": {
            "legit": {
                "url": "https://addons.noemt.dev/loaders/noemtaddons-legit.jar",
                "filename": legit_p.name if legit_p else "noemtaddons-legit.jar",
                "sha256": legit_info.get("sha256", ""),
                "size": legit_info.get("size", 0),
                "modified": legit_info.get("modified", "")
            },
            "cheat": {
                "url": "https://addons.noemt.dev/loaders/noemtaddons-cheat.jar",
                "filename": cheat_p.name if cheat_p else "noemtaddons-cheat.jar",
                "sha256": cheat_info.get("sha256", ""),
                "size": cheat_info.get("size", 0),
                "modified": cheat_info.get("modified", "")
            }
        }
    }


# ==============================================================================
# Discord Webhook Notification System
# ==============================================================================

def send_discord_webhook(webhook_url: str, title: str, description: str, color: int, fields: list, footer: str = "NoemtAddons CI/CD Auto-Deployer"):
    """Sends a rich embed notification to a Discord webhook."""
    if not webhook_url:
        return

    payload = {
        "username": "NoemtAddons CI/CD",
        "avatar_url": "https://i.imgur.com/8Qp4wZJ.png",
        "embeds": [
            {
                "title": title,
                "description": description,
                "color": color,
                "fields": fields,
                "timestamp": datetime.utcnow().isoformat() + "Z",
                "footer": {
                    "text": footer
                }
            }
        ]
    }

    def _post():
        try:
            req = urllib.request.Request(
                webhook_url,
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "User-Agent": "NoemtAddons-Server/1.0"
                }
            )
            with urllib.request.urlopen(req, timeout=8) as resp:
                pass
        except Exception as err:
            logger.error(f"Failed to post Discord webhook: {err}")

    asyncio.get_event_loop().run_in_executor(None, _post)


# ==============================================================================
# Git Auto-Pull & Build Engine
# ==============================================================================

class AutoBuilder:
    @staticmethod
    def get_commit_hash(short: bool = True) -> str:
        try:
            args = ["git", "rev-parse", "--short", "HEAD"] if short else ["git", "rev-parse", "HEAD"]
            res = subprocess.run(args, cwd=REPO_DIR, capture_output=True, text=True, check=True)
            return res.stdout.strip()
        except Exception:
            return "unknown"

    @staticmethod
    def get_latest_commit_details() -> Tuple[str, str, str]:
        """Returns (hash, author, message)."""
        try:
            res = subprocess.run(
                ["git", "log", "-1", "--pretty=format:%h|||%an|||%s"],
                cwd=REPO_DIR,
                capture_output=True,
                text=True,
                check=True
            )
            parts = res.stdout.strip().split("|||")
            if len(parts) == 3:
                return parts[0], parts[1], parts[2]
        except Exception:
            pass
        return AutoBuilder.get_commit_hash(), "Unknown", "Initial release"

    @staticmethod
    def check_for_updates() -> List[dict]:
        """Fetches origin and checks if new commits exist on remote branch."""
        try:
            # 1. Fetch remote
            subprocess.run(["git", "fetch", "origin", GIT_BRANCH], cwd=REPO_DIR, capture_output=True, text=True, check=True, timeout=20)
            
            # 2. Check commit diff
            res = subprocess.run(
                ["git", "log", f"HEAD..origin/{GIT_BRANCH}", "--pretty=format:%h|||%an|||%s"],
                cwd=REPO_DIR,
                capture_output=True,
                text=True,
                check=True
            )
            commits = []
            for line in res.stdout.strip().splitlines():
                if not line:
                    continue
                parts = line.split("|||")
                if len(parts) == 3:
                    commits.append({"hash": parts[0], "author": parts[1], "message": parts[2]})
            return commits
        except Exception as e:
            logger.debug(f"Git check skipped or error: {e}")
            return []

    @staticmethod
    async def run_build(commits: Optional[List[dict]] = None, trigger_source: str = "Git Auto-Poll") -> bool:
        global IS_BUILDING, LAST_BUILD_STATUS, LAST_BUILD_TIME
        if IS_BUILDING:
            logger.warning("Build already in progress, skipping trigger.")
            return False

        IS_BUILDING = True
        LAST_BUILD_STATUS = "Building..."
        start_time = time.time()
        logger.info(f"🔨 Starting automated build process (Trigger: {trigger_source})...")

        loop = asyncio.get_event_loop()

        # 1. Git pull if commits exist
        if commits:
            logger.info(f"📥 Pulling {len(commits)} new commit(s) from origin/{GIT_BRANCH}...")
            pull_res = await loop.run_in_executor(
                None,
                lambda: subprocess.run(["git", "pull", "origin", GIT_BRANCH], cwd=REPO_DIR, capture_output=True, text=True)
            )
            if pull_res.returncode != 0:
                logger.error(f"Git pull failed:\n{pull_res.stderr}")

        # 2. Generate updated changelog
        short_hash, author, latest_msg = AutoBuilder.get_latest_commit_details()
        formatted_changelog = AutoBuilder.generate_changelog_text(short_hash, commits)
        changelog_path = Path(__file__).parent / "changelog.txt"
        changelog_path.write_text(formatted_changelog, encoding="utf-8")
        logger.info("📝 Changelog updated with latest commit information.")

        # 3. Notify Discord: Build Started
        if DISCORD_WEBHOOK:
            commit_list_text = "\n".join([f"• `{c['hash']}` {c['message']} - *{c['author']}*" for c in (commits or [{'hash': short_hash, 'message': latest_msg, 'author': author}])[:5]])
            send_discord_webhook(
                DISCORD_WEBHOOK,
                title=f"🔨 Build Started for `{short_hash}`",
                description=f"**Trigger:** {trigger_source}\n**Branch:** `{GIT_BRANCH}`\n\n**Commits:**\n{commit_list_text}",
                color=0xFFA502,
                fields=[
                    {"name": "Status", "value": "⏳ Compiling via Gradle...", "inline": True},
                    {"name": "Triggered By", "value": trigger_source, "inline": True}
                ]
            )

        # 4. Run Gradle Clean Build
        gradle_cmd = ["./gradlew", "clean", "build"]
        build_res = await loop.run_in_executor(
            None,
            lambda: subprocess.run(gradle_cmd, cwd=REPO_DIR, capture_output=True, text=True)
        )

        build_duration = round(time.time() - start_time, 1)
        LAST_BUILD_TIME = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        if build_res.returncode == 0:
            IS_BUILDING = False
            LAST_BUILD_STATUS = "Success"
            logger.info(f"✅ Build completed successfully in {build_duration}s!")

            meta = compute_version_metadata()
            legit_size_kb = meta['endpoints']['legit']['size'] / 1024
            cheat_size_kb = meta['endpoints']['cheat']['size'] / 1024

            # 5. Discord Webhook: Build Success
            if DISCORD_WEBHOOK:
                fields = [
                    {"name": "Branch", "value": f"`{GIT_BRANCH}`", "inline": True},
                    {"name": "Latest Commit", "value": f"`{short_hash}` ({author})", "inline": True},
                    {"name": "Build Time", "value": f"{build_duration}s", "inline": True},
                    {"name": "Legit JAR", "value": f"`{legit_size_kb:.1f} KB`", "inline": True},
                    {"name": "Cheat JAR", "value": f"`{cheat_size_kb:.1f} KB`", "inline": True},
                    {"name": "Active Clients", "value": f"{len(clients)} online", "inline": True},
                ]
                send_discord_webhook(
                    DISCORD_WEBHOOK,
                    title=f"🚀 NoemtAddons Deployed: `{short_hash}`",
                    description=f"**New build compiled and ready for loaders!**\n\n**Commit Message:**\n> {latest_msg}\n\n**Downloads:**\n• [Legit Mod JAR](https://addons.noemt.dev/loaders/noemtaddons-legit.jar)\n• [Cheat Mod JAR](https://addons.noemt.dev/loaders/noemtaddons-cheat.jar)\n• [Changelog](https://addons.noemt.dev/changelog)",
                    color=0x2ED573,
                    fields=fields
                )

            # 6. Broadcast update notification to connected Minecraft clients
            await send_to_target("all", {
                "type": "MESSAGE",
                "message": f"&b[NoemtAddons] &aServer updated to build &e{short_hash}&a! Restart game when convenient to apply changes."
            })
            await send_to_target("all", {
                "type": "TITLE",
                "title": "&a&lNoemtAddons Updated",
                "subtitle": f"&eBuild {short_hash} deployed"
            })

            return True
        else:
            IS_BUILDING = False
            LAST_BUILD_STATUS = "Failed"
            logger.error(f"❌ Build failed in {build_duration}s!")
            error_tail = "\n".join(build_res.stderr.splitlines()[-10:] if build_res.stderr else build_res.stdout.splitlines()[-10:])

            if DISCORD_WEBHOOK:
                send_discord_webhook(
                    DISCORD_WEBHOOK,
                    title=f"❌ Build Failed for `{short_hash}`",
                    description=f"**Build error after {build_duration}s:**\n```\n{error_tail[:1000]}\n```",
                    color=0xFF4757,
                    fields=[
                        {"name": "Branch", "value": f"`{GIT_BRANCH}`", "inline": True},
                        {"name": "Commit", "value": f"`{short_hash}`", "inline": True}
                    ]
                )
            return False

    @staticmethod
    def generate_changelog_text(short_hash: str, commits: Optional[List[dict]] = None) -> str:
        date_str = datetime.now().strftime("%Y-%m-%d %H:%M")
        lines = [
            f"§b§lNoemtAddons Auto-Update ({short_hash})",
            f"§7Deployed: {date_str}",
            "",
            "§a[Recent Changes]"
        ]

        if commits:
            for c in commits:
                lines.append(f"§e• {c['message']}")
                lines.append(f"§7  Commit: {c['hash']} by {c['author']}")
        else:
            hash_c, author, msg = AutoBuilder.get_latest_commit_details()
            lines.append(f"§e• {msg}")
            lines.append(f"§7  Commit: {hash_c} by {author}")

        lines.extend([
            "",
            "§a[Commands]",
            "§7  &noemt                 - Configuration menu",
            "§7  &noemt changelog       - View changelog",
            "§7  &stalk <ign>           - Player 3D tracer",
            "§7  &path <x> <y> <z>      - SkyHanni 3D pathfinder"
        ])
        return "\n".join(lines)


async def git_polling_loop():
    """Background loop that polls git every POLL_INTERVAL seconds."""
    if POLL_INTERVAL <= 0:
        logger.info("Git polling disabled (POLL_INTERVAL <= 0).")
        return

    logger.info(f"🔄 Git Auto-Pull polling active: checking every {POLL_INTERVAL}s on branch '{GIT_BRANCH}'...")
    while True:
        try:
            await asyncio.sleep(POLL_INTERVAL)
            if IS_BUILDING:
                continue

            loop = asyncio.get_event_loop()
            commits = await loop.run_in_executor(None, AutoBuilder.check_for_updates)
            if commits:
                logger.info(f"✨ Detected {len(commits)} new commit(s) on remote origin/{GIT_BRANCH}!")
                await AutoBuilder.run_build(commits=commits, trigger_source="Git Polling Daemon")
        except Exception as e:
            logger.error(f"Error in git polling loop: {e}")


# ==============================================================================
# WebSocket Frame Encoding / Decoding (RFC 6455)
# ==============================================================================

async def read_ws_frame(reader: asyncio.StreamReader) -> Tuple[int, bytes]:
    head = await reader.readexactly(2)
    b1, b2 = head[0], head[1]
    
    fin = (b1 & 0x80) != 0
    opcode = b1 & 0x0F
    is_masked = (b2 & 0x80) != 0
    length = b2 & 0x7F

    if length == 126:
        ext = await reader.readexactly(2)
        length = struct.unpack("!H", ext)[0]
    elif length == 127:
        ext = await reader.readexactly(8)
        length = struct.unpack("!Q", ext)[0]

    mask_key = await reader.readexactly(4) if is_masked else None
    payload = await reader.readexactly(length)

    if is_masked and mask_key:
        unmasked = bytearray(length)
        for i in range(length):
            unmasked[i] = payload[i] ^ mask_key[i % 4]
        payload = bytes(unmasked)

    return opcode, payload


def make_ws_frame(opcode: int, payload: bytes) -> bytes:
    length = len(payload)
    b1 = 0x80 | (opcode & 0x0F)
    
    if length <= 125:
        head = bytes([b1, length])
    elif length <= 65535:
        head = bytes([b1, 126]) + struct.pack("!H", length)
    else:
        head = bytes([b1, 127]) + struct.pack("!Q", length)

    return head + payload


async def send_ws_json(writer: asyncio.StreamWriter, data: dict):
    payload = json.dumps(data).encode("utf-8")
    frame = make_ws_frame(0x1, payload)
    writer.write(frame)
    await writer.drain()


# ==============================================================================
# HTTP & WebSocket Router
# ==============================================================================

async def handle_connection(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    peer = writer.get_extra_info("peername")
    client_ip = peer[0] if peer else "Unknown"

    try:
        header_bytes = await reader.readuntil(b"\r\n\r\n")
    except Exception:
        writer.close()
        return

    header_text = header_bytes.decode("utf-8", errors="ignore")
    lines = header_text.split("\r\n")
    if not lines or not lines[0]:
        writer.close()
        return

    req_line = lines[0].split()
    if len(req_line) < 2:
        writer.close()
        return

    method, path = req_line[0], req_line[1]
    headers = {}
    for line in lines[1:]:
        if ": " in line:
            k, v = line.split(": ", 1)
            headers[k.lower()] = v.strip()

    # WebSocket Upgrade
    if headers.get("upgrade", "").lower() == "websocket" and "sec-websocket-key" in headers:
        key = headers["sec-websocket-key"]
        accept_val = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        
        ws_response = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept_val}\r\n\r\n"
        )
        writer.write(ws_response.encode("utf-8"))
        await writer.drain()
        
        logger.info(f"🌐 WebSocket client connected from {client_ip}")
        await handle_ws_session(reader, writer, client_ip)
        return

    # HTTP Requests (GET & POST)
    await handle_http_request(method, path, headers, reader, writer, client_ip)


async def handle_http_request(method: str, path: str, headers: dict, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, client_ip: str):
    clean_path = path.split("?")[0].rstrip("/")
    if not clean_path:
        clean_path = "/"

    # 1. GitHub Webhook POST /api/webhook
    if method == "POST" and clean_path in ("/api/webhook", "/api/github-webhook"):
        content_len = int(headers.get("content-length", 0))
        body = await reader.readexactly(content_len) if content_len > 0 else b"{}"
        logger.info(f"⚡ GitHub Webhook received from {client_ip}! Triggering auto-pull and build...")
        
        # Trigger build in background
        asyncio.create_task(AutoBuilder.run_build(trigger_source="GitHub Webhook Trigger"))
        send_http_response(writer, 200, "application/json", b'{"status":"Build triggered successfully"}')
        return

    # 2. Web Dashboard
    if clean_path == "/":
        meta = compute_version_metadata()
        connected_count = len(clients)
        short_hash, author, msg = AutoBuilder.get_latest_commit_details()
        status_color = "#2ed573" if LAST_BUILD_STATUS == "Success" or LAST_BUILD_STATUS == "Ready" else ("#ffa502" if IS_BUILDING else "#ff4757")

        html = f"""<!DOCTYPE html>
<html>
<head>
    <title>NoemtAddons Mod & CI/CD Server</title>
    <meta charset="utf-8">
    <style>
        body {{ font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #0f111a; color: #e1e7ec; margin: 0; padding: 40px; }}
        .card {{ background: #1a1d29; border-radius: 12px; padding: 24px; margin-bottom: 24px; box-shadow: 0 4px 20px rgba(0,0,0,0.4); }}
        h1 {{ color: #00d2ff; margin-top: 0; }}
        h2 {{ color: #70a1ff; border-bottom: 1px solid #2f3542; padding-bottom: 8px; }}
        .btn {{ display: inline-block; background: #00d2ff; color: #0f111a; padding: 10px 20px; border-radius: 6px; text-decoration: none; font-weight: bold; margin-right: 10px; }}
        .btn:hover {{ background: #70a1ff; }}
        .btn-cheat {{ background: #ff4757; color: white; }}
        .btn-cheat:hover {{ background: #ff6b81; }}
        table {{ width: 100%; border-collapse: collapse; margin-top: 12px; }}
        th, td {{ padding: 12px; text-align: left; border-bottom: 1px solid #2f3542; }}
        th {{ background: #131622; color: #70a1ff; }}
        .badge {{ background: {status_color}; color: #0f111a; padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; }}
        code {{ background: #10131d; padding: 2px 6px; border-radius: 4px; color: #ffa502; }}
    </style>
</head>
<body>
    <div class="card">
        <h1>🚀 NoemtAddons CI/CD & Mod Server</h1>
        <p>
            Build Status: <span class="badge">{LAST_BUILD_STATUS}</span> | 
            Active Clients: <span class="badge" style="background:#2ed573;">{connected_count}</span> | 
            Git Commit: <code>{short_hash}</code> by <b>{author}</b>
        </p>
        <p><i>Latest Message:</i> {msg}</p>
        <div style="margin-top: 18px;">
            <a class="btn" href="/loaders/noemtaddons-legit.jar">📥 Download Legit Mod JAR</a>
            <a class="btn btn-cheat" href="/loaders/noemtaddons-cheat.jar">⚡ Download Cheat Mod JAR</a>
            <a class="btn" style="background:#2ed573; color:#0f111a;" href="/changelog">📜 View Changelog</a>
            <a class="btn" style="background:#57606f; color:white;" href="/api/version">📡 API Version JSON</a>
        </div>
    </div>

    <div class="card">
        <h2>📦 Available Mod Loader Builds</h2>
        <table>
            <tr><th>Flavor</th><th>Endpoint URL</th><th>Size</th><th>SHA-256 Hash</th></tr>
            <tr>
                <td><b>Legit</b></td>
                <td><code>/loaders/noemtaddons-legit.jar</code></td>
                <td>{meta['endpoints']['legit']['size'] / 1024:.1f} KB</td>
                <td><small>{meta['endpoints']['legit']['sha256'][:24]}...</small></td>
            </tr>
            <tr>
                <td><b>Cheat</b></td>
                <td><code>/loaders/noemtaddons-cheat.jar</code></td>
                <td>{meta['endpoints']['cheat']['size'] / 1024:.1f} KB</td>
                <td><small>{meta['endpoints']['cheat']['sha256'][:24]}...</small></td>
            </tr>
        </table>
    </div>

    <div class="card">
        <h2>👥 Connected Players ({len(clients)})</h2>
        <table>
            <tr><th>Player</th><th>UUID</th><th>IP Address</th><th>Mod Version</th><th>Connected At</th></tr>
            {"".join(f"<tr><td><b>{name}</b></td><td>{info['uuid']}</td><td>{info['ip']}</td><td>v{info['version']}</td><td>{info['connected_at']}</td></tr>" for name, info in clients.items()) if clients else "<tr><td colspan='5'><i>No players currently connected.</i></td></tr>"}
        </table>
    </div>
</body>
</html>"""
        send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
        return

    # 3. Version API endpoint
    if clean_path == "/api/version":
        meta = compute_version_metadata()
        send_http_response(writer, 200, "application/json", json.dumps(meta, indent=2).encode("utf-8"))
        return

    # 4. Changelog endpoint
    if clean_path in ("/changelog", "/api/changelog"):
        changelog_p = Path(__file__).parent / "changelog.txt"
        if changelog_p.exists():
            content = changelog_p.read_text(encoding="utf-8")
        else:
            short_h, _, _ = AutoBuilder.get_latest_commit_details()
            content = AutoBuilder.generate_changelog_text(short_h)
        send_http_response(writer, 200, "text/plain; charset=utf-8", content.encode("utf-8"))
        return

    # 5. Loader / Payload Jar Download endpoints
    if clean_path in ("/loaders/noemtaddons-legit.jar", "/download/legit", "/download/noemtaddons-legit.jar"):
        serve_jar_file(writer, "legit", client_ip)
        return

    if clean_path in ("/loaders/noemtaddons-cheat.jar", "/download/cheat", "/download/noemtaddons-cheat.jar"):
        serve_jar_file(writer, "cheat", client_ip)
        return

    # 404 Not Found
    send_http_response(writer, 404, "text/plain", b"404 Not Found")


def serve_jar_file(writer: asyncio.StreamWriter, flavor: str, client_ip: str):
    jar_path = get_jar_path(flavor)
    if not jar_path or not jar_path.exists():
        logger.warning(f"Requested {flavor} jar not found for {client_ip}")
        send_http_response(writer, 404, "text/plain", f"Error: {flavor} mod build not found on server.".encode("utf-8"))
        return

    file_size = jar_path.stat().st_size
    logger.info(f"📤 Serving {flavor} jar ({file_size} bytes) to {client_ip}")

    headers = [
        "HTTP/1.1 200 OK",
        "Content-Type: application/java-archive",
        f"Content-Length: {file_size}",
        f"Content-Disposition: attachment; filename=\"noemtaddons-{flavor}.jar\"",
        "Access-Control-Allow-Origin: *",
        "Connection: close",
        "\r\n"
    ]
    writer.write("\r\n".join(headers).encode("utf-8"))

    with open(jar_path, "rb") as f:
        while chunk := f.read(65536):
            writer.write(chunk)
    
    writer.close()


def send_http_response(writer: asyncio.StreamWriter, status_code: int, content_type: str, body: bytes):
    status_text = {200: "OK", 400: "Bad Request", 404: "Not Found", 500: "Internal Server Error"}.get(status_code, "OK")
    response_headers = [
        f"HTTP/1.1 {status_code} {status_text}",
        f"Content-Type: {content_type}",
        f"Content-Length: {len(body)}",
        "Access-Control-Allow-Origin: *",
        "Connection: close",
        "\r\n"
    ]
    writer.write("\r\n".join(response_headers).encode("utf-8") + body)
    writer.close()


# ==============================================================================
# WebSocket Session Loop
# ==============================================================================

async def handle_ws_session(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, client_ip: str):
    player_name = None
    try:
        while True:
            try:
                opcode, payload = await read_ws_frame(reader)
            except (asyncio.IncompleteReadError, ConnectionResetError):
                break

            if opcode == 0x8:
                break
            elif opcode == 0x9:
                writer.write(make_ws_frame(0xA, payload))
                await writer.drain()
                continue
            elif opcode == 0xA:
                continue
            elif opcode == 0x1:
                try:
                    data = json.loads(payload.decode("utf-8"))
                except json.JSONDecodeError:
                    continue

                msg_type = data.get("type", "").upper()

                if msg_type == "HANDSHAKE":
                    player_name = data.get("player", f"Player_{client_ip}")
                    player_uuid = data.get("uuid", "Unknown")
                    secret = data.get("secret", "")
                    version = data.get("modVersion", "Unknown")

                    if AUTH_SECRET and secret != AUTH_SECRET:
                        logger.warning(f"Auth failed for {player_name} ({client_ip}): Invalid secret.")
                        await send_ws_json(writer, {"type": "ERROR", "message": "Invalid authentication secret"})
                        break

                    clients[player_name] = {
                        "writer": writer,
                        "ip": client_ip,
                        "uuid": player_uuid,
                        "version": version,
                        "connected_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    }
                    ws_to_player[writer] = player_name
                    logger.info(f"✅ Player '{player_name}' connected (UUID: {player_uuid}, Mod v{version})")

                    await send_ws_json(writer, {
                        "type": "HANDSHAKE_ACK",
                        "message": f"Connected to NoemtAddons Server as '{player_name}'",
                        "serverTime": int(datetime.now().timestamp() * 1000)
                    })

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

                elif msg_type == "PONG":
                    logger.debug(f"PONG from {player_name}")

    except Exception as e:
        logger.error(f"WebSocket error with {player_name or client_ip}: {e}")
    finally:
        if player_name and player_name in clients:
            del clients[player_name]
        if writer in ws_to_player:
            del ws_to_player[writer]
        logger.info(f"❌ Connection closed for {player_name or client_ip}")
        writer.close()


async def send_to_target(target: str, payload: dict) -> int:
    count = 0
    if target.lower() == "all":
        for name, info in list(clients.items()):
            try:
                await send_ws_json(info["writer"], payload)
                count += 1
            except Exception as e:
                logger.error(f"Failed sending to {name}: {e}")
    else:
        if target in clients:
            try:
                await send_ws_json(clients[target]["writer"], payload)
                count = 1
            except Exception as e:
                logger.error(f"Failed sending to {target}: {e}")
        else:
            print(f"Player '{target}' is not online.")
    return count


# ==============================================================================
# Interactive Terminal Console
# ==============================================================================

async def interactive_console():
    await asyncio.sleep(1)
    print("\n" + "=" * 65)
    print(" 🚀 NoemtAddons CI/CD & Control Server Console Ready")
    print(" Type 'help' for command list.")
    print("=" * 65 + "\n")

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
  build / update                      - Force git pull & trigger rebuild
  webhook <url>                       - Set or test Discord webhook URL
  list                                - List all connected players
  msg <player|all> <text>             - Send chat message to player(s)
  chat <player|all> <command>         - Execute command as player
  title <player|all> <title> [sub]    - Show screen title alert
  goto <player|all> <x> <y> <z>       - Direct player pathfinder to coords
  stop <player|all>                   - Stop player pathfinder
  status <player|all>                 - Query player position & health
  discord <title> <desc>              - Send Discord notification test
  version                             - View build metadata and JAR status
  raw <player|all> <json>             - Send raw custom JSON packet
  quit / exit                         - Shutdown server
""")

            elif cmd in ("build", "update", "pull"):
                print("Triggering manual build...")
                asyncio.create_task(AutoBuilder.run_build(trigger_source="Manual CLI Command"))

            elif cmd == "webhook":
                global DISCORD_WEBHOOK
                if args:
                    DISCORD_WEBHOOK = args.strip()
                    print(f"Updated Discord webhook URL: {DISCORD_WEBHOOK}")
                    send_discord_webhook(
                        DISCORD_WEBHOOK,
                        title="🔔 Webhook Connected",
                        description="Discord webhook integration is successfully linked to NoemtAddons server!",
                        color=0x2ED573,
                        fields=[]
                    )
                else:
                    print(f"Current webhook: {DISCORD_WEBHOOK or 'None'}")

            elif cmd == "list":
                if not clients:
                    print("No players connected.")
                else:
                    print(f"\n--- Connected Players ({len(clients)}) ---")
                    for name, info in clients.items():
                        print(f"  • {name} | UUID: {info['uuid']} | IP: {info['ip']} | Mod: v{info['version']} | Joined: {info['connected_at']}")
                    print()

            elif cmd == "version":
                meta = compute_version_metadata()
                print(json.dumps(meta, indent=2))

            elif cmd == "msg":
                sub = args.split(" ", 1)
                if len(sub) < 2:
                    print("Usage: msg <player|all> <text>")
                    continue
                n = await send_to_target(sub[0], {"type": "MESSAGE", "message": sub[1]})
                print(f"Sent message to {n} client(s).")

            elif cmd == "chat":
                sub = args.split(" ", 1)
                if len(sub) < 2:
                    print("Usage: chat <player|all> <command>")
                    continue
                n = await send_to_target(sub[0], {"type": "CHAT", "text": sub[1]})
                print(f"Dispatched command to {n} client(s).")

            elif cmd == "title":
                sub = args.split(" ")
                if len(sub) < 2:
                    print("Usage: title <player|all> <title_text> [sub_text]")
                    continue
                sub_text = " ".join(sub[2:]) if len(sub) > 2 else ""
                n = await send_to_target(sub[0], {"type": "TITLE", "title": sub[1], "subtitle": sub_text})
                print(f"Sent title to {n} client(s).")

            elif cmd in ("goto", "pf"):
                sub = args.split(" ")
                if len(sub) < 4:
                    print("Usage: goto <player|all> <x> <y> <z>")
                    continue
                try:
                    x, y, z = int(sub[1]), int(sub[2]), int(sub[3])
                except ValueError:
                    print("Coordinates must be integers.")
                    continue
                n = await send_to_target(sub[0], {"type": "PATHFIND", "x": x, "y": y, "z": z})
                print(f"Sent pathfinder target to {n} client(s).")

            elif cmd == "stop":
                target = args.strip() if args else "all"
                n = await send_to_target(target, {"type": "PATHFIND_STOP"})
                print(f"Sent pathfinder stop to {n} client(s).")

            elif cmd == "status":
                target = args.strip() if args else "all"
                n = await send_to_target(target, {"type": "STATUS_REQUEST"})
                print(f"Requested status from {n} client(s).")

            elif cmd == "discord":
                sub = args.split(" ", 1)
                title = sub[0] if len(sub) > 0 else "Remote Alert"
                desc = sub[1] if len(sub) > 1 else ""
                if DISCORD_WEBHOOK:
                    send_discord_webhook(
                        DISCORD_WEBHOOK,
                        title=f"📢 {title}",
                        description=desc or "Notification from NoemtAddons Console",
                        color=0x00D2FF,
                        fields=[{"name": "Triggered By", "value": "Console", "inline": True}]
                    )
                    print("Sent Discord webhook message.")
                else:
                    print("No Discord webhook URL configured. Use 'webhook <url>' to set one.")

            elif cmd == "raw":
                sub = args.split(" ", 1)
                if len(sub) < 2:
                    print("Usage: raw <player|all> <json>")
                    continue
                try:
                    payload = json.loads(sub[1])
                    n = await send_to_target(sub[0], payload)
                    print(f"Sent raw payload to {n} client(s).")
                except json.JSONDecodeError as err:
                    print(f"Invalid JSON: {err}")

            elif cmd in ("quit", "exit"):
                print("Shutting down server...")
                sys.exit(0)

            else:
                print(f"Unknown command '{cmd}'. Type 'help' for options.")

        except (EOFError, KeyboardInterrupt):
            print("\nShutting down server.")
            break
        except Exception as e:
            print(f"Console error: {e}")


# ==============================================================================
# Server Main Entrypoint
# ==============================================================================

async def main():
    parser = argparse.ArgumentParser(description="NoemtAddons Mod-Loader, CI/CD & Control Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host address (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8765, help="Port (default: 8765)")
    parser.add_argument("--repo-dir", default=None, help="Root repository directory (default: parent of server/)")
    parser.add_argument("--jars-dir", default=None, help="Compiled JARs directory (default: build/libs/)")
    parser.add_argument("--branch", default="master", help="Git branch to track (default: master)")
    parser.add_argument("--poll-interval", type=int, default=60, help="Seconds between git pull checks (default: 60)")
    parser.add_argument("--discord-webhook", default=None, help="Discord Webhook URL for build notifications")
    parser.add_argument("--secret", default=None, help="Optional client authentication secret key")
    args = parser.parse_args()

    global AUTH_SECRET, REPO_DIR, JARS_DIR, GIT_BRANCH, POLL_INTERVAL, DISCORD_WEBHOOK
    AUTH_SECRET = args.secret
    if args.repo_dir:
        REPO_DIR = Path(args.repo_dir)
    if args.jars_dir:
        JARS_DIR = Path(args.jars_dir)
    else:
        JARS_DIR = REPO_DIR / "build" / "libs"
    GIT_BRANCH = args.branch
    POLL_INTERVAL = args.poll_interval
    if args.discord_webhook:
        DISCORD_WEBHOOK = args.discord_webhook

    logger.info(f"Starting NoemtAddons Server on http://{args.host}:{args.port}")
    logger.info(f"Repository directory: {REPO_DIR.resolve()} (Branch: '{GIT_BRANCH}')")
    logger.info(f"Mod JARs directory: {JARS_DIR.resolve()}")
    if DISCORD_WEBHOOK:
        logger.info("Discord Webhook integration is ENABLED.")
    if AUTH_SECRET:
        logger.info("Secret authentication key is ENABLED.")

    server = await asyncio.start_server(handle_connection, args.host, args.port)

    async with server:
        await asyncio.gather(
            server.serve_forever(),
            git_polling_loop(),
            interactive_console()
        )


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")
