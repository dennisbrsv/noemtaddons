#!/usr/bin/env python3
"""
Noemt Cloud Console & CI/CD Mod-Loader Server
============================================
Google Cloud / Material Design 3 Control Plane:
- Authenticated operator access (nom:<generated_password>)
- Live telemetry stream & remote commands
- Remote client emergency failsafe (close game remotely)
- Dynamic mod loader sync & automated git pull CI/CD
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
import email.utils
import urllib.request
import urllib.parse
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional, Tuple, List

logging.basicConfig(
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
    level=logging.INFO
)
logger = logging.getLogger("NoemtCloud")

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# Paths & Config
REPO_DIR: Path = Path(__file__).parent.parent
JARS_DIR: Path = REPO_DIR / "build" / "libs"
DB_PATH: Path = Path(__file__).parent / "server.db"
DISCORD_BOT_TOKEN: Optional[str] = os.getenv("DISCORD_BOT_TOKEN")
DISCORD_CHANNEL_ID: Optional[str] = os.getenv("DISCORD_CHANNEL_ID")
GIT_BRANCH: str = "master"
POLL_INTERVAL: int = 0
AUTH_SECRET: Optional[str] = None

# Runtime State
clients: Dict[str, dict] = {}
ws_to_player: Dict[asyncio.StreamWriter, str] = {}
IS_BUILDING: bool = False
LAST_BUILD_STATUS: str = "Healthy"
LAST_BUILD_TIME: str = "N/A"
LAST_BUILD_OUTPUT: str = "No builds executed yet."


# ==============================================================================
# Database & Authentication Layer (SQLite)
# ==============================================================================

def get_db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    """Initializes the SQLite database schema for users and sessions."""
    with get_db() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                password_salt TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                expires_at REAL NOT NULL
            )
        """)
        conn.commit()


def hash_password(password: str, salt: Optional[str] = None) -> Tuple[str, str]:
    if not salt:
        salt = secrets.token_hex(16)
    key = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt.encode("utf-8"),
        200_000
    )
    return key.hex(), salt


def verify_password(password: str, expected_hash: str, salt: str) -> bool:
    computed_hash, _ = hash_password(password, salt)
    return secrets.compare_digest(computed_hash, expected_hash)


def has_admin_user() -> bool:
    with get_db() as conn:
        row = conn.execute("SELECT COUNT(*) as count FROM users").fetchone()
        return (row["count"] if row else 0) > 0


def get_admin_username() -> str:
    with get_db() as conn:
        row = conn.execute("SELECT username FROM users ORDER BY id ASC LIMIT 1").fetchone()
        return row["username"] if row else "nom"


def register_admin(username: str, password: str) -> Tuple[bool, str]:
    """Registers the initial admin user. Strictly allowed ONLY ONCE ever."""
    username = username.strip()
    password = password.strip()
    if len(username) < 3:
        return False, "Username must be at least 3 characters long."
    if len(password) < 6:
        return False, "Password must be at least 6 characters long."

    with get_db() as conn:
        row = conn.execute("SELECT COUNT(*) as count FROM users").fetchone()
        if row and row["count"] > 0:
            return False, "Registration is permanently locked. An operator account already exists."

        pwd_hash, salt = hash_password(password)
        try:
            conn.execute(
                "INSERT INTO users (username, password_hash, password_salt) VALUES (?, ?, ?)",
                (username, pwd_hash, salt)
            )
            conn.commit()
            return True, "Success"
        except sqlite3.IntegrityError:
            return False, "Username already taken."


def verify_login(username: str, password: str) -> bool:
    with get_db() as conn:
        row = conn.execute(
            "SELECT password_hash, password_salt FROM users WHERE username = ?",
            (username.strip(),)
        ).fetchone()
        if not row:
            return False
        return verify_password(password, row["password_hash"], row["password_salt"])


def create_session(username: str) -> str:
    token = secrets.token_urlsafe(32)
    expires_at = time.time() + (7 * 24 * 3600)  # 7 days
    with get_db() as conn:
        conn.execute(
            "INSERT INTO sessions (token, username, expires_at) VALUES (?, ?, ?)",
            (token, username, expires_at)
        )
        conn.commit()
    return token


def get_authenticated_user(headers: dict) -> Optional[str]:
    cookie_str = headers.get("cookie", "")
    token = None
    for cookie in cookie_str.split(";"):
        if "=" in cookie:
            k, v = cookie.strip().split("=", 1)
            if k == "noemt_session":
                token = v
                break
    if not token:
        return None

    now = time.time()
    with get_db() as conn:
        row = conn.execute(
            "SELECT username, expires_at FROM sessions WHERE token = ?",
            (token,)
        ).fetchone()
        if row:
            if row["expires_at"] > now:
                return row["username"]
            else:
                conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
                conn.commit()
    return None


def is_authenticated(headers: dict) -> bool:
    return get_authenticated_user(headers) is not None


def destroy_session(headers: dict):
    cookie_str = headers.get("cookie", "")
    token = None
    for cookie in cookie_str.split(";"):
        if "=" in cookie:
            k, v = cookie.strip().split("=", 1)
            if k == "noemt_session":
                token = v
                break
    if token:
        with get_db() as conn:
            conn.execute("DELETE FROM sessions WHERE token = ?", (token,))
            conn.commit()


def get_project_version() -> str:
    props_file = REPO_DIR / "../gradle.properties"
    if props_file.exists():
        try:
            for line in props_file.read_text(encoding="utf-8").splitlines():
                if line.strip().startswith("version="):
                    return line.split("=", 1)[1].strip()
        except Exception:
            pass
    return "1.0.2"


def get_jar_path(flavor: str = "mod") -> Optional[Path]:
    ver = get_project_version()
    candidates = [
        JARS_DIR / f"noemtaddons-{ver}.jar",
        JARS_DIR / "noemtaddons.jar",
        JARS_DIR / f"noemtaddons-{ver}-cheat.jar",
        JARS_DIR / "noemtaddons-cheat.jar",
        Path(__file__).parent / "jars" / "noemtaddons.jar",
        Path(__file__).parent / "jars" / f"noemtaddons-{ver}.jar",
    ]
    for p in candidates:
        if p.exists() and p.is_file() and p.stat().st_size > 0:
            return p

    for d in (JARS_DIR, Path(__file__).parent / "jars"):
        if d.exists():
            matches = [
                p for p in d.glob("*.jar")
                if p.is_file() and p.stat().st_size > 0 and "loader" not in p.name.lower() and "sources" not in p.name.lower()
            ]
            if matches:
                return max(matches, key=lambda x: x.stat().st_mtime)
    return None


def get_loader_jar_path(flavor: str = "loader") -> Optional[Path]:
    ver = get_project_version()
    candidates = [
        JARS_DIR / f"noemtaddons-loader-{ver}.jar",
        JARS_DIR / "noemtaddons-loader.jar",
        JARS_DIR / f"noemtaddons-cheat-loader-{ver}.jar",
        JARS_DIR / "noemtaddons-cheat-loader.jar",
        Path(__file__).parent / "jars" / "noemtaddons-loader.jar",
        Path(__file__).parent / "jars" / f"noemtaddons-loader-{ver}.jar",
    ]
    for p in candidates:
        if p.exists() and p.is_file() and p.stat().st_size > 0:
            return p

    for d in (JARS_DIR, Path(__file__).parent / "jars"):
        if d.exists():
            matches = [
                p for p in d.glob("*loader*.jar")
                if p.is_file() and p.stat().st_size > 0 and "sources" not in p.name.lower()
            ]
            if matches:
                return max(matches, key=lambda x: x.stat().st_mtime)
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
    mod_p = get_jar_path("mod")
    loader_p = get_loader_jar_path("loader")

    mod_info = get_file_info(mod_p) if mod_p else {"exists": False}
    loader_info = get_file_info(loader_p) if loader_p else {"exists": False}

    return {
        "version": get_project_version(),
        "timestamp": int(datetime.now().timestamp()),
        "last_build": LAST_BUILD_TIME,
        "build_status": LAST_BUILD_STATUS,
        "endpoints": {
            "mod": {
                "url": "https://addons.noemt.dev/loaders/noemtaddons.jar",
                "filename": mod_p.name if mod_p else "noemtaddons.jar",
                "sha256": mod_info.get("sha256", ""),
                "size": mod_info.get("size", 0),
                "modified": mod_info.get("modified", "")
            },
            "loader": {
                "url": "https://addons.noemt.dev/download/loader",
                "filename": loader_p.name if loader_p else "noemtaddons-loader.jar",
                "sha256": loader_info.get("sha256", ""),
                "size": loader_info.get("size", 0),
                "modified": loader_info.get("modified", "")
            }
        }
    }


# ==============================================================================
# Discord Bot Notifications (Direct REST API)
# ==============================================================================

def send_discord_bot_notification(title: str, description: str, color: int, fields: Optional[List[dict]] = None, footer: str = "Noemt Cloud Bot"):
    """Dispatches notifications through the Discord Bot API to the configured channel."""
    if not DISCORD_BOT_TOKEN or not DISCORD_CHANNEL_ID:
        return

    url = f"https://discord.com/api/v10/channels/{DISCORD_CHANNEL_ID}/messages"
    payload = {
        "embeds": [
            {
                "title": title,
                "description": description,
                "color": color,
                "fields": fields or [],
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
                url,
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "Authorization": f"Bot {DISCORD_BOT_TOKEN}",
                    "Content-Type": "application/json",
                    "User-Agent": "NoemtCloud-BotClient/1.0"
                },
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=8) as resp:
                pass
        except Exception as err:
            logger.warning(f"Discord Bot notification delivery error: {err}")

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
        LAST_BUILD_STATUS = "Building..."
        start_time = time.time()
        logger.info(f"🔨 Initiating build pipeline (Source: {trigger_source})...")

        loop = asyncio.get_event_loop()

        # 1. Pull Git Updates
        logger.info(f"📥 Pulling latest commits from origin/{GIT_BRANCH}...")
        pull_res = await loop.run_in_executor(
            None,
            lambda: subprocess.run(["git", "pull", "origin", GIT_BRANCH], cwd=REPO_DIR, capture_output=True, text=True)
        )
        if pull_res.returncode == 0:
            logger.info(f"📥 Git pull succeeded: {pull_res.stdout.strip()}")
        else:
            logger.warning(f"⚠️ Git pull notice: {pull_res.stderr.strip() or pull_res.stdout.strip()}")

        # 2. Update In-Game Changelog
        short_hash, author, latest_msg = AutoBuilder.get_latest_commit_details()
        formatted_changelog = AutoBuilder.generate_changelog_text(short_hash, commits)
        changelog_path = Path(__file__).parent / "changelog.txt"
        changelog_path.write_text(formatted_changelog, encoding="utf-8")

        # 3. Discord Notification
        commit_lines = "\n".join([f"• `{c['hash']}` {c['message']} *(by {c['author']})*" for c in (commits or [{'hash': short_hash, 'message': latest_msg, 'author': author}])[:5]])
        if SERVER_INSTANCE and SERVER_INSTANCE.bot and hasattr(SERVER_INSTANCE.bot, "dispatch"):
            SERVER_INSTANCE.bot.dispatch("build_started", trigger_source, GIT_BRANCH, short_hash, commit_lines)
        elif DISCORD_BOT_TOKEN and DISCORD_CHANNEL_ID:
            send_discord_bot_notification(
                title=f"Build Triggered (`{short_hash}`)",
                description=f"**Trigger:** `{trigger_source}`\n**Branch:** `{GIT_BRANCH}`\n\n**Commit Details:**\n{commit_lines}",
                color=0xFBBC04,
                fields=[
                    {"name": "Status", "value": "⏳ Executing Gradle build...", "inline": True},
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
            LAST_BUILD_STATUS = "Healthy"
            logger.info(f"✅ Build pipeline completed in {build_duration}s!")

            meta = compute_version_metadata()
            mod_size_kb = meta['endpoints']['mod']['size'] / 1024

            if SERVER_INSTANCE and SERVER_INSTANCE.bot and hasattr(SERVER_INSTANCE.bot, "dispatch"):
                SERVER_INSTANCE.bot.dispatch("build_completed", True, build_duration, short_hash, author, latest_msg, mod_size_kb)
            elif DISCORD_BOT_TOKEN and DISCORD_CHANNEL_ID:
                fields = [
                    {"name": "🌿 Branch", "value": f"`{GIT_BRANCH}`", "inline": True},
                    {"name": "Commit", "value": f"`{short_hash}` ({author})", "inline": True},
                    {"name": "Build Time", "value": f"`{build_duration}s`", "inline": True},
                    {"name": "Mod Size", "value": f"`{mod_size_kb:.1f} KB`", "inline": True},
                ]
                send_discord_bot_notification(
                    title=f"Deployment Succeeded (`{short_hash}`)",
                    description=f"**New version deployed**\n\n> 📝 *\"{latest_msg}\"*",
                    color=0x34A853,
                    fields=fields
                )

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

            if SERVER_INSTANCE and SERVER_INSTANCE.bot and hasattr(SERVER_INSTANCE.bot, "dispatch"):
                SERVER_INSTANCE.bot.dispatch("build_completed", False, build_duration, short_hash, author, latest_msg, 0.0, error_tail)
            elif DISCORD_BOT_TOKEN and DISCORD_CHANNEL_ID:
                send_discord_bot_notification(
                    title=f"❌ Build Failed for `{short_hash}`",
                    description=f"**Compilation error encountered after {build_duration}s:**\n```\n{error_tail[:1000]}\n```",
                    color=0xEA4335,
                    fields=[
                        {"name": "🌿 Branch", "value": f"`{GIT_BRANCH}`", "inline": True},
                        {"name": "Commit", "value": f"`{short_hash}` by {author}", "inline": True}
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
            "§7  /noemt                 - Configuration menu",
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
# WebSocket Frame Handling (RFC 6455)
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
# Connection & Request Handling
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

    # HTTP Requests
    await handle_http_request(method, path, headers, reader, writer, client_ip)


async def handle_http_request(method: str, path: str, headers: dict, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, client_ip: str):
    clean_path = path.split("?")[0].rstrip("/")
    if not clean_path:
        clean_path = "/"

    # 1. Instant GitHub Webhook / Deploy API Trigger (No polling needed)
    if clean_path in ("/api/webhook", "/api/github-webhook", "/api/webhook/github", "/webhook", "/deploy", "/api/build", "/build"):
        content_len = int(headers.get("content-length", 0))
        if content_len > 0:
            await reader.readexactly(content_len)
        logger.info(f"⚡ Instant Webhook / Build Trigger from {client_ip}! Pulling origin/{GIT_BRANCH} & building...")
        asyncio.create_task(AutoBuilder.run_build(trigger_source=f"Instant Webhook ({client_ip})"))
        send_http_response(writer, 200, "application/json", b'{"status":"Instant build pipeline initiated","success":true}')
        return

    # 2. Version Metadata API
    if clean_path == "/api/version":
        meta = compute_version_metadata()
        send_http_response(writer, 200, "application/json", json.dumps(meta, indent=2).encode("utf-8"))
        return

    # 3. Changelog
    if clean_path in ("/changelog", "/api/changelog"):
        changelog_p = Path(__file__).parent / "changelog.txt"
        content = changelog_p.read_text(encoding="utf-8") if changelog_p.exists() else "§bNoemtAddons v1.0.1"
        send_http_response(writer, 200, "text/plain; charset=utf-8", content.encode("utf-8"))
        return

    # 4. Mod Payload JAR Downloads (Requested by loaders on game startup)
    if clean_path in ("/loaders/noemtaddons.jar", "/download/mod", "/download/noemtaddons.jar",
                      "/loaders/noemtaddons-cheat.jar", "/download/cheat", "/download/noemtaddons-cheat.jar"):
        serve_jar_file(writer, "mod", headers, client_ip)
        return

    # 5. Bootstrap Loader JAR Downloads (The bootstrap loader given to users)
    if clean_path in ("/download/loader", "/download/loaders/cheat", "/download/noemtaddons-loader.jar",
                      "/loaders/noemtaddons-loader.jar", "/loaders/cheat-loader.jar"):
        serve_loader_stub_file(writer, "loader", headers, client_ip)
        return

    # 6. Initial Registration (First-time setup - strictly single-use ever)
    if clean_path == "/register":
        if has_admin_user():
            if method == "POST":
                send_http_response(writer, 403, "application/json", b'{"error":"Registration is permanently locked. An admin account already exists."}')
            else:
                headers_out = ["HTTP/1.1 302 Found", "Location: /login", "Connection: close", "\r\n"]
                writer.write("\r\n".join(headers_out).encode("utf-8"))
                writer.close()
            return

        if method == "POST":
            content_len = int(headers.get("content-length", 0))
            body = (await reader.readexactly(content_len)).decode("utf-8", errors="ignore") if content_len > 0 else ""
            form_data = urllib.parse.parse_qs(body)
            username = form_data.get("username", [""])[0].strip()
            password = form_data.get("password", [""])[0].strip()
            confirm = form_data.get("confirm_password", [""])[0].strip()

            if password != confirm:
                html = render_register_page(error="Passwords do not match. Please re-enter.")
                send_http_response(writer, 400, "text/html; charset=utf-8", html.encode("utf-8"))
                return

            ok, err_msg = register_admin(username, password)
            if not ok:
                html = render_register_page(error=err_msg)
                send_http_response(writer, 400, "text/html; charset=utf-8", html.encode("utf-8"))
                return

            session_token = create_session(username)
            logger.info(f"🎉 Initial Operator account successfully created: '{username}' from {client_ip}")
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
            html = render_register_page()
            send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
            return

    # If no admin account exists yet, automatically route any UI request to /register
    if not has_admin_user() and clean_path in ("/", "/dashboard", "/login"):
        if method == "GET":
            html = render_register_page()
            send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
            return

    # 7. Login POST Request
    if method == "POST" and clean_path == "/login":
        if not has_admin_user():
            headers_out = ["HTTP/1.1 302 Found", "Location: /register", "Connection: close", "\r\n"]
            writer.write("\r\n".join(headers_out).encode("utf-8"))
            writer.close()
            return

        content_len = int(headers.get("content-length", 0))
        body = (await reader.readexactly(content_len)).decode("utf-8", errors="ignore") if content_len > 0 else ""
        form_data = urllib.parse.parse_qs(body)
        username = form_data.get("username", [""])[0].strip()
        password = form_data.get("password", [""])[0].strip()

        if verify_login(username, password):
            session_token = create_session(username)
            logger.info(f"🔑 Successful Google Cloud console login for '{username}' from {client_ip}")
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
            html = render_login_page(error="Invalid credentials. Verify your Operator ID and Password.")
            send_http_response(writer, 401, "text/html; charset=utf-8", html.encode("utf-8"))
            return

    # 8. Logout GET
    if clean_path == "/logout":
        destroy_session(headers)
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

    # 9. Authenticated Dashboard Remote Action POST
    if method == "POST" and clean_path == "/api/action":
        current_user = get_authenticated_user(headers)
        if not current_user:
            send_http_response(writer, 401, "application/json", b'{"error":"Unauthorized"}')
            return
        content_len = int(headers.get("content-length", 0))
        body = (await reader.readexactly(content_len)).decode("utf-8", errors="ignore") if content_len > 0 else ""
        form_data = urllib.parse.parse_qs(body)
        action = form_data.get("action", [""])[0]
        target = form_data.get("target", ["all"])[0]
        text = form_data.get("text", [""])[0]

        if action == "build":
            asyncio.create_task(AutoBuilder.run_build(trigger_source=f"Cloud Console ({current_user})"))
        elif action in ("kill", "shutdown", "close_game"):
            logger.warning(f"🛑 Remote failsafe triggered: Closing Minecraft for target '{target}'")
            await send_to_target(target, {"type": "SHUTDOWN", "reason": "Remote operator failsafe"})
        elif action == "msg" and text:
            await send_to_target(target, {"type": "MESSAGE", "message": text})
        elif action == "title" and text:
            await send_to_target(target, {"type": "TITLE", "title": text, "subtitle": "Cloud Console Alert"})
        elif action == "chat" and text:
            await send_to_target(target, {"type": "CHAT", "text": text})

        headers_out = ["HTTP/1.1 302 Found", "Location: /", "Connection: close", "\r\n"]
        writer.write("\r\n".join(headers_out).encode("utf-8"))
        writer.close()
        return

    # 10. Dashboard GET Route (Protected)
    if clean_path in ("/", "/dashboard"):
        if not has_admin_user():
            html = render_register_page()
            send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
            return
        current_user = get_authenticated_user(headers)
        if not current_user:
            html = render_login_page()
            send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
            return
        html = render_dashboard_page(current_user=current_user)
        send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
        return

    if clean_path == "/login":
        if not has_admin_user():
            html = render_register_page()
            send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
            return
        if is_authenticated(headers):
            headers_out = ["HTTP/1.1 302 Found", "Location: /", "Connection: close", "\r\n"]
            writer.write("\r\n".join(headers_out).encode("utf-8"))
            writer.close()
            return
        html = render_login_page()
        send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
        return

    send_http_response(writer, 404, "text/plain", b"404 Not Found")


def serve_jar_file(writer: asyncio.StreamWriter, flavor: str, headers: dict, client_ip: str):
    jar_path = get_jar_path(flavor)
    if not jar_path or not jar_path.exists():
        logger.warning(f"Requested {flavor} jar not found for {client_ip}")
        send_http_response(writer, 404, "text/plain", f"Error: {flavor} mod build not found on server.".encode("utf-8"))
        return

    stat = jar_path.stat()
    file_size = stat.st_size
    mtime = stat.st_mtime
    http_mtime = email.utils.formatdate(mtime, usegmt=True)
    etag = f'"{hashlib.md5(f"{flavor}_{mtime}_{file_size}".encode()).hexdigest()}"'

    # 1. Check If-Modified-Since
    ims = headers.get("if-modified-since")
    if ims:
        try:
            ims_time = email.utils.parsedate_to_datetime(ims).timestamp()
            if ims_time >= int(mtime):
                logger.info(f"⚡ 304 Not Modified for {flavor} payload to {client_ip}")
                resp = "HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n"
                writer.write(resp.encode("utf-8"))
                writer.close()
                return
        except Exception:
            pass

    # 2. Check If-None-Match
    inm = headers.get("if-none-match")
    if inm and inm.strip() == etag:
        logger.info(f"⚡ 304 Not Modified (ETag) for {flavor} payload to {client_ip}")
        resp = "HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n"
        writer.write(resp.encode("utf-8"))
        writer.close()
        return

    logger.info(f"📤 Serving updated {flavor} payload jar ({file_size} bytes) to {client_ip}")

    headers_out = [
        "HTTP/1.1 200 OK",
        "Content-Type: application/java-archive",
        f"Content-Length: {file_size}",
        f"Last-Modified: {http_mtime}",
        f"ETag: {etag}",
        f"Content-Disposition: attachment; filename=\"noemtaddons-{flavor}.jar\"",
        "Cache-Control: public, no-cache",
        "Access-Control-Allow-Origin: *",
        "Connection: close",
        "\r\n"
    ]
    writer.write("\r\n".join(headers_out).encode("utf-8"))

    with open(jar_path, "rb") as f:
        while chunk := f.read(65536):
            writer.write(chunk)
    
    writer.close()


def serve_loader_stub_file(writer: asyncio.StreamWriter, flavor: str, headers: dict, client_ip: str):
    loader_path = get_loader_jar_path(flavor)
    if not loader_path or not loader_path.exists():
        logger.warning(f"Requested {flavor} loader jar not found for {client_ip}")
        send_http_response(writer, 404, "text/plain", f"Error: {flavor} loader build not found on server.".encode("utf-8"))
        return

    stat = loader_path.stat()
    file_size = stat.st_size
    mtime = stat.st_mtime
    http_mtime = email.utils.formatdate(mtime, usegmt=True)

    ims = headers.get("if-modified-since")
    if ims:
        try:
            ims_time = email.utils.parsedate_to_datetime(ims).timestamp()
            if ims_time >= int(mtime):
                resp = "HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n"
                writer.write(resp.encode("utf-8"))
                writer.close()
                return
        except Exception:
            pass

    logger.info(f"📤 Serving {flavor} bootstrap loader jar ({file_size} bytes) to {client_ip}")

    headers_out = [
        "HTTP/1.1 200 OK",
        "Content-Type: application/java-archive",
        f"Content-Length: {file_size}",
        f"Last-Modified: {http_mtime}",
        f"Content-Disposition: attachment; filename=\"noemtaddons-{flavor}-loader.jar\"",
        "Access-Control-Allow-Origin: *",
        "Connection: close",
        "\r\n"
    ]
    writer.write("\r\n".join(headers_out).encode("utf-8"))

    with open(loader_path, "rb") as f:
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
# Google Cloud / Material Design 3 UI Renderers
# ==============================================================================

def render_register_page(error: Optional[str] = None) -> str:
    error_html = f'<div class="google-alert-error"><span>⚠️</span> {error}</div>' if error else ""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Initial Setup - Noemt Cloud Console</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Google+Sans:wght@400;500;700&family=Roboto:wght@400;500&family=Roboto+Mono:wght@400;500&display=swap" rel="stylesheet">
    <style>
        :root {{
            --google-blue: #8ab4f8;
            --google-blue-hover: #aecbfa;
            --google-bg: #131314;
            --google-surface: #1e1f20;
            --google-surface-variant: #28292a;
            --google-border: #444746;
            --google-text: #e3e3e3;
            --google-text-secondary: #c4c7c5;
            --google-red: #f28b82;
            --google-green: #81c995;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: var(--google-bg);
            color: var(--google-text);
            font-family: 'Google Sans', 'Roboto', -apple-system, sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
        }}
        .google-card {{
            width: 100%;
            max-width: 460px;
            background: var(--google-surface);
            border: 1px solid var(--google-border);
            border-radius: 28px;
            padding: 40px;
            box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
        }}
        .google-logo-row {{
            display: flex;
            align-items: center;
            gap: 12px;
            margin-bottom: 16px;
        }}
        .google-logo {{
            width: 32px;
            height: 32px;
        }}
        .google-title {{
            font-size: 24px;
            font-weight: 400;
            color: var(--google-text);
            margin-bottom: 8px;
        }}
        .google-subtitle {{
            font-size: 14px;
            color: var(--google-text-secondary);
            margin-bottom: 20px;
        }}
        .setup-notice {{
            background: rgba(138, 180, 248, 0.08);
            border: 1px solid rgba(138, 180, 248, 0.25);
            color: var(--google-blue);
            padding: 12px 16px;
            border-radius: 12px;
            font-size: 12px;
            line-height: 1.5;
            margin-bottom: 20px;
            display: flex;
            align-items: flex-start;
            gap: 10px;
        }}
        .google-alert-error {{
            background: rgba(242, 139, 130, 0.12);
            border: 1px solid rgba(242, 139, 130, 0.3);
            color: var(--google-red);
            padding: 12px 16px;
            border-radius: 8px;
            font-size: 13px;
            margin-bottom: 20px;
            display: flex;
            align-items: center;
            gap: 8px;
        }}
        .form-group {{
            margin-bottom: 18px;
        }}
        .input-label {{
            display: block;
            font-size: 12px;
            font-weight: 500;
            color: var(--google-text-secondary);
            margin-bottom: 6px;
        }}
        .google-input {{
            width: 100%;
            background: var(--google-bg);
            border: 1px solid var(--google-border);
            border-radius: 8px;
            padding: 14px 16px;
            color: var(--google-text);
            font-size: 14px;
            font-family: 'Roboto', sans-serif;
            transition: all 0.2s ease;
        }}
        .google-input:focus {{
            outline: none;
            border-color: var(--google-blue);
            box-shadow: 0 0 0 2px rgba(138, 180, 248, 0.2);
        }}
        .google-btn {{
            width: 100%;
            background: var(--google-blue);
            color: #040c17;
            border: none;
            border-radius: 20px;
            padding: 12px 24px;
            font-size: 14px;
            font-weight: 500;
            font-family: 'Google Sans', sans-serif;
            cursor: pointer;
            transition: all 0.2s ease;
            margin-top: 12px;
        }}
        .google-btn:hover {{
            background: var(--google-blue-hover);
        }}
        .google-footer-text {{
            margin-top: 24px;
            text-align: center;
            font-size: 12px;
            color: var(--google-text-secondary);
        }}
    </style>
</head>
<body>
    <div class="google-card">
        <div class="google-logo-row">
            <svg class="google-logo" viewBox="0 0 24 24">
                <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"/>
                <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"/>
            </svg>
            <span style="font-weight: 500; font-size: 18px; letter-spacing: -0.2px;">Noemt Cloud</span>
        </div>
        <h1 class="google-title">Initial Setup</h1>
        <p class="google-subtitle">Create your Operator Account (One-Time Setup)</p>
        
        <div class="setup-notice">
            <span>🛡️</span>
            <div><strong>First-Time Setup:</strong> Registration will be permanently locked after you create this account. Credentials will be securely hashed with PBKDF2 in SQLite.</div>
        </div>

        {error_html}

        <form method="POST" action="/register">
            <div class="form-group">
                <label class="input-label">Operator Username</label>
                <input type="text" name="username" class="google-input" placeholder="e.g. nom" minlength="3" required autofocus>
            </div>
            <div class="form-group">
                <label class="input-label">Master Password</label>
                <input type="password" name="password" class="google-input" placeholder="Min. 6 characters" minlength="6" required>
            </div>
            <div class="form-group">
                <label class="input-label">Confirm Password</label>
                <input type="password" name="confirm_password" class="google-input" placeholder="Re-enter password" minlength="6" required>
            </div>
            <button type="submit" class="google-btn">Create Account & Enter Console →</button>
        </form>
        <div class="google-footer-text">
            Protected by Noemt Cloud SQLite Identity Storage
        </div>
    </div>
</body>
</html>"""


def render_login_page(error: Optional[str] = None) -> str:
    error_html = f'<div class="google-alert-error"><span>⚠️</span> {error}</div>' if error else ""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Sign in - Noemt Cloud Accounts</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Google+Sans:wght@400;500;700&family=Roboto:wght@400;500&family=Roboto+Mono:wght@400;500&display=swap" rel="stylesheet">
    <style>
        :root {{
            --google-blue: #8ab4f8;
            --google-blue-hover: #aecbfa;
            --google-bg: #131314;
            --google-surface: #1e1f20;
            --google-surface-variant: #28292a;
            --google-border: #444746;
            --google-text: #e3e3e3;
            --google-text-secondary: #c4c7c5;
            --google-red: #f28b82;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: var(--google-bg);
            color: var(--google-text);
            font-family: 'Google Sans', 'Roboto', -apple-system, sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
        }}
        .google-card {{
            width: 100%;
            max-width: 440px;
            background: var(--google-surface);
            border: 1px solid var(--google-border);
            border-radius: 28px;
            padding: 40px;
            box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
        }}
        .google-logo-row {{
            display: flex;
            align-items: center;
            gap: 12px;
            margin-bottom: 16px;
        }}
        .google-logo {{
            width: 32px;
            height: 32px;
        }}
        .google-title {{
            font-size: 24px;
            font-weight: 400;
            color: var(--google-text);
            margin-bottom: 8px;
        }}
        .google-subtitle {{
            font-size: 14px;
            color: var(--google-text-secondary);
            margin-bottom: 28px;
        }}
        .google-alert-error {{
            background: rgba(242, 139, 130, 0.12);
            border: 1px solid rgba(242, 139, 130, 0.3);
            color: var(--google-red);
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
        .input-label {{
            display: block;
            font-size: 12px;
            font-weight: 500;
            color: var(--google-text-secondary);
            margin-bottom: 6px;
        }}
        .google-input {{
            width: 100%;
            background: var(--google-bg);
            border: 1px solid var(--google-border);
            border-radius: 8px;
            padding: 14px 16px;
            color: var(--google-text);
            font-size: 14px;
            font-family: 'Roboto', sans-serif;
            transition: all 0.2s ease;
        }}
        .google-input:focus {{
            outline: none;
            border-color: var(--google-blue);
            box-shadow: 0 0 0 2px rgba(138, 180, 248, 0.2);
        }}
        .google-btn {{
            width: 100%;
            background: var(--google-blue);
            color: #040c17;
            border: none;
            border-radius: 20px;
            padding: 12px 24px;
            font-size: 14px;
            font-weight: 500;
            font-family: 'Google Sans', sans-serif;
            cursor: pointer;
            transition: all 0.2s ease;
            margin-top: 12px;
        }}
        .google-btn:hover {{
            background: var(--google-blue-hover);
        }}
        .google-footer-text {{
            margin-top: 24px;
            text-align: center;
            font-size: 12px;
            color: var(--google-text-secondary);
        }}
    </style>
</head>
<body>
    <div class="google-card">
        <div class="google-logo-row">
            <svg class="google-logo" viewBox="0 0 24 24">
                <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"/>
                <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"/>
            </svg>
            <span style="font-weight: 500; font-size: 18px; letter-spacing: -0.2px;">Noemt Cloud</span>
        </div>
        <h1 class="google-title">Sign in</h1>
        <p class="google-subtitle">Use your Noemt Operator Account</p>
        {error_html}
        <form method="POST" action="/login">
            <div class="form-group">
                <label class="input-label">Operator ID</label>
                <input type="text" name="username" class="google-input" placeholder="Operator username" required autofocus>
            </div>
            <div class="form-group">
                <label class="input-label">Password</label>
                <input type="password" name="password" class="google-input" placeholder="Operator password" required>
            </div>
            <button type="submit" class="google-btn">Sign in to Console →</button>
        </form>
        <div class="google-footer-text">
            Protected by Noemt Cloud Identity Services
        </div>
    </div>
</body>
</html>"""


def render_dashboard_page(current_user: str = "nom") -> str:
    meta = compute_version_metadata()
    connected_count = len(clients)
    short_hash, author, msg = AutoBuilder.get_latest_commit_details()

    player_rows = ""
    if clients:
        for name, info in clients.items():
            player_rows += f"""
            <tr>
                <td>
                    <div style="display:flex; align-items:center; gap:12px;">
                        <div class="avatar-chip">{name[:1].upper()}</div>
                        <div>
                            <div style="font-weight:500; color:var(--google-text);">{name}</div>
                            <div style="font-size:11px; font-family:'Roboto Mono',monospace; color:var(--google-text-secondary);">{info['uuid'][:12]}...</div>
                        </div>
                    </div>
                </td>
                <td><span class="status-pill status-healthy"><span class="pulse-dot"></span> RUNNING</span></td>
                <td><code>{info['ip']}</code></td>
                <td><span class="badge-chip">v{info['version']}</span></td>
                <td style="color:var(--google-text-secondary); font-size:12px;">{info['connected_at']}</td>
                <td style="text-align:right;">
                    <form method="POST" action="/api/action" style="display:inline;" onsubmit="return confirm('Emergency close Minecraft for {name}?');">
                        <input type="hidden" name="action" value="kill">
                        <input type="hidden" name="target" value="{name}">
                        <button type="submit" class="google-btn-danger-sm">⛔ Close Game</button>
                    </form>
                </td>
            </tr>
            """
    else:
        player_rows = '<tr><td colspan="6" style="text-align:center; padding: 48px 16px; color: var(--google-text-secondary);"><div style="font-size:28px; margin-bottom:8px;">🎮</div><div style="font-size:14px; font-weight:500;">No Active Client Instances Connected</div><div style="font-size:12px; margin-top:4px; opacity:0.8;">Launch Minecraft with NoemtAddons to establish live WebSocket telemetry link.</div></td></tr>'

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Google Cloud Console • NoemtAddons Control Plane</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Google+Sans:wght@400;500;600;700&family=Roboto:wght@400;500&family=Roboto+Mono:wght@400;500;600&display=swap" rel="stylesheet">
    <style>
        :root {{
            --google-blue: #8ab4f8;
            --google-blue-container: #1b3a57;
            --google-green: #81c995;
            --google-yellow: #fdd663;
            --google-red: #f28b82;
            --google-bg: #131314;
            --google-surface: #1e1f20;
            --google-surface-variant: #28292a;
            --google-surface-hover: #323335;
            --google-border: #444746;
            --google-border-subtle: rgba(255, 255, 255, 0.08);
            --google-text: #e3e3e3;
            --google-text-secondary: #c4c7c5;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: var(--google-bg);
            color: var(--google-text);
            font-family: 'Google Sans', 'Roboto', -apple-system, sans-serif;
            min-height: 100vh;
            -webkit-font-smoothing: antialiased;
        }}
        /* Google Cloud Top Bar */
        .google-app-bar {{
            height: 52px;
            background: var(--google-surface);
            border-bottom: 1px solid var(--google-border);
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 0 20px;
            position: sticky;
            top: 0;
            z-index: 100;
            backdrop-filter: blur(12px);
        }}
        .bar-left {{
            display: flex;
            align-items: center;
            gap: 16px;
        }}
        .bar-brand {{
            display: flex;
            align-items: center;
            gap: 10px;
            font-size: 15px;
            font-weight: 500;
            color: var(--google-text);
            text-decoration: none;
            letter-spacing: -0.2px;
        }}
        .project-selector {{
            display: flex;
            align-items: center;
            gap: 8px;
            background: var(--google-surface-variant);
            border: 1px solid var(--google-border);
            border-radius: 8px;
            padding: 5px 12px;
            font-size: 12px;
            font-weight: 500;
            color: var(--google-text);
            cursor: pointer;
            transition: all 0.2s ease;
        }}
        .project-selector:hover {{
            background: var(--google-surface-hover);
            border-color: var(--google-blue);
        }}
        .bar-search {{
            flex: 1;
            max-width: 480px;
            margin: 0 24px;
        }}
        .search-box {{
            width: 100%;
            background: var(--google-bg);
            border: 1px solid var(--google-border);
            border-radius: 8px;
            padding: 7px 14px;
            color: var(--google-text);
            font-size: 13px;
            font-family: inherit;
            transition: border-color 0.2s ease;
        }}
        .search-box:focus {{
            outline: none;
            border-color: var(--google-blue);
        }}
        .bar-right {{
            display: flex;
            align-items: center;
            gap: 12px;
        }}
        .user-avatar {{
            width: 32px;
            height: 32px;
            border-radius: 50%;
            background: linear-gradient(135deg, #1a73e8, #4285f4);
            color: #fff;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 600;
            font-size: 13px;
            box-shadow: 0 2px 6px rgba(0,0,0,0.3);
        }}
        /* Main Container */
        .container {{
            max-width: 1320px;
            margin: 0 auto;
            padding: 28px 24px;
        }}
        .page-header {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 24px;
            flex-wrap: wrap;
            gap: 16px;
        }}
        .page-title h1 {{
            font-size: 24px;
            font-weight: 500;
            letter-spacing: -0.3px;
        }}
        .page-title p {{
            font-size: 13px;
            color: var(--google-text-secondary);
            margin-top: 4px;
        }}
        .action-row {{
            display: flex;
            align-items: center;
            gap: 10px;
        }}
        .google-btn-primary {{
            background: var(--google-blue);
            color: #040c17;
            border: none;
            border-radius: 8px;
            padding: 9px 18px;
            font-size: 13px;
            font-weight: 600;
            font-family: 'Google Sans', sans-serif;
            cursor: pointer;
            display: inline-flex;
            align-items: center;
            gap: 8px;
            transition: all 0.2s ease;
            box-shadow: 0 1px 3px rgba(0,0,0,0.2);
        }}
        .google-btn-primary:hover {{
            background: #aecbfa;
            transform: translateY(-1px);
        }}
        .google-btn-secondary {{
            background: var(--google-surface-variant);
            color: var(--google-text);
            border: 1px solid var(--google-border);
            border-radius: 8px;
            padding: 8px 16px;
            font-size: 13px;
            font-weight: 500;
            font-family: 'Google Sans', sans-serif;
            text-decoration: none;
            display: inline-flex;
            align-items: center;
            gap: 6px;
            cursor: pointer;
            transition: all 0.2s ease;
        }}
        .google-btn-secondary:hover {{
            background: var(--google-surface-hover);
            border-color: rgba(255,255,255,0.2);
        }}
        .google-btn-danger {{
            background: rgba(242, 139, 130, 0.15);
            color: var(--google-red);
            border: 1px solid rgba(242, 139, 130, 0.3);
            border-radius: 8px;
            padding: 9px 16px;
            font-size: 13px;
            font-weight: 600;
            font-family: 'Google Sans', sans-serif;
            cursor: pointer;
            transition: all 0.2s ease;
        }}
        .google-btn-danger:hover {{
            background: rgba(242, 139, 130, 0.25);
            border-color: var(--google-red);
        }}
        .google-btn-danger-sm {{
            background: rgba(242, 139, 130, 0.12);
            color: var(--google-red);
            border: 1px solid rgba(242, 139, 130, 0.25);
            border-radius: 6px;
            padding: 5px 12px;
            font-size: 12px;
            font-weight: 500;
            font-family: 'Google Sans', sans-serif;
            cursor: pointer;
            transition: all 0.15s ease;
        }}
        .google-btn-danger-sm:hover {{
            background: rgba(242, 139, 130, 0.25);
            border-color: var(--google-red);
        }}
        /* Metric Cards */
        .metric-grid {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
            gap: 16px;
            margin-bottom: 24px;
        }}
        .metric-card {{
            background: var(--google-surface);
            border: 1px solid var(--google-border);
            border-radius: 12px;
            padding: 20px;
            transition: border-color 0.2s ease;
        }}
        .metric-card:hover {{
            border-color: rgba(255, 255, 255, 0.2);
        }}
        .metric-title {{
            font-size: 11px;
            font-weight: 600;
            color: var(--google-text-secondary);
            text-transform: uppercase;
            letter-spacing: 0.6px;
            margin-bottom: 8px;
        }}
        .metric-val {{
            font-size: 24px;
            font-weight: 500;
            display: flex;
            align-items: center;
            gap: 10px;
        }}
        /* Main Layout Grid */
        .dashboard-grid {{
            display: grid;
            grid-template-columns: 2fr 1fr;
            gap: 20px;
        }}
        @media (max-width: 980px) {{
            .dashboard-grid {{ grid-template-columns: 1fr; }}
            .bar-search {{ display: none; }}
        }}
        .card {{
            background: var(--google-surface);
            border: 1px solid var(--google-border);
            border-radius: 12px;
            padding: 20px;
            margin-bottom: 20px;
        }}
        .card-title {{
            font-size: 15px;
            font-weight: 600;
            margin-bottom: 16px;
            display: flex;
            align-items: center;
            justify-content: space-between;
        }}
        table {{
            width: 100%;
            border-collapse: collapse;
        }}
        th, td {{
            padding: 13px 16px;
            text-align: left;
            border-bottom: 1px solid var(--google-border);
            font-size: 13px;
        }}
        th {{
            color: var(--google-text-secondary);
            font-size: 11px;
            text-transform: uppercase;
            font-weight: 600;
            letter-spacing: 0.6px;
            background: rgba(255,255,255,0.02);
        }}
        tbody tr:hover {{
            background: rgba(255,255,255,0.02);
        }}
        code {{
            background: var(--google-surface-variant);
            padding: 3px 7px;
            border-radius: 6px;
            font-family: 'Roboto Mono', monospace;
            font-size: 12px;
            color: var(--google-blue);
            border: 1px solid var(--google-border-subtle);
        }}
        .status-pill {{
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 3px 10px;
            border-radius: 12px;
            font-size: 11px;
            font-weight: 600;
            letter-spacing: 0.3px;
        }}
        .status-healthy {{ background: rgba(129, 201, 149, 0.15); color: var(--google-green); }}
        .badge-chip {{
            background: var(--google-surface-variant);
            border: 1px solid var(--google-border);
            border-radius: 12px;
            padding: 3px 9px;
            font-size: 11px;
            font-weight: 500;
            font-family: 'Roboto Mono', monospace;
        }}
        .avatar-chip {{
            width: 28px;
            height: 28px;
            border-radius: 50%;
            background: linear-gradient(135deg, #1a73e8, #8ab4f8);
            color: #040c17;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 700;
            font-size: 12px;
        }}
        .pulse-dot {{
            width: 7px;
            height: 7px;
            border-radius: 50%;
            background: var(--google-green);
            box-shadow: 0 0 8px var(--google-green);
            animation: pulse 2s infinite;
        }}
        @keyframes pulse {{
            0% {{ transform: scale(0.95); opacity: 0.8; }}
            50% {{ transform: scale(1.2); opacity: 1; }}
            100% {{ transform: scale(0.95); opacity: 0.8; }}
        }}
        .form-control {{
            width: 100%;
            background: var(--google-bg);
            border: 1px solid var(--google-border);
            border-radius: 8px;
            padding: 9px 12px;
            color: var(--google-text);
            font-size: 13px;
            font-family: inherit;
            margin-bottom: 12px;
            transition: border-color 0.2s ease;
        }}
        .form-control:focus {{
            outline: none;
            border-color: var(--google-blue);
        }}
        .copy-btn {{
            background: transparent;
            border: 1px solid var(--google-border);
            color: var(--google-text-secondary);
            border-radius: 6px;
            padding: 2px 8px;
            font-size: 11px;
            cursor: pointer;
            transition: all 0.2s ease;
        }}
        .copy-btn:hover {{
            background: var(--google-surface-variant);
            color: var(--google-text);
            border-color: var(--google-blue);
        }}
        /* Toast Notification */
        #toast {{
            visibility: hidden;
            min-width: 250px;
            background: #1b3a57;
            color: #8ab4f8;
            border: 1px solid var(--google-blue);
            text-align: center;
            border-radius: 8px;
            padding: 12px 20px;
            position: fixed;
            z-index: 1000;
            left: 50%;
            bottom: 30px;
            transform: translateX(-50%);
            font-size: 13px;
            font-weight: 500;
            box-shadow: 0 4px 16px rgba(0,0,0,0.4);
            opacity: 0;
            transition: opacity 0.3s, visibility 0.3s;
        }}
        #toast.show {{
            visibility: visible;
            opacity: 1;
        }}
    </style>
</head>
<body>
    <div id="toast">Copied to clipboard!</div>

    <!-- Google Cloud App Bar -->
    <div class="google-app-bar">
        <div class="bar-left">
            <a href="/" class="bar-brand">
                <svg width="22" height="22" viewBox="0 0 24 24">
                    <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                    <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                    <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"/>
                    <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"/>
                </svg>
                <span>Noemt Cloud Console</span>
            </a>
            <div class="project-selector" title="Active Project">
                <span>🏢 noemtaddons-prod</span>
                <small style="color:var(--google-text-secondary);">▾</small>
            </div>
        </div>
        <div class="bar-search">
            <input type="text" class="search-box" id="searchFilter" onkeyup="filterTable()" placeholder="Search connected instances or endpoints...">
        </div>
        <div class="bar-right">
            <a href="/logout" class="google-btn-secondary" style="padding:5px 12px; font-size:12px;">Sign Out</a>
            <div class="user-avatar" title="Logged in as {current_user}">{current_user[:1].upper()}</div>
        </div>
    </div>

    <!-- Main Content -->
    <div class="container">
        <div class="page-header">
            <div class="page-title">
                <h1>Mod Telemetry & Instance Management</h1>
                <p>Project: <b>noemtaddons-prod</b> • Region: <b>global</b> • Operator: <b>{current_user}</b> • Mode: <b>Event-Driven CI/CD</b></p>
            </div>
            <div class="action-row">
                <form method="POST" action="/api/action" style="display:inline;">
                    <input type="hidden" name="action" value="build">
                    <button type="submit" class="google-btn-primary">⚡ Instant Pull & Build</button>
                </form>
                <a href="/changelog" class="google-btn-secondary" target="_blank">📜 View Changelog</a>
            </div>
        </div>

        <!-- Metric Cards -->
        <div class="metric-grid">
            <div class="metric-card">
                <div class="metric-title">Active Client Instances</div>
                <div class="metric-val">
                    <span>{connected_count}</span>
                    <span class="status-pill status-healthy"><span class="pulse-dot"></span> {connected_count} online</span>
                </div>
            </div>
            <div class="metric-card">
                <div class="metric-title">Current Git Release</div>
                <div class="metric-val">
                    <code>{short_hash}</code>
                    <span class="badge-chip">{GIT_BRANCH}</span>
                </div>
            </div>
            <div class="metric-card">
                <div class="metric-title">Build Pipeline Status</div>
                <div class="metric-val" style="color: {'var(--google-green)' if LAST_BUILD_STATUS == 'Healthy' else 'var(--google-red)'}; font-size:20px;">
                    {LAST_BUILD_STATUS}
                </div>
            </div>
            <div class="metric-card">
                <div class="metric-title">Last Automated Deployment</div>
                <div class="metric-val" style="font-size: 14px; font-family:'Roboto Mono',monospace;">
                    {LAST_BUILD_TIME}
                </div>
            </div>
        </div>

        <!-- Dashboard Grid -->
        <div class="dashboard-grid">
            <div>
                <!-- Connected Instances Table -->
                <div class="card">
                    <div class="card-title">
                        <span>👥 Connected Client Instances ({len(clients)})</span>
                        <small style="color:var(--google-text-secondary); font-weight:normal;">Real-Time WebSocket Link</small>
                    </div>
                    <table id="instancesTable">
                        <thead>
                            <tr>
                                <th>Client Instance</th>
                                <th>Status</th>
                                <th>IP Address</th>
                                <th>Version</th>
                                <th>Connected At</th>
                                <th style="text-align:right;">Failsafe Action</th>
                            </tr>
                        </thead>
                        <tbody>
                            {player_rows}
                        </tbody>
                    </table>
                </div>

                <!-- Mod Loader Distribution Card -->
                <div class="card">
                    <div class="card-title">
                        <span>📦 Distributed Builds & Dynamic Endpoints</span>
                        <small style="color:var(--google-text-secondary); font-weight:normal;">Smart HTTP 304 Cache</small>
                    </div>
                    <table>
                        <thead>
                            <tr>
                                <th>Component</th>
                                <th>Download Endpoint</th>
                                <th>Size</th>
                                <th>Checksum</th>
                                <th>Action</th>
                            </tr>
                        </thead>
                        <tbody>
                            <tr>
                                <td><span class="status-pill status-healthy">MOD PAYLOAD</span></td>
                                <td>
                                    <code>/loaders/noemtaddons.jar</code>
                                    <button class="copy-btn" onclick="copyText('https://addons.noemt.dev/loaders/noemtaddons.jar')">Copy</button>
                                </td>
                                <td>{meta['endpoints']['mod']['size'] / 1024:.1f} KB</td>
                                <td><small style="color:var(--google-text-secondary);">{meta['endpoints']['mod']['sha256'][:14]}...</small></td>
                                <td><a href="/download/mod" class="google-btn-secondary" style="padding:4px 10px; font-size:11px;">Download .jar</a></td>
                            </tr>
                            <tr>
                                <td><span class="status-pill" style="background:rgba(138,180,248,0.15); color:var(--google-blue);">BOOTSTRAP LOADER</span></td>
                                <td>
                                    <code>/download/loader</code>
                                    <button class="copy-btn" onclick="copyText('https://addons.noemt.dev/download/loader')">Copy</button>
                                </td>
                                <td>{meta['endpoints']['loader']['size'] / 1024:.1f} KB</td>
                                <td><small style="color:var(--google-text-secondary);">{meta['endpoints']['loader']['sha256'][:14]}...</small></td>
                                <td><a href="/download/loader" class="google-btn-secondary" style="padding:4px 10px; font-size:11px;">Download .jar</a></td>
                            </tr>
                        </tbody>
                    </table>
                </div>

                <!-- Instant Webhook Guide Card -->
                <div class="card">
                    <div class="card-title">
                        <span>⚡ Instant GitHub CI/CD Webhook</span>
                    </div>
                    <p style="font-size:12px; color:var(--google-text-secondary); line-height:1.6; margin-bottom:12px;">
                        Add this URL to your GitHub Repository Settings (<b>Webhooks → Add webhook</b>). The server will instantly pull and build on every push.
                    </p>
                    <div style="display:flex; gap:10px; align-items:center;">
                        <input type="text" class="form-control" style="margin-bottom:0;" value="https://addons.noemt.dev/api/webhook" readonly id="webhookInput">
                        <button class="google-btn-secondary" onclick="copyText(document.getElementById('webhookInput').value)">Copy URL</button>
                    </div>
                </div>
            </div>

            <!-- Right Column: Emergency Failsafe & Remote Control -->
            <div>
                <!-- Emergency Client Shutdown Card -->
                <div class="card" style="border-color: rgba(242, 139, 130, 0.4); background: linear-gradient(180deg, rgba(242,139,130,0.04), var(--google-surface));">
                    <div class="card-title" style="color: var(--google-red);">
                        <span>🛑 Remote Client Failsafe</span>
                    </div>
                    <p style="font-size:12px; color:var(--google-text-secondary); margin-bottom:14px; line-height:1.5;">
                        Emergency kill switch that closes Minecraft cleanly when away from computer.
                    </p>
                    <form method="POST" action="/api/action" onsubmit="return confirm('Trigger emergency shutdown for selected target?');">
                        <input type="hidden" name="action" value="kill">
                        <label style="font-size:11px; font-weight:600; color:var(--google-text-secondary); display:block; margin-bottom:6px;">TARGET CLIENT</label>
                        <input type="text" name="target" class="form-control" value="all" placeholder="Player IGN or 'all'" required>
                        <button type="submit" class="google-btn-danger" style="width:100%;">⚡ Close Target Client(s)</button>
                    </form>
                </div>

                <!-- Remote Command Dispatch -->
                <div class="card">
                    <div class="card-title">
                        <span>🎮 Remote Instance Dispatch</span>
                    </div>
                    <form method="POST" action="/api/action">
                        <label style="font-size:11px; font-weight:600; color:var(--google-text-secondary); display:block; margin-bottom:6px;">ACTION</label>
                        <select name="action" class="form-control">
                            <option value="msg">💬 Chat Message</option>
                            <option value="title">🔔 Screen Title Alert</option>
                            <option value="chat">⚡ Execute In-Game Command</option>
                        </select>

                        <label style="font-size:11px; font-weight:600; color:var(--google-text-secondary); display:block; margin-bottom:6px;">TARGET</label>
                        <input type="text" name="target" class="form-control" value="all" placeholder="Player IGN or 'all'">

                        <label style="font-size:11px; font-weight:600; color:var(--google-text-secondary); display:block; margin-bottom:6px;">PAYLOAD TEXT</label>
                        <input type="text" name="text" class="form-control" placeholder="Message or $command" required>

                        <button type="submit" class="google-btn-primary" style="width:100%;">Dispatch to Client →</button>
                    </form>
                </div>

                <!-- Git Metadata -->
                <div class="card">
                    <div class="card-title">
                        <span>🌿 Git Deployment Info</span>
                    </div>
                    <div style="font-size:12px; line-height:1.7; color:var(--google-text-secondary);">
                        <p><b>Commit:</b> <code>{short_hash}</code> ({author})</p>
                        <p style="margin-top:4px;"><b>Message:</b> <span style="color:var(--google-text);">{msg}</span></p>
                        <p style="margin-top:4px;"><b>Branch:</b> <code>origin/{GIT_BRANCH}</code></p>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script>
        function copyText(text) {{
            navigator.clipboard.writeText(text).then(function() {{
                var toast = document.getElementById("toast");
                toast.className = "show";
                setTimeout(function(){{ toast.className = toast.className.replace("show", ""); }}, 2500);
            }});
        }}

        function filterTable() {{
            var input = document.getElementById("searchFilter");
            var filter = input.value.toUpperCase();
            var table = document.getElementById("instancesTable");
            var tr = table.getElementsByTagName("tr");
            for (var i = 1; i < tr.length; i++) {{
                var td = tr[i].getElementsByTagName("td")[0];
                if (td) {{
                    var txtValue = td.textContent || td.innerText;
                    tr[i].style.display = txtValue.toUpperCase().indexOf(filter) > -1 ? "" : "none";
                }}
            }}
        }}
    </script>
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

                    if SERVER_INSTANCE and SERVER_INSTANCE.bot and hasattr(SERVER_INSTANCE.bot, "dispatch"):
                        SERVER_INSTANCE.bot.dispatch("player_join", player_name, clients[player_name])

                    await send_ws_json(writer, {
                        "type": "HANDSHAKE_ACK",
                        "message": f"Connected to Noemt Cloud Server as '{player_name}'",
                        "serverTime": int(datetime.now().timestamp() * 1000)
                    })

                elif msg_type == "STATUS_RESPONSE":
                    x = data.get("x", 0)
                    y = data.get("y", 0)
                    z = data.get("z", 0)
                    hp = data.get("health", 0)
                    logger.info(f"📊 Status [{player_name}]: Pos=({x:.1f}, {y:.1f}, {z:.1f}) | HP={hp}")

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
            if SERVER_INSTANCE and SERVER_INSTANCE.bot and hasattr(SERVER_INSTANCE.bot, "dispatch"):
                SERVER_INSTANCE.bot.dispatch("player_leave", player_name)
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
    print(" ☁️ Noemt Cloud Console & CI/CD Server Ready")
    print(" Type 'help' for command list.")
    print("=" * 65 + "\n")

    loop = asyncio.get_event_loop()
    while True:
        try:
            line = await loop.run_in_executor(None, input, "noemt-cloud> ")
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
  kill / close <player|all>           - Remotely close player's Minecraft instance
  list                                - List connected players
  msg <player|all> <text>             - Send chat message to player(s)
  chat <player|all> <command>         - Execute command as player
  title <player|all> <title> [sub]    - Show screen title alert
  goto <player|all> <x> <y> <z>       - Direct player pathfinder to coords
  stop <player|all>                   - Stop player pathfinder
  status <player|all>                 - Query player position & health
  webhook <url>                       - Set or test Discord webhook URL
  quit / exit                         - Shutdown server
""")

            elif cmd in ("build", "update", "pull"):
                print("Triggering manual build...")
                asyncio.create_task(AutoBuilder.run_build(trigger_source="Manual CLI Command"))

            elif cmd in ("kill", "close", "shutdown"):
                target = args.strip() if args else "all"
                n = await send_to_target(target, {"type": "SHUTDOWN", "reason": "CLI remote shutdown"})
                print(f"Sent emergency shutdown signal to {n} client(s).")

            elif cmd in ("creds", "pass", "login", "user"):
                if has_admin_user():
                    print(f"\n🔐 Cloud Console Admin: '{get_admin_username()}' (Configured in SQLite database server.db)\n")
                else:
                    print("\n⚠️ Initial setup required: Open http://<host>:<port>/ in your browser to register the master operator account.\n")

            elif cmd == "list":
                if not clients:
                    print("No players connected.")
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

            elif cmd == "webhook":
                global DISCORD_WEBHOOK
                if args:
                    DISCORD_WEBHOOK = args.strip()
                    print(f"Updated Discord webhook URL: {DISCORD_WEBHOOK}")
                else:
                    print(f"Current webhook: {DISCORD_WEBHOOK or 'None'}")

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
# Server Application Class & Factory
# ==============================================================================

class ServerApp:
    def __init__(self):
        self.bot = None
        self.port = 8765
        self.host = "0.0.0.0"
        self.clients = clients
        self.ws_to_player = ws_to_player
        self.AutoBuilder = AutoBuilder
        self.send_to_target = send_to_target
        self.compute_version_metadata = compute_version_metadata

    @property
    def IS_BUILDING(self):
        return IS_BUILDING

    @property
    def LAST_BUILD_STATUS(self):
        return LAST_BUILD_STATUS

    @property
    def LAST_BUILD_TIME(self):
        return LAST_BUILD_TIME

    @property
    def LAST_BUILD_OUTPUT(self):
        return LAST_BUILD_OUTPUT

    @property
    def GIT_BRANCH(self):
        return GIT_BRANCH

    async def run_task(self, host: str = "0.0.0.0", port: int = 8765):
        self.host = host
        self.port = port
        init_db()

        print("\n" + "=" * 65)
        print(" 🔒 NOEMT CLOUD CONTROL PLANE & DISCORD BOT")
        print(f"    Web Dashboard: http://{host}:{port}/")
        if has_admin_user():
            print(f"    Operator:      {get_admin_username()} (Protected)")
        else:
            print("    Status:        Initial Setup Required (Open URL in browser to register)")
        print("=" * 65 + "\n")

        logger.info(f"Starting Noemt Cloud Control Server on http://{host}:{port}")
        server = await asyncio.start_server(handle_connection, host, port)
        async with server:
            await server.serve_forever()


SERVER_INSTANCE = ServerApp()


def create_server() -> ServerApp:
    init_db()
    return SERVER_INSTANCE


# ==============================================================================
# Server Main Entrypoint (Standalone CLI runner)
# ==============================================================================

async def main():
    parser = argparse.ArgumentParser(description="Noemt Cloud Console & CI/CD Mod Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host address (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8765, help="Port (default: 8765)")
    parser.add_argument("--repo-dir", default=None, help="Root repository directory (default: parent of server/)")
    parser.add_argument("--discord-token", default=None, help="Discord Bot Token for build notifications")
    parser.add_argument("--discord-channel", default=None, help="Discord Channel ID for build notifications")
    parser.add_argument("--secret", default=None, help="Optional client authentication secret key")
    args = parser.parse_args()

    global AUTH_SECRET, REPO_DIR, JARS_DIR, GIT_BRANCH, POLL_INTERVAL, DISCORD_BOT_TOKEN, DISCORD_CHANNEL_ID
    AUTH_SECRET = args.secret
    if args.repo_dir:
        REPO_DIR = Path(args.repo_dir)
    if args.jars_dir:
        JARS_DIR = Path(args.jars_dir)
    else:
        JARS_DIR = REPO_DIR / "build" / "libs"
    GIT_BRANCH = args.branch
    POLL_INTERVAL = args.poll_interval
    if args.discord_token:
        DISCORD_BOT_TOKEN = args.discord_token
    if args.discord_channel:
        DISCORD_CHANNEL_ID = args.discord_channel

    init_db()

    print("\n" + "=" * 65)
    print(" 🔒 NOEMT CLOUD CONSOLE")
    print(f"    URL:      http://{args.host}:{args.port}/")
    if has_admin_user():
        print(f"    Operator: {get_admin_username()} (Protected)")
    else:
        print("    Status:   Initial Setup Required (Open URL in browser to register)")
    print("=" * 65 + "\n")

    logger.info(f"Starting Noemt Cloud Server on http://{args.host}:{args.port}")
    logger.info(f"Repository: {REPO_DIR.resolve()} (Branch: '{GIT_BRANCH}')")
    logger.info(f"Mod JARs: {JARS_DIR.resolve()}")

    server = await asyncio.start_server(handle_connection, args.host, args.port)

    async with server:
        await asyncio.gather(
            server.serve_forever(),
            interactive_console()
        )


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")
