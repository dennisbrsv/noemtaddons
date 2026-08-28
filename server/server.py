#!/usr/bin/env python3
"""
NoemtAddons Control Plane & CI/CD Mod-Loader Server
===================================================
1. Authenticated Dashboard:
   - Protected by username 'nom' and dynamically generated password
   - Bespoke obsidian telemetry UI with live client monitoring & remote commands
   - Real-time CI/CD build manager & git auto-deployer
2. HTTP Endpoints:
   - GET /loaders/noemtaddons-legit.jar
   - GET /loaders/noemtaddons-cheat.jar
   - GET /changelog
   - GET /api/version
   - POST /api/webhook (GitHub Webhook trigger)
   - POST /api/trigger-build (Dashboard authenticated build trigger)
3. WebSocket Server:
   - Real-time telemetry, client packet synchronization, remote control, and pathfinder navigation.
4. Discord Integration:
   - High-fidelity rich embeds for automated build deployment and telemetry alerts.
"""

import asyncio
import os
import sys
import json
import time
import base64
import secrets
import hashlib
import struct
import logging
import argparse
import subprocess
import urllib.request
import urllib.parse
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

# Paths & Config
REPO_DIR: Path = Path(__file__).parent.parent
JARS_DIR: Path = REPO_DIR / "build" / "libs"
AUTH_FILE: Path = Path(__file__).parent / "server_auth.json"
DISCORD_WEBHOOK: Optional[str] = os.getenv("DISCORD_WEBHOOK_URL")
GIT_BRANCH: str = "master"
POLL_INTERVAL: int = 60
AUTH_SECRET: Optional[str] = None

# Runtime State
clients: Dict[str, dict] = {}
ws_to_player: Dict[asyncio.StreamWriter, str] = {}
active_sessions: set = set()
ADMIN_USER = "nom"
ADMIN_PASSWORD = ""
IS_BUILDING: bool = False
LAST_BUILD_STATUS: str = "Ready"
LAST_BUILD_TIME: str = "N/A"
LAST_BUILD_OUTPUT: str = "No builds executed yet."


def init_auth(custom_password: Optional[str] = None):
    """Initializes or loads the persistent admin credentials."""
    global ADMIN_PASSWORD
    if custom_password:
        ADMIN_PASSWORD = custom_password
    elif AUTH_FILE.exists():
        try:
            data = json.loads(AUTH_FILE.read_text(encoding="utf-8"))
            ADMIN_PASSWORD = data.get("password", secrets.token_urlsafe(12))
        except Exception:
            ADMIN_PASSWORD = secrets.token_urlsafe(12)
    else:
        ADMIN_PASSWORD = secrets.token_urlsafe(12)
        AUTH_FILE.write_text(json.dumps({"username": ADMIN_USER, "password": ADMIN_PASSWORD}, indent=2), encoding="utf-8")

    # Generate persistent session hash
    token = hashlib.sha256(f"{ADMIN_USER}:{ADMIN_PASSWORD}".encode()).hexdigest()
    active_sessions.add(token)


def get_jar_path(flavor: str) -> Optional[Path]:
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
# High-Fidelity Discord Webhook Notifications
# ==============================================================================

def send_discord_webhook(webhook_url: str, title: str, description: str, color: int, fields: list, footer: str = "NoemtAddons CI/CD Control Plane"):
    if not webhook_url:
        return

    payload = {
        "username": "NoemtAddons CI/CD",
        "avatar_url": "https://cdn-icons-png.flaticon.com/512/919/919836.png",
        "embeds": [
            {
                "title": title,
                "description": description,
                "color": color,
                "fields": fields,
                "timestamp": datetime.utcnow().isoformat() + "Z",
                "footer": {
                    "text": footer,
                    "icon_url": "https://cdn-icons-png.flaticon.com/512/3242/3242257.png"
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
            logger.error(f"Discord Webhook delivery error: {err}")

    asyncio.get_event_loop().run_in_executor(None, _post)


# ==============================================================================
# CI/CD Auto-Builder & Git Integration
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
        try:
            subprocess.run(["git", "fetch", "origin", GIT_BRANCH], cwd=REPO_DIR, capture_output=True, text=True, check=True, timeout=25)
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
            logger.debug(f"Git check: {e}")
            return []

    @staticmethod
    async def run_build(commits: Optional[List[dict]] = None, trigger_source: str = "Git Auto-Poll") -> bool:
        global IS_BUILDING, LAST_BUILD_STATUS, LAST_BUILD_TIME, LAST_BUILD_OUTPUT
        if IS_BUILDING:
            logger.warning("Build in progress. Trigger skipped.")
            return False

        IS_BUILDING = True
        LAST_BUILD_STATUS = "Compiling..."
        start_time = time.time()
        logger.info(f"🔨 Initiating build pipeline (Source: {trigger_source})...")

        loop = asyncio.get_event_loop()

        # 1. Pull Git Updates
        if commits:
            logger.info(f"📥 Pulling {len(commits)} commits from origin/{GIT_BRANCH}...")
            pull_res = await loop.run_in_executor(
                None,
                lambda: subprocess.run(["git", "pull", "origin", GIT_BRANCH], cwd=REPO_DIR, capture_output=True, text=True)
            )
            if pull_res.returncode != 0:
                logger.error(f"Git pull failed:\n{pull_res.stderr}")

        # 2. Update In-Game Changelog
        short_hash, author, latest_msg = AutoBuilder.get_latest_commit_details()
        formatted_changelog = AutoBuilder.generate_changelog_text(short_hash, commits)
        changelog_path = Path(__file__).parent / "changelog.txt"
        changelog_path.write_text(formatted_changelog, encoding="utf-8")

        # 3. Discord Notification: Build In Progress
        if DISCORD_WEBHOOK:
            commit_lines = "\n".join([f"• `{c['hash']}` {c['message']} *(by {c['author']})*" for c in (commits or [{'hash': short_hash, 'message': latest_msg, 'author': author}])[:5]])
            send_discord_webhook(
                DISCORD_WEBHOOK,
                title=f"⚙️ Build Pipeline Triggered (`{short_hash}`)",
                description=f"**Trigger:** `{trigger_source}`\n**Branch:** `{GIT_BRANCH}`\n\n**Commit Details:**\n{commit_lines}",
                color=0xFFB830,
                fields=[
                    {"name": "Status", "value": "⏳ Executing Gradle build & remapping...", "inline": True},
                    {"name": "Triggered By", "value": trigger_source, "inline": True}
                ]
            )

        # 4. Execute Gradle Build
        gradle_cmd = ["./gradlew", "clean", "build"]
        build_res = await loop.run_in_executor(
            None,
            lambda: subprocess.run(gradle_cmd, cwd=REPO_DIR, capture_output=True, text=True)
        )

        build_duration = round(time.time() - start_time, 1)
        LAST_BUILD_TIME = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        LAST_BUILD_OUTPUT = build_res.stdout[-2000:] if build_res.stdout else (build_res.stderr[-2000:] if build_res.stderr else "")

        if build_res.returncode == 0:
            IS_BUILDING = False
            LAST_BUILD_STATUS = "Success"
            logger.info(f"✅ Build pipeline completed in {build_duration}s!")

            meta = compute_version_metadata()
            legit_size_kb = meta['endpoints']['legit']['size'] / 1024
            cheat_size_kb = meta['endpoints']['cheat']['size'] / 1024

            # 5. Discord Webhook: Build Success
            if DISCORD_WEBHOOK:
                fields = [
                    {"name": "🌿 Branch", "value": f"`{GIT_BRANCH}`", "inline": True},
                    {"name": "🔨 Commit", "value": f"`{short_hash}` ({author})", "inline": True},
                    {"name": "⏱️ Build Time", "value": f"`{build_duration}s`", "inline": True},
                    {"name": "🛡️ Legit Mod", "value": f"`{legit_size_kb:.1f} KB`", "inline": True},
                    {"name": "⚡ Cheat Mod", "value": f"`{cheat_size_kb:.1f} KB`", "inline": True},
                    {"name": "👥 Active Players", "value": f"`{len(clients)} online`", "inline": True},
                    {"name": "📥 Loader Endpoints", "value": "[Legit Loader](https://addons.noemt.dev/loaders/noemtaddons-legit.jar) • [Cheat Loader](https://addons.noemt.dev/loaders/noemtaddons-cheat.jar)", "inline": False}
                ]
                send_discord_webhook(
                    DISCORD_WEBHOOK,
                    title=f"🚀 NoemtAddons Deployed Successfully (`{short_hash}`)",
                    description=f"**New version compiled & ready for instant client sync.**\n\n> 📝 *\"{latest_msg}\"*",
                    color=0x00F5A0,
                    fields=fields
                )

            # 6. Broadcast to Connected Minecraft Players
            await send_to_target("all", {
                "type": "MESSAGE",
                "message": f"&b[NoemtAddons] &aServer updated to build &e{short_hash}&a! Restart game when ready."
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
            error_tail = "\n".join(build_res.stderr.splitlines()[-12:] if build_res.stderr else build_res.stdout.splitlines()[-12:])

            if DISCORD_WEBHOOK:
                send_discord_webhook(
                    DISCORD_WEBHOOK,
                    title=f"❌ Build Failed for `{short_hash}`",
                    description=f"**Compilation error encountered after {build_duration}s:**\n```\n{error_tail[:1000]}\n```",
                    color=0xFF3860,
                    fields=[
                        {"name": "🌿 Branch", "value": f"`{GIT_BRANCH}`", "inline": True},
                        {"name": "🔨 Commit", "value": f"`{short_hash}` by {author}", "inline": True}
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
            "§7  $noemt                 - Configuration menu",
            "§7  $noemt changelog       - View changelog",
            "§7  $stalk <ign>           - Player 3D tracer",
            "§7  $path <x> <y> <z>      - SkyHanni 3D pathfinder"
        ])
        return "\n".join(lines)


async def git_polling_loop():
    if POLL_INTERVAL <= 0:
        return
    logger.info(f"🔄 Git Auto-Pull active: polling every {POLL_INTERVAL}s on '{GIT_BRANCH}'...")
    while True:
        try:
            await asyncio.sleep(POLL_INTERVAL)
            if IS_BUILDING:
                continue
            loop = asyncio.get_event_loop()
            commits = await loop.run_in_executor(None, AutoBuilder.check_for_updates)
            if commits:
                logger.info(f"✨ New commits found on origin/{GIT_BRANCH}! Triggering build...")
                await AutoBuilder.run_build(commits=commits, trigger_source="Git Polling Daemon")
        except Exception as e:
            logger.error(f"Git polling error: {e}")


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
# HTTP Authentication & Request Handling
# ==============================================================================

def is_authenticated(headers: dict) -> bool:
    cookie_str = headers.get("cookie", "")
    for cookie in cookie_str.split(";"):
        if "=" in cookie:
            k, v = cookie.strip().split("=", 1)
            if k == "noemt_session" and v in active_sessions:
                return True
    return False


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

    # HTTP Requests
    await handle_http_request(method, path, headers, reader, writer, client_ip)


async def handle_http_request(method: str, path: str, headers: dict, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, client_ip: str):
    clean_path = path.split("?")[0].rstrip("/")
    if not clean_path:
        clean_path = "/"

    # 1. GitHub Webhook Trigger (Public / Secret)
    if method == "POST" and clean_path in ("/api/webhook", "/api/github-webhook"):
        content_len = int(headers.get("content-length", 0))
        if content_len > 0:
            await reader.readexactly(content_len)
        logger.info(f"⚡ GitHub Push Webhook from {client_ip}! Starting CI/CD build...")
        asyncio.create_task(AutoBuilder.run_build(trigger_source="GitHub Webhook"))
        send_http_response(writer, 200, "application/json", b'{"status":"Build triggered"}')
        return

    # 2. Version Metadata API (Public)
    if clean_path == "/api/version":
        meta = compute_version_metadata()
        send_http_response(writer, 200, "application/json", json.dumps(meta, indent=2).encode("utf-8"))
        return

    # 3. Changelog (Public)
    if clean_path in ("/changelog", "/api/changelog"):
        changelog_p = Path(__file__).parent / "changelog.txt"
        content = changelog_p.read_text(encoding="utf-8") if changelog_p.exists() else "§bNoemtAddons v1.0.0"
        send_http_response(writer, 200, "text/plain; charset=utf-8", content.encode("utf-8"))
        return

    # 4. Mod JAR Downloads (Public)
    if clean_path in ("/loaders/noemtaddons-legit.jar", "/download/legit", "/download/noemtaddons-legit.jar"):
        serve_jar_file(writer, "legit", client_ip)
        return

    if clean_path in ("/loaders/noemtaddons-cheat.jar", "/download/cheat", "/download/noemtaddons-cheat.jar"):
        serve_jar_file(writer, "cheat", client_ip)
        return

    # 5. Login POST Request
    if method == "POST" and clean_path == "/login":
        content_len = int(headers.get("content-length", 0))
        body = (await reader.readexactly(content_len)).decode("utf-8", errors="ignore") if content_len > 0 else ""
        form_data = urllib.parse.parse_qs(body)
        username = form_data.get("username", [""])[0].strip()
        password = form_data.get("password", [""])[0].strip()

        if username == ADMIN_USER and password == ADMIN_PASSWORD:
            session_token = hashlib.sha256(f"{username}:{password}".encode()).hexdigest()
            active_sessions.add(session_token)
            logger.info(f"🔑 Successful dashboard login from {client_ip}")
            headers_out = [
                "HTTP/1.1 302 Found",
                "Location: /",
                f"Set-Cookie: noemt_session={session_token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800",
                "Connection: close",
                "\r\n"
            ]
            writer.write("\r\n".join(headers_out).encode("utf-8"))
            writer.close()
            return
        else:
            logger.warning(f"🚫 Failed login attempt from {client_ip} (user: '{username}')")
            html = render_login_page(error="Invalid credentials. Please verify username & password.")
            send_http_response(writer, 401, "text/html; charset=utf-8", html.encode("utf-8"))
            return

    # 6. Logout GET
    if clean_path == "/logout":
        headers_out = [
            "HTTP/1.1 302 Found",
            "Location: /login",
            "Set-Cookie: noemt_session=deleted; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            "Connection: close",
            "\r\n"
        ]
        writer.write("\r\n".join(headers_out).encode("utf-8"))
        writer.close()
        return

    # 7. Authenticated Dashboard Remote Action POST
    if method == "POST" and clean_path == "/api/action":
        if not is_authenticated(headers):
            send_http_response(writer, 401, "application/json", b'{"error":"Unauthorized"}')
            return
        content_len = int(headers.get("content-length", 0))
        body = (await reader.readexactly(content_len)).decode("utf-8", errors="ignore") if content_len > 0 else ""
        form_data = urllib.parse.parse_qs(body)
        action = form_data.get("action", [""])[0]
        target = form_data.get("target", ["all"])[0]
        text = form_data.get("text", [""])[0]

        if action == "build":
            asyncio.create_task(AutoBuilder.run_build(trigger_source=f"Dashboard ({ADMIN_USER})"))
            send_http_response(writer, 200, "application/json", b'{"status":"Build triggered"}')
            return
        elif action == "msg" and text:
            await send_to_target(target, {"type": "MESSAGE", "message": text})
        elif action == "title" and text:
            await send_to_target(target, {"type": "TITLE", "title": text, "subtitle": "Alert from Noemt Control"})
        elif action == "chat" and text:
            await send_to_target(target, {"type": "CHAT", "text": text})

        headers_out = ["HTTP/1.1 302 Found", "Location: /", "Connection: close", "\r\n"]
        writer.write("\r\n".join(headers_out).encode("utf-8"))
        writer.close()
        return

    # 8. Dashboard GET Route (Protected)
    if clean_path in ("/", "/dashboard"):
        if not is_authenticated(headers):
            html = render_login_page()
            send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
            return
        html = render_dashboard_page()
        send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
        return

    if clean_path == "/login":
        html = render_login_page()
        send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
        return

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
    status_text = {200: "OK", 400: "Bad Request", 401: "Unauthorized", 404: "Not Found", 500: "Internal Server Error"}.get(status_code, "OK")
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
# Bespoke Frontend UI Renderer (Obsidian Cockpit Aesthetic)
# ==============================================================================

def render_login_page(error: Optional[str] = None) -> str:
    error_html = f'<div class="alert-error"><span>⚠️</span> {error}</div>' if error else ""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>NoemtAddons Control Plane • Security Access</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600;700&family=Space+Grotesk:wght@500;700&display=swap" rel="stylesheet">
    <style>
        :root {{
            --bg: #090b10;
            --surface: #121622;
            --surface-hover: #181e2e;
            --border: #1f273b;
            --accent: #ffb830;
            --accent-glow: rgba(255, 184, 48, 0.25);
            --mint: #00f5a0;
            --text: #edf2f7;
            --text-dim: #718096;
            --danger: #ff4757;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: var(--bg);
            color: var(--text);
            font-family: 'Space Grotesk', -apple-system, sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
            background-image: 
                radial-gradient(circle at 50% 20%, rgba(255, 184, 48, 0.08) 0%, transparent 50%),
                linear-gradient(to right, #11141f 1px, transparent 1px),
                linear-gradient(to bottom, #11141f 1px, transparent 1px);
            background-size: 100% 100%, 40px 40px, 40px 40px;
        }}
        .login-card {{
            width: 100%;
            max-width: 420px;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 36px;
            box-shadow: 0 20px 50px rgba(0, 0, 0, 0.7), 0 0 0 1px rgba(255, 255, 255, 0.04);
            position: relative;
            overflow: hidden;
        }}
        .login-card::before {{
            content: '';
            position: absolute;
            top: 0; left: 0; right: 0; height: 3px;
            background: linear-gradient(90deg, var(--accent), var(--mint));
        }}
        .brand {{
            display: flex;
            align-items: center;
            gap: 12px;
            margin-bottom: 28px;
        }}
        .brand-icon {{
            width: 42px;
            height: 42px;
            background: rgba(255, 184, 48, 0.12);
            border: 1px solid rgba(255, 184, 48, 0.3);
            border-radius: 10px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 20px;
        }}
        .brand h1 {{
            font-size: 20px;
            font-weight: 700;
            letter-spacing: -0.5px;
        }}
        .brand p {{
            font-size: 12px;
            color: var(--text-dim);
            font-family: 'JetBrains Mono', monospace;
        }}
        .alert-error {{
            background: rgba(255, 71, 87, 0.12);
            border: 1px solid rgba(255, 71, 87, 0.3);
            color: #ff6b81;
            padding: 12px 16px;
            border-radius: 8px;
            font-size: 13px;
            margin-bottom: 20px;
            display: flex;
            align-items: center;
            gap: 8px;
        }}
        .form-group {{
            margin-bottom: 20px;
        }}
        label {{
            display: block;
            font-size: 12px;
            font-weight: 600;
            color: var(--text-dim);
            text-transform: uppercase;
            letter-spacing: 0.8px;
            margin-bottom: 8px;
            font-family: 'JetBrains Mono', monospace;
        }}
        input {{
            width: 100%;
            background: #0b0e17;
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 14px 16px;
            color: #fff;
            font-family: 'JetBrains Mono', monospace;
            font-size: 14px;
            transition: all 0.2s ease;
        }}
        input:focus {{
            outline: none;
            border-color: var(--accent);
            box-shadow: 0 0 0 3px var(--accent-glow);
        }}
        .btn-submit {{
            width: 100%;
            background: var(--accent);
            color: #0b0e17;
            border: none;
            border-radius: 10px;
            padding: 14px;
            font-size: 14px;
            font-weight: 700;
            font-family: 'Space Grotesk', sans-serif;
            cursor: pointer;
            transition: all 0.2s ease;
            margin-top: 8px;
        }}
        .btn-submit:hover {{
            background: #ffa800;
            transform: translateY(-1px);
            box-shadow: 0 8px 20px var(--accent-glow);
        }}
        .footer-note {{
            margin-top: 24px;
            text-align: center;
            font-size: 12px;
            color: var(--text-dim);
            font-family: 'JetBrains Mono', monospace;
        }}
    </style>
</head>
<body>
    <div class="login-card">
        <div class="brand">
            <div class="brand-icon">⚡</div>
            <div>
                <h1>NoemtAddons Control</h1>
                <p>v1.0.0 • Hypixel Skyblock Suite</p>
            </div>
        </div>
        {error_html}
        <form method="POST" action="/login">
            <div class="form-group">
                <label>Operator ID</label>
                <input type="text" name="username" placeholder="nom" value="nom" required autofocus>
            </div>
            <div class="form-group">
                <label>Access Key / Password</label>
                <input type="password" name="password" placeholder="Enter generated server key" required>
            </div>
            <button type="submit" class="btn-submit">Authenticate Terminal →</button>
        </form>
        <div class="footer-note">
            Check terminal server logs for generated key.
        </div>
    </div>
</body>
</html>"""


def render_dashboard_page() -> str:
    meta = compute_version_metadata()
    connected_count = len(clients)
    short_hash, author, msg = AutoBuilder.get_latest_commit_details()

    player_rows = ""
    if clients:
        for name, info in clients.items():
            player_rows += f"""
            <tr>
                <td><b><span class="pulse-dot"></span> {name}</b></td>
                <td><code>{info['uuid'][:12]}...</code></td>
                <td>{info['ip']}</td>
                <td><span class="tag tag-legit">v{info['version']}</span></td>
                <td>{info['connected_at']}</td>
            </tr>
            """
    else:
        player_rows = '<tr><td colspan="5" style="text-align:center; padding: 28px; color: var(--text-dim);"><i>No active Minecraft clients currently connected to telemetry plane.</i></td></tr>'

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>NoemtAddons • Operator Dashboard</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600;700&family=Space+Grotesk:wght@500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {{
            --bg: #0b0d13;
            --surface: #131722;
            --surface-hover: #191f2e;
            --border: #1e2638;
            --border-light: rgba(255, 255, 255, 0.08);
            --accent: #ffb830;
            --accent-glow: rgba(255, 184, 48, 0.2);
            --mint: #00f5a0;
            --mint-glow: rgba(0, 245, 160, 0.2);
            --cyan: #00d2ff;
            --danger: #ff4757;
            --text: #edf2f7;
            --text-dim: #78859b;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: var(--bg);
            color: var(--text);
            font-family: 'Space Grotesk', -apple-system, sans-serif;
            min-height: 100vh;
            padding: 32px 40px;
        }}
        header {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 32px;
            padding-bottom: 20px;
            border-bottom: 1px solid var(--border);
        }}
        .header-brand {{
            display: flex;
            align-items: center;
            gap: 14px;
        }}
        .header-logo {{
            width: 44px;
            height: 44px;
            background: rgba(255, 184, 48, 0.12);
            border: 1px solid rgba(255, 184, 48, 0.3);
            border-radius: 12px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 22px;
        }}
        .header-title h1 {{
            font-size: 22px;
            font-weight: 700;
            letter-spacing: -0.5px;
        }}
        .header-title p {{
            font-size: 12px;
            color: var(--text-dim);
            font-family: 'JetBrains Mono', monospace;
        }}
        .header-controls {{
            display: flex;
            align-items: center;
            gap: 12px;
        }}
        .btn {{
            display: inline-flex;
            align-items: center;
            gap: 8px;
            background: var(--surface);
            color: var(--text);
            border: 1px solid var(--border);
            padding: 10px 18px;
            border-radius: 10px;
            font-size: 13px;
            font-weight: 600;
            font-family: 'Space Grotesk', sans-serif;
            text-decoration: none;
            cursor: pointer;
            transition: all 0.2s ease;
        }}
        .btn:hover {{
            background: var(--surface-hover);
            border-color: var(--border-light);
            transform: translateY(-1px);
        }}
        .btn-accent {{
            background: var(--accent);
            color: #0b0d13;
            border: none;
            font-weight: 700;
        }}
        .btn-accent:hover {{
            background: #ffa800;
            box-shadow: 0 4px 16px var(--accent-glow);
        }}
        .btn-danger {{
            background: rgba(255, 71, 87, 0.15);
            color: #ff6b81;
            border-color: rgba(255, 71, 87, 0.3);
        }}
        .grid-stats {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
            gap: 20px;
            margin-bottom: 32px;
        }}
        .stat-card {{
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 14px;
            padding: 22px;
            position: relative;
            overflow: hidden;
        }}
        .stat-label {{
            font-size: 12px;
            font-weight: 600;
            color: var(--text-dim);
            text-transform: uppercase;
            letter-spacing: 0.8px;
            font-family: 'JetBrains Mono', monospace;
            margin-bottom: 8px;
        }}
        .stat-val {{
            font-size: 26px;
            font-weight: 700;
            letter-spacing: -0.5px;
            display: flex;
            align-items: center;
            gap: 8px;
        }}
        .grid-main {{
            display: grid;
            grid-template-columns: 2fr 1fr;
            gap: 24px;
        }}
        @media (max-width: 1024px) {{
            .grid-main {{ grid-template-columns: 1fr; }}
            body {{ padding: 20px; }}
        }}
        .card {{
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 14px;
            padding: 26px;
            margin-bottom: 24px;
        }}
        .card-header {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 20px;
            padding-bottom: 12px;
            border-bottom: 1px solid var(--border);
        }}
        .card-header h2 {{
            font-size: 16px;
            font-weight: 700;
            display: flex;
            align-items: center;
            gap: 10px;
        }}
        table {{
            width: 100%;
            border-collapse: collapse;
        }}
        th, td {{
            padding: 14px 16px;
            text-align: left;
            border-bottom: 1px solid var(--border);
            font-size: 13px;
        }}
        th {{
            color: var(--text-dim);
            font-family: 'JetBrains Mono', monospace;
            font-size: 11px;
            text-transform: uppercase;
            letter-spacing: 0.8px;
        }}
        code {{
            background: #0b0e17;
            border: 1px solid rgba(255,255,255,0.06);
            padding: 3px 8px;
            border-radius: 6px;
            font-family: 'JetBrains Mono', monospace;
            font-size: 12px;
            color: var(--accent);
        }}
        .pulse-dot {{
            display: inline-block;
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: var(--mint);
            box-shadow: 0 0 0 0 var(--mint-glow);
            animation: pulse 2s infinite;
        }}
        @keyframes pulse {{
            0% {{ box-shadow: 0 0 0 0 var(--mint-glow); }}
            70% {{ box-shadow: 0 0 0 8px transparent; }}
            100% {{ box-shadow: 0 0 0 0 transparent; }}
        }}
        .tag {{
            display: inline-block;
            padding: 3px 8px;
            border-radius: 6px;
            font-size: 11px;
            font-weight: 700;
            font-family: 'JetBrains Mono', monospace;
        }}
        .tag-legit {{ background: rgba(0, 245, 160, 0.12); color: var(--mint); border: 1px solid rgba(0, 245, 160, 0.25); }}
        .tag-cheat {{ background: rgba(255, 71, 87, 0.12); color: var(--danger); border: 1px solid rgba(255, 71, 87, 0.25); }}
        .form-control {{
            width: 100%;
            background: #0b0e17;
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 10px 14px;
            color: #fff;
            font-family: 'JetBrains Mono', monospace;
            font-size: 13px;
            margin-bottom: 12px;
        }}
    </style>
</head>
<body>
    <header>
        <div class="header-brand">
            <div class="header-logo">⚡</div>
            <div class="header-title">
                <h1>NoemtAddons Control Plane</h1>
                <p>Operator: <b>nom</b> • Environment: <b>Production</b></p>
            </div>
        </div>
        <div class="header-controls">
            <form method="POST" action="/api/action" style="display:inline;">
                <input type="hidden" name="action" value="build">
                <button type="submit" class="btn btn-accent">🔨 Trigger Build & Deploy</button>
            </form>
            <a href="/changelog" class="btn" target="_blank">📜 Changelog</a>
            <a href="/logout" class="btn btn-danger">🔒 Logout</a>
        </div>
    </header>

    <div class="grid-stats">
        <div class="stat-card">
            <div class="stat-label">Active Connections</div>
            <div class="stat-val"><span class="pulse-dot"></span> {connected_count} <span style="font-size:14px; color:var(--text-dim); font-weight:normal;">players online</span></div>
        </div>
        <div class="stat-card">
            <div class="stat-label">Git Deployment</div>
            <div class="stat-val"><code>{short_hash}</code> <span class="tag tag-legit">branch: {GIT_BRANCH}</span></div>
        </div>
        <div class="stat-card">
            <div class="stat-label">Build Engine Status</div>
            <div class="stat-val" style="color: {'var(--mint)' if LAST_BUILD_STATUS == 'Success' or LAST_BUILD_STATUS == 'Ready' else 'var(--danger)'};">{LAST_BUILD_STATUS}</div>
        </div>
        <div class="stat-card">
            <div class="stat-label">Last Auto-Build</div>
            <div class="stat-val" style="font-size: 16px; font-family:'JetBrains Mono',monospace;">{LAST_BUILD_TIME}</div>
        </div>
    </div>

    <div class="grid-main">
        <div>
            <div class="card">
                <div class="card-header">
                    <h2>👥 Active Telemetry Stream</h2>
                </div>
                <table>
                    <thead>
                        <tr><th>Player</th><th>Session UUID</th><th>IP Address</th><th>Client Build</th><th>Connected At</th></tr>
                    </thead>
                    <tbody>
                        {player_rows}
                    </tbody>
                </table>
            </div>

            <div class="card">
                <div class="card-header">
                    <h2>📦 Distributable Mod Loaders & Builds</h2>
                </div>
                <table>
                    <thead>
                        <tr><th>Flavor</th><th>Endpoint</th><th>Payload Size</th><th>SHA-256 Checksum</th><th>Action</th></tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td><span class="tag tag-legit">LEGIT</span></td>
                            <td><code>/loaders/noemtaddons-legit.jar</code></td>
                            <td>{meta['endpoints']['legit']['size'] / 1024:.1f} KB</td>
                            <td><small>{meta['endpoints']['legit']['sha256'][:16]}...</small></td>
                            <td><a href="/loaders/noemtaddons-legit.jar" class="btn" style="padding:4px 10px; font-size:12px;">Download</a></td>
                        </tr>
                        <tr>
                            <td><span class="tag tag-cheat">CHEAT</span></td>
                            <td><code>/loaders/noemtaddons-cheat.jar</code></td>
                            <td>{meta['endpoints']['cheat']['size'] / 1024:.1f} KB</td>
                            <td><small>{meta['endpoints']['cheat']['sha256'][:16]}...</small></td>
                            <td><a href="/loaders/noemtaddons-cheat.jar" class="btn" style="padding:4px 10px; font-size:12px;">Download</a></td>
                        </tr>
                    </tbody>
                </table>
            </div>
        </div>

        <div>
            <div class="card">
                <div class="card-header">
                    <h2>🎮 In-Game Remote Control</h2>
                </div>
                <form method="POST" action="/api/action">
                    <label style="font-size:11px; color:var(--text-dim); display:block; margin-bottom:6px; font-family:'JetBrains Mono',monospace;">ACTION TYPE</label>
                    <select name="action" class="form-control">
                        <option value="msg">💬 Chat Message</option>
                        <option value="title">🔔 Screen Title Alert</option>
                        <option value="chat">⚡ Execute Client Command</option>
                    </select>

                    <label style="font-size:11px; color:var(--text-dim); display:block; margin-bottom:6px; font-family:'JetBrains Mono',monospace;">TARGET PLAYER</label>
                    <input type="text" name="target" class="form-control" value="all" placeholder="Player name or 'all'">

                    <label style="font-size:11px; color:var(--text-dim); display:block; margin-bottom:6px; font-family:'JetBrains Mono',monospace;">MESSAGE / COMMAND TEXT</label>
                    <input type="text" name="text" class="form-control" placeholder="Text or command (e.g. &warp hub)" required>

                    <button type="submit" class="btn btn-accent" style="width:100%; justify-content:center; padding:12px;">Send Signal →</button>
                </form>
            </div>

            <div class="card">
                <div class="card-header">
                    <h2>📋 Git CI/CD Information</h2>
                </div>
                <div style="font-family:'JetBrains Mono',monospace; font-size:12px; line-height:1.6; color:var(--text-dim);">
                    <p style="margin-bottom:8px;"><b>Latest Commit:</b> <span style="color:#fff;">{short_hash}</span></p>
                    <p style="margin-bottom:8px;"><b>Author:</b> <span style="color:#fff;">{author}</span></p>
                    <p style="margin-bottom:8px;"><b>Message:</b> <span style="color:#fff;">{msg}</span></p>
                    <p><b>Auto-Polling:</b> <span style="color:var(--mint);">{POLL_INTERVAL}s interval</span></p>
                </div>
            </div>
        </div>
    </div>
</body>
</html>"""


# ==============================================================================
# WebSocket Session Handler
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
                        logger.warning(f"Auth failed for {player_name} ({client_ip})")
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
    if not sys.stdin or not sys.stdin.isatty():
        return

    await asyncio.sleep(1)
    print("\n" + "=" * 65)
    print(" 🚀 NoemtAddons Control Plane & CI/CD Server Ready")
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
  build / update                      - Trigger git pull & rebuild
  creds                               - Print current dashboard login credentials
  webhook <url>                       - Set or test Discord webhook URL
  list                                - List connected players
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

            elif cmd in ("creds", "pass", "login"):
                print(f"\n🔐 Dashboard Credentials:\n  Username: {ADMIN_USER}\n  Password: {ADMIN_PASSWORD}\n")

            elif cmd == "webhook":
                global DISCORD_WEBHOOK
                if args:
                    DISCORD_WEBHOOK = args.strip()
                    print(f"Updated Discord webhook URL: {DISCORD_WEBHOOK}")
                    send_discord_webhook(
                        DISCORD_WEBHOOK,
                        title="🔔 Webhook Connected",
                        description="Discord webhook integration is successfully linked to NoemtAddons server!",
                        color=0x00F5A0,
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
    parser = argparse.ArgumentParser(description="NoemtAddons Control Plane & CI/CD Mod Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host address (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8765, help="Port (default: 8765)")
    parser.add_argument("--repo-dir", default=None, help="Root repository directory (default: parent of server/)")
    parser.add_argument("--jars-dir", default=None, help="Compiled JARs directory (default: build/libs/)")
    parser.add_argument("--branch", default="master", help="Git branch to track (default: master)")
    parser.add_argument("--poll-interval", type=int, default=60, help="Seconds between git pull checks (default: 60)")
    parser.add_argument("--discord-webhook", default=None, help="Discord Webhook URL for build notifications")
    parser.add_argument("--admin-pass", default=None, help="Custom admin password for dashboard")
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

    init_auth(args.admin_pass)

    print("\n" + "=" * 65)
    print(" 🔒 DASHBOARD SECURITY CREDENTIALS")
    print(f"    URL:      http://{args.host}:{args.port}/")
    print(f"    Username: {ADMIN_USER}")
    print(f"    Password: {ADMIN_PASSWORD}")
    print("=" * 65 + "\n")

    logger.info(f"Starting NoemtAddons Server on http://{args.host}:{args.port}")
    logger.info(f"Repository: {REPO_DIR.resolve()} (Branch: '{GIT_BRANCH}')")
    logger.info(f"Mod JARs: {JARS_DIR.resolve()}")
    if DISCORD_WEBHOOK:
        logger.info("Discord Webhook integration is ENABLED.")

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
