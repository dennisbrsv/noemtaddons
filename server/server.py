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
WEB_DIST_DIR: Path = REPO_DIR / "web" / "dist"
WEB_PUBLIC_DIR: Path = REPO_DIR / "web" / "public"
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
        conn.execute("""
            CREATE TABLE IF NOT EXISTS player_sizes (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                scale_x REAL NOT NULL DEFAULT 1.0,
                scale_y REAL NOT NULL DEFAULT 1.0,
                scale_z REAL NOT NULL DEFAULT 1.0,
                custom_name TEXT DEFAULT '',
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.commit()
    load_player_sizes()


player_sizes: Dict[str, dict] = {}


def load_player_sizes():
    global player_sizes
    try:
        with get_db() as conn:
            rows = conn.execute("SELECT uuid, name, scale_x, scale_y, scale_z, custom_name FROM player_sizes").fetchall()
            for r in rows:
                u = r["uuid"].lower()
                player_sizes[u] = {
                    "uuid": r["uuid"],
                    "name": r["name"],
                    "scale": [float(r["scale_x"]), float(r["scale_y"]), float(r["scale_z"])],
                    "customName": r["custom_name"] or ""
                }
    except Exception as e:
        logger.warning(f"Failed loading player sizes from database: {e}")


def save_player_size(uuid_str: str, name: str, scale: List[float], custom_name: str = "") -> dict:
    global player_sizes
    clean_uuid = uuid_str.strip()
    u_key = clean_uuid.lower()
    sx = float(scale[0]) if len(scale) > 0 else 1.0
    sy = float(scale[1]) if len(scale) > 1 else 1.0
    sz = float(scale[2]) if len(scale) > 2 else 1.0

    # Sanitize and clamp
    sx = max(-10.0, min(10.0, sx))
    sy = max(-10.0, min(10.0, sy))
    sz = max(-10.0, min(10.0, sz))

    record = {
        "uuid": clean_uuid,
        "name": name.strip(),
        "scale": [sx, sy, sz],
        "customName": (custom_name or "").strip()
    }
    player_sizes[u_key] = record

    try:
        with get_db() as conn:
            conn.execute("""
                INSERT INTO player_sizes (uuid, name, scale_x, scale_y, scale_z, custom_name, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(uuid) DO UPDATE SET
                    name=excluded.name,
                    scale_x=excluded.scale_x,
                    scale_y=excluded.scale_y,
                    scale_z=excluded.scale_z,
                    custom_name=excluded.custom_name,
                    updated_at=CURRENT_TIMESTAMP
            """, (clean_uuid, name.strip(), sx, sy, sz, (custom_name or "").strip()))
            conn.commit()
    except Exception as e:
        logger.warning(f"Error persisting player size for {name}: {e}")

    return record


def delete_player_size(uuid_str: str) -> bool:
    global player_sizes
    clean_uuid = uuid_str.strip()
    u_key = clean_uuid.lower()
    if u_key in player_sizes:
        del player_sizes[u_key]
    try:
        with get_db() as conn:
            conn.execute("DELETE FROM player_sizes WHERE LOWER(uuid) = ?", (u_key,))
            conn.commit()
    except Exception as e:
        logger.warning(f"Error deleting player size for {uuid_str}: {e}")
    return True


def get_all_player_sizes_payload() -> List[dict]:
    return list(player_sizes.values())


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
    server_jars = Path(__file__).parent / "jars"
    candidates = [
        server_jars / "noemtaddons.jar",
        server_jars / f"noemtaddons-{ver}.jar",
        server_jars / "noemtaddons-cheat.jar",
        server_jars / f"noemtaddons-{ver}-cheat.jar",
        JARS_DIR / f"noemtaddons-{ver}.jar",
        JARS_DIR / "noemtaddons.jar",
        JARS_DIR / f"noemtaddons-{ver}-cheat.jar",
        JARS_DIR / "noemtaddons-cheat.jar",
    ]
    for p in candidates:
        if p.exists() and p.is_file() and p.stat().st_size > 0:
            return p

    for d in (server_jars, JARS_DIR):
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
    server_jars = Path(__file__).parent / "jars"
    candidates = [
        server_jars / "noemtaddons-loader.jar",
        server_jars / f"noemtaddons-loader-{ver}.jar",
        server_jars / "noemtaddons-cheat-loader.jar",
        server_jars / f"noemtaddons-cheat-loader-{ver}.jar",
        JARS_DIR / f"noemtaddons-loader-{ver}.jar",
        JARS_DIR / "noemtaddons-loader.jar",
        JARS_DIR / f"noemtaddons-cheat-loader-{ver}.jar",
        JARS_DIR / "noemtaddons-cheat-loader.jar",
    ]
    for p in candidates:
        if p.exists() and p.is_file() and p.stat().st_size > 0:
            return p

    for d in (server_jars, JARS_DIR):
        if d.exists():
            matches = [
                p for p in d.glob("*loader*.jar")
                if p.is_file() and p.stat().st_size > 0 and "sources" not in p.name.lower()
            ]
            if matches:
                return max(matches, key=lambda x: x.stat().st_mtime)
    return None


def get_sig_path() -> Optional[Path]:
    server_jars = Path(__file__).parent / "jars"
    mod_p = get_jar_path("mod")
    if mod_p:
        sig1 = mod_p.with_name(mod_p.name + ".sig")
        if sig1.exists() and sig1.is_file() and sig1.stat().st_size > 0:
            return sig1
        sig2 = mod_p.with_suffix(".sig")
        if sig2.exists() and sig2.is_file() and sig2.stat().st_size > 0:
            return sig2

    candidates = [
        server_jars / "noemtaddons.jar.sig",
        server_jars / "noemtaddons.sig",
    ]
    for p in candidates:
        if p.exists() and p.is_file() and p.stat().st_size > 0:
            return p

    for d in (server_jars, JARS_DIR):
        if d.exists():
            matches = [p for p in d.glob("*.sig") if p.is_file() and p.stat().st_size > 0]
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
    sig_p = get_sig_path()

    mod_info = get_file_info(mod_p) if mod_p else {"exists": False}
    loader_info = get_file_info(loader_p) if loader_p else {"exists": False}
    sig_info = get_file_info(sig_p) if sig_p else {"exists": False}

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
            },
            "signature": {
                "url": "https://addons.noemt.dev/loaders/noemtaddons.jar.sig",
                "filename": sig_p.name if sig_p else "noemtaddons.jar.sig",
                "sha256": sig_info.get("sha256", ""),
                "size": sig_info.get("size", 0),
                "modified": sig_info.get("modified", "")
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
            logger.warning("Deployment sync already in progress. Trigger skipped.")
            return False

        IS_BUILDING = True
        LAST_BUILD_STATUS = "Syncing..."
        start_time = time.time()
        logger.info(f"📥 Initiating Release Pull Pipeline (Source: {trigger_source})...")

        loop = asyncio.get_event_loop()

        # 1. Pull Git Updates (Latest code and pre-signed JAR artifacts)
        logger.info(f"📥 Pulling latest release commits from origin/{GIT_BRANCH}...")
        pull_res = await loop.run_in_executor(
            None,
            lambda: subprocess.run(["git", "pull", "origin", GIT_BRANCH], cwd=REPO_DIR, capture_output=True, text=True)
        )
        if pull_res.returncode == 0:
            logger.info(f"📥 Git pull succeeded: {pull_res.stdout.strip()}")
        else:
            logger.warning(f"⚠️ Git pull warning: {pull_res.stderr.strip() or pull_res.stdout.strip()}")

        # 2. Clean stale build/libs to prevent serving un-synced legacy local builds
        stale_build_dir = REPO_DIR / "build" / "libs"
        if stale_build_dir.exists() and (Path(__file__).parent / "jars").exists():
            try:
                import shutil
                shutil.rmtree(stale_build_dir, ignore_errors=True)
            except Exception:
                pass

        # 3. Update In-Game Changelog
        short_hash, author, latest_msg = AutoBuilder.get_latest_commit_details()
        formatted_changelog = AutoBuilder.generate_changelog_text(short_hash, commits)
        changelog_path = Path(__file__).parent / "changelog.txt"
        changelog_path.write_text(formatted_changelog, encoding="utf-8")

        # 3. Verify Local Release Artifacts
        meta = compute_version_metadata()
        mod_info = meta.get("endpoints", {}).get("mod", {})
        sig_info = meta.get("endpoints", {}).get("signature", {})

        deploy_duration = round(time.time() - start_time, 2)
        LAST_BUILD_TIME = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        LAST_BUILD_OUTPUT = pull_res.stdout if pull_res.stdout else pull_res.stderr

        if mod_info.get("size", 0) > 5000:
            IS_BUILDING = False
            LAST_BUILD_STATUS = "Healthy"
            mod_size_kb = mod_info.get("size", 0) / 1024
            logger.info(f"✅ Release deployment synced in {deploy_duration}s (Mod: {mod_size_kb:.1f} KB, Sig: {sig_info.get('size', 0)} B)!")

            commit_lines = "\n".join([f"• `{c['hash']}` {c['message']} *(by {c['author']})*" for c in (commits or [{'hash': short_hash, 'message': latest_msg, 'author': author}])[:5]])

            if SERVER_INSTANCE and SERVER_INSTANCE.bot and hasattr(SERVER_INSTANCE.bot, "dispatch"):
                SERVER_INSTANCE.bot.dispatch("build_completed", True, deploy_duration, short_hash, author, latest_msg, mod_size_kb)
            elif DISCORD_BOT_TOKEN and DISCORD_CHANNEL_ID:
                fields = [
                    {"name": "🌿 Branch", "value": f"`{GIT_BRANCH}`", "inline": True},
                    {"name": "Commit", "value": f"`{short_hash}` ({author})", "inline": True},
                    {"name": "Sync Time", "value": f"`{deploy_duration}s`", "inline": True},
                    {"name": "Mod Size", "value": f"`{mod_size_kb:.1f} KB`", "inline": True},
                ]
                send_discord_bot_notification(
                    title=f"Release Deployed (`{short_hash}`)",
                    description=f"**New version pulled & active**\n\n> 📝 *\"{latest_msg}\"*",
                    color=0x34A853,
                    fields=fields
                )

            await send_to_target("all", {
                "type": "MESSAGE",
                "message": f"&aServer updated to release &e{short_hash}&a! Restart game when ready."
            })
            await send_to_target("all", {
                "type": "TITLE",
                "title": "&a&lNoemtAddons Updated",
                "subtitle": f"&eRelease {short_hash} deployed"
            })

            return True
        else:
            IS_BUILDING = False
            LAST_BUILD_STATUS = "Warning: No JAR"
            logger.warning(f"⚠️ Git pull succeeded, but no mod JAR was found in server/jars/ or build/libs/!")
            return True

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
        content = changelog_p.read_text(encoding="utf-8") if changelog_p.exists() else "§bNoemtAddons v1.0.2"
        send_http_response(writer, 200, "text/plain; charset=utf-8", content.encode("utf-8"))
        return

    # 4. Remote Safety Manifest & Anticheat Kill-Switch
    if clean_path in ("/api/manifest", "/api/killswitch", "/manifest.json"):
        manifest_data = {
            "enabled": True,
            "message": "OK",
            "version": get_project_version(),
            "timestamp": int(datetime.now().timestamp())
        }
        send_http_response(writer, 200, "application/json; charset=utf-8", json.dumps(manifest_data, indent=2).encode("utf-8"))
        return

    # 4.5 Player Sizes API (Multiplayer live sync & HTTP failsafe)
    if clean_path in ("/api/player-sizes", "/api/sizes", "/player-sizes"):
        if method == "GET":
            payload = get_all_player_sizes_payload()
            send_http_response(writer, 200, "application/json; charset=utf-8", json.dumps(payload, indent=2).encode("utf-8"))
            return
        elif method == "POST":
            content_len = int(headers.get("content-length", 0))
            body = (await reader.readexactly(content_len)).decode("utf-8", errors="ignore") if content_len > 0 else "{}"
            try:
                data = json.loads(body)
                uuid_v = str(data.get("uuid") or data.get("Uuid") or "")
                name_v = str(data.get("name") or data.get("DevName") or f"Player_{client_ip}")
                scale_v = data.get("scale") or data.get("Size") or [1.0, 1.0, 1.0]
                cname_v = str(data.get("customName") or data.get("CustomName") or "")
                if uuid_v:
                    rec = save_player_size(uuid_v, name_v, scale_v, cname_v)
                    asyncio.create_task(send_to_target("all", {
                        "type": "PLAYER_SIZE_BROADCAST",
                        "uuid": rec["uuid"],
                        "name": rec["name"],
                        "scale": rec["scale"],
                        "customName": rec["customName"],
                        "timestamp": int(time.time() * 1000)
                    }))
                    send_http_response(writer, 200, "application/json", json.dumps({"success": True, "record": rec}).encode("utf-8"))
                    return
                else:
                    send_http_response(writer, 400, "application/json", b'{"error":"Missing uuid field"}')
                    return
            except Exception as err:
                send_http_response(writer, 400, "application/json", json.dumps({"error": str(err)}).encode("utf-8"))
                return

    # 5. Cryptographic Ed25519 Signature Downloads
    if clean_path in ("/loaders/noemtaddons.jar.sig", "/loaders/noemtaddons.sig", "/download/sig", "/download/noemtaddons.jar.sig"):
        serve_sig_file(writer, headers, client_ip)
        return

    # 6. Mod Payload JAR Downloads (Requested by loaders on game startup)
    if clean_path in ("/loaders/noemtaddons.jar", "/download/mod", "/download/noemtaddons.jar",
                      "/loaders/noemtaddons-cheat.jar", "/download/cheat", "/download/noemtaddons-cheat.jar"):
        serve_jar_file(writer, "mod", headers, client_ip)
        return

    # 7. Bootstrap Loader JAR Downloads (The bootstrap loader given to users)
    if clean_path in ("/download/loader", "/download/loaders/cheat", "/download/noemtaddons-loader.jar",
                      "/loaders/noemtaddons-loader.jar", "/loaders/cheat-loader.jar"):
        serve_loader_stub_file(writer, "loader", headers, client_ip)
        return

    # 8. Initial Operator Registration (First-time setup - strictly single-use ever)
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
                "Location: /admin",
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

    # 9. Login POST / GET Request
    if clean_path == "/login":
        if not has_admin_user():
            html = render_register_page()
            send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
            return

        if method == "POST":
            content_len = int(headers.get("content-length", 0))
            body = (await reader.readexactly(content_len)).decode("utf-8", errors="ignore") if content_len > 0 else ""
            form_data = urllib.parse.parse_qs(body)
            username = form_data.get("username", [""])[0].strip()
            password = form_data.get("password", [""])[0].strip()

            if verify_login(username, password):
                session_token = create_session(username)
                logger.info(f"🔑 Successful operator console login for '{username}' from {client_ip}")
                headers_out = [
                    "HTTP/1.1 302 Found",
                    "Location: /admin",
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
        else:
            if is_authenticated(headers):
                headers_out = ["HTTP/1.1 302 Found", "Location: /admin", "Connection: close", "\r\n"]
                writer.write("\r\n".join(headers_out).encode("utf-8"))
                writer.close()
                return
            html = render_login_page()
            send_http_response(writer, 200, "text/html; charset=utf-8", html.encode("utf-8"))
            return

    # 10. Logout GET
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

    # 11. Authenticated Dashboard Remote Action POST
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

        headers_out = ["HTTP/1.1 302 Found", "Location: /admin", "Connection: close", "\r\n"]
        writer.write("\r\n".join(headers_out).encode("utf-8"))
        writer.close()
        return

    # 12. Operator Admin Dashboard GET Route (/admin, /dashboard)
    if clean_path in ("/admin", "/dashboard"):
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

    # 13. Public React Frontend Landing Page (Served at /)
    if clean_path == "/":
        index_file = WEB_DIST_DIR / "index.html"
        if index_file.exists():
            serve_static_file(writer, index_file, headers, client_ip)
            return
        else:
            # Fallback if frontend build not generated yet
            send_http_response(writer, 200, "text/html; charset=utf-8", b"<h1>NoemtAddons React Frontend Building...</h1>")
            return

    # 14. Static Assets & Media (from web/dist or web/public)
    if clean_path.startswith(("/assets/", "/media/")) or clean_path in ("/favicon.ico", "/robots.txt", "/preview-banner.png"):
        rel_path = clean_path.lstrip("/")
        candidate = WEB_DIST_DIR / rel_path
        if not candidate.exists():
            candidate = WEB_PUBLIC_DIR / rel_path
        if candidate.exists() and candidate.is_file():
            serve_static_file(writer, candidate, headers, client_ip)
            return

    # 15. SPA Client-Side Routing Fallback (for any non-API GET request)
    if method == "GET":
        rel_path = clean_path.lstrip("/")
        candidate = WEB_DIST_DIR / rel_path
        if candidate.exists() and candidate.is_file():
            serve_static_file(writer, candidate, headers, client_ip)
            return
        index_file = WEB_DIST_DIR / "index.html"
        if index_file.exists():
            serve_static_file(writer, index_file, headers, client_ip)
            return

    send_http_response(writer, 404, "text/plain", b"404 Not Found")


def serve_static_file(writer: asyncio.StreamWriter, file_path: Path, headers: dict, client_ip: str):
    if not file_path.exists() or not file_path.is_file():
        send_http_response(writer, 404, "text/plain", b"404 Not Found")
        return

    ext = file_path.suffix.lower()
    mime_types = {
        ".html": "text/html; charset=utf-8",
        ".css": "text/css; charset=utf-8",
        ".js": "text/javascript; charset=utf-8",
        ".mjs": "text/javascript; charset=utf-8",
        ".json": "application/json; charset=utf-8",
        ".png": "image/png",
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".svg": "image/svg+xml",
        ".ico": "image/x-icon",
        ".mp4": "video/mp4",
        ".webm": "video/webm",
        ".woff2": "font/woff2",
        ".woff": "font/woff",
        ".ttf": "font/ttf",
        ".txt": "text/plain; charset=utf-8",
    }
    content_type = mime_types.get(ext, "application/octet-stream")
    file_size = file_path.stat().st_size
    mtime = file_path.stat().st_mtime
    http_mtime = email.utils.formatdate(mtime, usegmt=True)

    # HTTP Range Header Handling (Optimized for smooth video playback & seeking)
    range_header = headers.get("range")
    if range_header and range_header.startswith("bytes="):
        try:
            byte_range = range_header.split("=")[1].strip()
            parts = byte_range.split("-")
            start = int(parts[0]) if parts[0] else 0
            end = int(parts[1]) if len(parts) > 1 and parts[1] else file_size - 1

            if start >= file_size:
                headers_out = [
                    "HTTP/1.1 416 Range Not Satisfiable",
                    f"Content-Range: bytes */{file_size}",
                    "Connection: close",
                    "\r\n"
                ]
                writer.write("\r\n".join(headers_out).encode("utf-8"))
                writer.close()
                return

            end = min(end, file_size - 1)
            chunk_length = (end - start) + 1

            headers_out = [
                "HTTP/1.1 206 Partial Content",
                f"Content-Type: {content_type}",
                f"Content-Length: {chunk_length}",
                f"Content-Range: bytes {start}-{end}/{file_size}",
                "Accept-Ranges: bytes",
                f"Last-Modified: {http_mtime}",
                "Access-Control-Allow-Origin: *",
                "Connection: close",
                "\r\n"
            ]
            writer.write("\r\n".join(headers_out).encode("utf-8"))
            with open(file_path, "rb") as f:
                f.seek(start)
                remaining = chunk_length
                while remaining > 0:
                    read_size = min(65536, remaining)
                    chunk = f.read(read_size)
                    if not chunk:
                        break
                    writer.write(chunk)
                    remaining -= len(chunk)
            writer.close()
            return
        except Exception as err:
            logger.warning(f"Static Range handling error: {err}")

    # Full Static File Response
    headers_out = [
        "HTTP/1.1 200 OK",
        f"Content-Type: {content_type}",
        f"Content-Length: {file_size}",
        f"Last-Modified: {http_mtime}",
        "Accept-Ranges: bytes",
        "Cache-Control: public, max-age=3600" if ext != ".html" else "no-cache",
        "Access-Control-Allow-Origin: *",
        "Connection: close",
        "\r\n"
    ]
    writer.write("\r\n".join(headers_out).encode("utf-8"))
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            writer.write(chunk)
    writer.close()


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


def serve_sig_file(writer: asyncio.StreamWriter, headers: dict, client_ip: str):
    sig_path = get_sig_path()
    if not sig_path or not sig_path.exists():
        logger.warning(f"Signature file not found for {client_ip}")
        send_http_response(writer, 404, "text/plain", b"Error: Mod payload Ed25519 signature not found.")
        return

    stat = sig_path.stat()
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

    logger.info(f"📤 Serving Ed25519 payload signature ({file_size} bytes) to {client_ip}")
    headers_out = [
        "HTTP/1.1 200 OK",
        "Content-Type: application/octet-stream",
        f"Content-Length: {file_size}",
        f"Last-Modified: {http_mtime}",
        "X-Signature-Algorithm: Ed25519",
        "Cache-Control: public, no-cache",
        "Access-Control-Allow-Origin: *",
        "Connection: close",
        "\r\n"
    ]
    writer.write("\r\n".join(headers_out).encode("utf-8"))
    writer.write(sig_path.read_bytes())
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
    error_html = f'<div class="alert-error"><span>⚠️</span> {error}</div>' if error else ""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Initial Setup • NoemtAddons Console</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
    <style>
        :root {{
            --bg-base: #121212;
            --bg-surface: #181818;
            --bg-input: #1E1E1E;
            --border-color: #262626;
            --border-focus: #1A73E8;
            --text-primary: #EEEEEE;
            --text-secondary: #9E9E9E;
            --accent-blue: #1A73E8;
            --accent-blue-hover: #1557B0;
            --accent-red: #F28B82;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: var(--bg-base);
            color: var(--text-primary);
            font-family: 'Plus Jakarta Sans', sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
        }}
        .auth-container {{
            width: 100%;
            max-width: 420px;
            background: var(--bg-surface);
            border: 1px solid var(--border-color);
            border-radius: 20px;
            padding: 36px;
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.4);
        }}
        .brand-header {{
            margin-bottom: 24px;
        }}
        .brand-title {{
            font-size: 20px;
            font-weight: 700;
            letter-spacing: -0.3px;
        }}
        .brand-title .white {{ color: #FFFFFF; }}
        .brand-title .blue {{ color: var(--accent-blue); }}
        .brand-subtitle {{
            font-size: 13px;
            color: var(--text-secondary);
            margin-top: 4px;
        }}
        .setup-notice {{
            background: rgba(26, 115, 232, 0.08);
            border: 1px solid rgba(26, 115, 232, 0.25);
            color: #8AB4F8;
            padding: 12px 14px;
            border-radius: 12px;
            font-size: 12px;
            line-height: 1.5;
            margin-bottom: 20px;
        }}
        .alert-error {{
            background: rgba(242, 139, 130, 0.12);
            border: 1px solid rgba(242, 139, 130, 0.3);
            color: var(--accent-red);
            padding: 10px 14px;
            border-radius: 8px;
            font-size: 12px;
            margin-bottom: 16px;
            display: flex;
            align-items: center;
            gap: 8px;
        }}
        .form-group {{
            margin-bottom: 16px;
        }}
        .input-label {{
            display: block;
            font-size: 11px;
            font-weight: 600;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-bottom: 6px;
        }}
        .dark-input {{
            width: 100%;
            background: var(--bg-input);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 11px 14px;
            color: var(--text-primary);
            font-size: 13px;
            font-family: inherit;
            transition: border-color 0.15s ease;
        }}
        .dark-input:focus {{
            outline: none;
            border-color: var(--border-focus);
        }}
        .btn-submit {{
            width: 100%;
            background: var(--accent-blue);
            color: #FFFFFF;
            border: none;
            border-radius: 9999px;
            padding: 11px 20px;
            font-size: 13px;
            font-weight: 600;
            font-family: inherit;
            cursor: pointer;
            transition: all 0.15s ease;
            margin-top: 8px;
        }}
        .btn-submit:hover {{
            background: var(--accent-blue-hover);
        }}
        .footer-note {{
            margin-top: 20px;
            text-align: center;
            font-size: 11px;
            font-family: 'JetBrains Mono', monospace;
            color: var(--text-secondary);
        }}
    </style>
</head>
<body>
    <div class="auth-container">
        <div class="brand-header">
            <div class="brand-title">
                <span class="white">Noemt</span><span class="blue">Addons</span>
            </div>
            <div class="brand-subtitle">Initial Operator Registration</div>
        </div>
        
        <div class="setup-notice">
            <strong>One-Time Setup:</strong> Registration permanently locks after creating this operator account.
        </div>

        {error_html}

        <form method="POST" action="/register">
            <div class="form-group">
                <label class="input-label">Operator Username</label>
                <input type="text" name="username" class="dark-input" placeholder="e.g. nom" minlength="3" required autofocus>
            </div>
            <div class="form-group">
                <label class="input-label">Master Password</label>
                <input type="password" name="password" class="dark-input" placeholder="Min. 6 characters" minlength="6" required>
            </div>
            <div class="form-group">
                <label class="input-label">Confirm Password</label>
                <input type="password" name="confirm_password" class="dark-input" placeholder="Re-enter password" minlength="6" required>
            </div>
            <button type="submit" class="btn-submit">Create Operator Account →</button>
        </form>
        <div class="footer-note">
            SQLite PBKDF2 Identity Vault
        </div>
    </div>
</body>
</html>"""


def render_login_page(error: Optional[str] = None) -> str:
    error_html = f'<div class="alert-error"><span>⚠️</span> {error}</div>' if error else ""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Sign in • NoemtAddons Console</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
    <style>
        :root {{
            --bg-base: #121212;
            --bg-surface: #181818;
            --bg-input: #1E1E1E;
            --border-color: #262626;
            --border-focus: #1A73E8;
            --text-primary: #EEEEEE;
            --text-secondary: #9E9E9E;
            --accent-blue: #1A73E8;
            --accent-blue-hover: #1557B0;
            --accent-red: #F28B82;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: var(--bg-base);
            color: var(--text-primary);
            font-family: 'Plus Jakarta Sans', sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
        }}
        .auth-container {{
            width: 100%;
            max-width: 400px;
            background: var(--bg-surface);
            border: 1px solid var(--border-color);
            border-radius: 20px;
            padding: 36px;
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.4);
        }}
        .brand-header {{
            margin-bottom: 24px;
        }}
        .brand-title {{
            font-size: 20px;
            font-weight: 700;
            letter-spacing: -0.3px;
        }}
        .brand-title .white {{ color: #FFFFFF; }}
        .brand-title .blue {{ color: var(--accent-blue); }}
        .brand-subtitle {{
            font-size: 13px;
            color: var(--text-secondary);
            margin-top: 4px;
        }}
        .alert-error {{
            background: rgba(242, 139, 130, 0.12);
            border: 1px solid rgba(242, 139, 130, 0.3);
            color: var(--accent-red);
            padding: 10px 14px;
            border-radius: 8px;
            font-size: 12px;
            margin-bottom: 16px;
            display: flex;
            align-items: center;
            gap: 8px;
        }}
        .form-group {{
            margin-bottom: 16px;
        }}
        .input-label {{
            display: block;
            font-size: 11px;
            font-weight: 600;
            color: var(--text-secondary);
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-bottom: 6px;
        }}
        .dark-input {{
            width: 100%;
            background: var(--bg-input);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 11px 14px;
            color: var(--text-primary);
            font-size: 13px;
            font-family: inherit;
            transition: border-color 0.15s ease;
        }}
        .dark-input:focus {{
            outline: none;
            border-color: var(--border-focus);
        }}
        .btn-submit {{
            width: 100%;
            background: var(--accent-blue);
            color: #FFFFFF;
            border: none;
            border-radius: 9999px;
            padding: 11px 20px;
            font-size: 13px;
            font-weight: 600;
            font-family: inherit;
            cursor: pointer;
            transition: all 0.15s ease;
            margin-top: 8px;
        }}
        .btn-submit:hover {{
            background: var(--accent-blue-hover);
        }}
        .footer-note {{
            margin-top: 20px;
            text-align: center;
            font-size: 11px;
            font-family: 'JetBrains Mono', monospace;
            color: var(--text-secondary);
        }}
    </style>
</head>
<body>
    <div class="auth-container">
        <div class="brand-header">
            <div class="brand-title">
                <span class="white">Noemt</span><span class="blue">Addons</span>
            </div>
            <div class="brand-subtitle">Operator Console Sign-In</div>
        </div>

        {error_html}

        <form method="POST" action="/login">
            <div class="form-group">
                <label class="input-label">Operator ID</label>
                <input type="text" name="username" class="dark-input" placeholder="Operator username" required autofocus>
            </div>
            <div class="form-group">
                <label class="input-label">Password</label>
                <input type="password" name="password" class="dark-input" placeholder="Operator password" required>
            </div>
            <button type="submit" class="btn-submit">Sign In to Console →</button>
        </form>
        <div class="footer-note">
            NoemtAddons Control Plane
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
                    <div style="display:flex; align-items:center; gap:10px;">
                        <div class="player-avatar">{name[:1].upper()}</div>
                        <div>
                            <div style="font-weight:600; color:var(--text-primary);">{name}</div>
                            <div style="font-size:11px; font-family:'JetBrains Mono',monospace; color:var(--text-secondary);">{info['uuid'][:12]}...</div>
                        </div>
                    </div>
                </td>
                <td><span class="status-pill status-online"><span class="pulse-dot"></span> Online</span></td>
                <td><code class="code-badge">{info['ip']}</code></td>
                <td><span class="version-chip">v{info['version']}</span></td>
                <td style="color:var(--text-secondary); font-size:12px; font-family:'JetBrains Mono',monospace;">{info['connected_at']}</td>
                <td style="text-align:right;">
                    <form method="POST" action="/api/action" style="display:inline;" onsubmit="return confirm('Emergency close Minecraft for {name}?');">
                        <input type="hidden" name="action" value="kill">
                        <input type="hidden" name="target" value="{name}">
                        <button type="submit" class="btn-danger-sm">Close Game</button>
                    </form>
                </td>
            </tr>
            """
    else:
        player_rows = '<tr><td colspan="6" style="text-align:center; padding: 40px 16px; color: var(--text-secondary);"><div style="font-size:13px; font-weight:500;">No Active Client Instances Connected</div><div style="font-size:11px; color:#666666; margin-top:4px;">Launch Minecraft with NoemtAddons to establish live WebSocket link.</div></td></tr>'

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>NoemtAddons • Operator Console</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
    <style>
        :root {{
            --bg-base: #121212;
            --bg-surface: #181818;
            --bg-elevated: #1E1E1E;
            --bg-input: #1E1E1E;
            --border-color: #262626;
            --border-subtle: #1F1F1F;
            --text-primary: #EEEEEE;
            --text-secondary: #9E9E9E;
            --accent-blue: #1A73E8;
            --accent-blue-hover: #1557B0;
            --accent-blue-light: #8AB4F8;
            --accent-red: #E63946;
            --accent-green: #34D399;
        }}
        * {{ box-sizing: border-box; margin: 0; padding: 0; }}
        body {{
            background: var(--bg-base);
            color: var(--text-primary);
            font-family: 'Plus Jakarta Sans', sans-serif;
            min-height: 100vh;
            -webkit-font-smoothing: antialiased;
        }}
        /* Top Navigation Bar */
        .top-navbar {{
            height: 56px;
            background: rgba(18, 18, 18, 0.95);
            border-bottom: 1px solid var(--border-color);
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 0 24px;
            position: sticky;
            top: 0;
            z-index: 100;
            backdrop-filter: blur(12px);
        }}
        .nav-left {{
            display: flex;
            align-items: center;
            gap: 16px;
        }}
        .brand-link {{
            font-size: 16px;
            font-weight: 700;
            text-decoration: none;
            letter-spacing: -0.3px;
        }}
        .brand-link .white {{ color: #FFFFFF; }}
        .brand-link .blue {{ color: var(--accent-blue); }}
        .brand-pill {{
            font-size: 10px;
            font-family: 'JetBrains Mono', monospace;
            color: var(--text-secondary);
            background: var(--bg-elevated);
            border: 1px solid var(--border-color);
            border-radius: 9999px;
            padding: 2px 8px;
        }}
        .nav-search {{
            flex: 1;
            max-width: 360px;
            margin: 0 20px;
        }}
        .search-input {{
            width: 100%;
            background: var(--bg-input);
            border: 1px solid var(--border-color);
            border-radius: 9999px;
            padding: 6px 14px;
            color: var(--text-primary);
            font-size: 12px;
            font-family: inherit;
            transition: border-color 0.15s ease;
        }}
        .search-input:focus {{
            outline: none;
            border-color: var(--accent-blue);
        }}
        .nav-right {{
            display: flex;
            align-items: center;
            gap: 12px;
        }}
        .user-chip {{
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 12px;
            font-weight: 500;
            color: var(--text-primary);
        }}
        .user-avatar {{
            width: 28px;
            height: 28px;
            border-radius: 50%;
            background: var(--accent-blue);
            color: #fff;
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 700;
            font-size: 11px;
        }}
        /* Main Container */
        .container {{
            max-width: 1180px;
            margin: 0 auto;
            padding: 32px 24px;
        }}
        .page-header {{
            display: flex;
            justify-content: space-between;
            align-items: flex-end;
            margin-bottom: 28px;
            flex-wrap: wrap;
            gap: 16px;
        }}
        .page-title h1 {{
            font-size: 24px;
            font-weight: 700;
            letter-spacing: -0.4px;
        }}
        .page-title p {{
            font-size: 13px;
            color: var(--text-secondary);
            margin-top: 4px;
        }}
        .btn-group {{
            display: flex;
            align-items: center;
            gap: 8px;
        }}
        /* Buttons */
        .btn-blue {{
            background: var(--accent-blue);
            color: #FFFFFF;
            border: none;
            border-radius: 9999px;
            padding: 8px 16px;
            font-size: 12px;
            font-weight: 600;
            font-family: inherit;
            cursor: pointer;
            text-decoration: none;
            display: inline-flex;
            align-items: center;
            gap: 6px;
            transition: all 0.15s ease;
        }}
        .btn-blue:hover {{
            background: var(--accent-blue-hover);
        }}
        .btn-secondary {{
            background: var(--bg-surface);
            color: var(--text-primary);
            border: 1px solid var(--border-color);
            border-radius: 9999px;
            padding: 7px 14px;
            font-size: 12px;
            font-weight: 500;
            font-family: inherit;
            cursor: pointer;
            text-decoration: none;
            display: inline-flex;
            align-items: center;
            gap: 6px;
            transition: all 0.15s ease;
        }}
        .btn-secondary:hover {{
            background: var(--bg-elevated);
            border-color: #383838;
            color: #FFFFFF;
        }}
        .btn-danger-sm {{
            background: rgba(230, 57, 70, 0.12);
            color: #FFA8B5;
            border: 1px solid rgba(230, 57, 70, 0.3);
            border-radius: 6px;
            padding: 4px 10px;
            font-size: 11px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.15s ease;
        }}
        .btn-danger-sm:hover {{
            background: #E63946;
            color: #FFFFFF;
        }}
        .btn-danger-full {{
            background: #E63946;
            color: #FFFFFF;
            border: none;
            border-radius: 8px;
            padding: 9px 16px;
            font-size: 12px;
            font-weight: 600;
            cursor: pointer;
            width: 100%;
            transition: background 0.15s ease;
        }}
        .btn-danger-full:hover {{
            background: #C92A37;
        }}
        /* Stats Grid */
        .stats-grid {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
            gap: 12px;
            margin-bottom: 28px;
        }}
        .stat-box {{
            background: var(--bg-surface);
            border: 1px solid var(--border-color);
            border-radius: 14px;
            padding: 16px 20px;
        }}
        .stat-label {{
            font-size: 11px;
            color: var(--text-secondary);
            font-weight: 500;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }}
        .stat-value {{
            font-size: 18px;
            font-weight: 700;
            margin-top: 6px;
            display: flex;
            align-items: center;
            gap: 8px;
        }}
        /* Tables & Sections */
        .section-box {{
            background: var(--bg-surface);
            border: 1px solid var(--border-color);
            border-radius: 16px;
            overflow: hidden;
            margin-bottom: 24px;
        }}
        .section-header {{
            padding: 16px 20px;
            border-bottom: 1px solid var(--border-color);
            display: flex;
            justify-content: space-between;
            align-items: center;
        }}
        .section-title {{
            font-size: 14px;
            font-weight: 600;
            color: var(--text-primary);
        }}
        table {{
            width: 100%;
            border-collapse: collapse;
            font-size: 12px;
            text-align: left;
        }}
        th {{
            background: rgba(255, 255, 255, 0.02);
            color: var(--text-secondary);
            font-size: 11px;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            padding: 12px 20px;
            border-bottom: 1px solid var(--border-color);
        }}
        td {{
            padding: 14px 20px;
            border-bottom: 1px solid var(--border-subtle);
            color: var(--text-primary);
        }}
        tr:last-child td {{
            border-bottom: none;
        }}
        tr:hover td {{
            background: rgba(255, 255, 255, 0.015);
        }}
        .player-avatar {{
            width: 26px;
            height: 26px;
            border-radius: 6px;
            background: var(--bg-elevated);
            border: 1px solid var(--border-color);
            color: var(--accent-blue-light);
            display: flex;
            align-items: center;
            justify-content: center;
            font-weight: 700;
            font-size: 11px;
        }}
        .status-pill {{
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: 3px 8px;
            border-radius: 9999px;
            font-size: 11px;
            font-weight: 600;
        }}
        .status-online {{
            background: rgba(52, 211, 153, 0.12);
            color: var(--accent-green);
        }}
        .pulse-dot {{
            width: 5px;
            height: 5px;
            border-radius: 50%;
            background: currentColor;
        }}
        .code-badge {{
            font-family: 'JetBrains Mono', monospace;
            background: var(--bg-elevated);
            padding: 2px 6px;
            border-radius: 4px;
            font-size: 11px;
            color: #CCCCCC;
        }}
        .version-chip {{
            font-family: 'JetBrains Mono', monospace;
            font-size: 11px;
            color: var(--accent-blue-light);
        }}
        /* Two Column Grid */
        .split-grid {{
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
        }}
        @media (max-width: 860px) {{
            .split-grid {{
                grid-template-columns: 1fr;
            }}
        }}
        .form-control {{
            width: 100%;
            background: var(--bg-input);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 9px 12px;
            color: var(--text-primary);
            font-size: 12px;
            font-family: inherit;
            margin-bottom: 12px;
        }}
        .form-control:focus {{
            outline: none;
            border-color: var(--accent-blue);
        }}
        .form-label {{
            font-size: 11px;
            font-weight: 600;
            color: var(--text-secondary);
            display: block;
            margin-bottom: 5px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }}
        /* Toast */
        #toast {{
            visibility: hidden;
            background: #1E1E1E;
            color: #8AB4F8;
            border: 1px solid var(--accent-blue);
            text-align: center;
            border-radius: 9999px;
            padding: 10px 20px;
            position: fixed;
            z-index: 1000;
            left: 50%;
            bottom: 30px;
            transform: translateX(-50%);
            font-size: 12px;
            font-weight: 600;
            box-shadow: 0 4px 16px rgba(0,0,0,0.5);
            opacity: 0;
            transition: opacity 0.2s, visibility 0.2s;
        }}
        #toast.show {{
            visibility: visible;
            opacity: 1;
        }}
    </style>
</head>
<body>
    <div id="toast">Copied to clipboard!</div>

    <!-- Navigation Top Bar -->
    <div class="top-navbar">
        <div class="nav-left">
            <a href="/admin" class="brand-link">
                <span class="white">Noemt</span><span class="blue">Addons</span>
            </a>
            <span class="brand-pill">Console • Fabric 26.1.2</span>
        </div>
        <div class="nav-search">
            <input type="text" class="search-input" id="searchFilter" onkeyup="filterTable()" placeholder="Filter client instances...">
        </div>
        <div class="nav-right">
            <a href="/" target="_blank" class="btn-secondary" style="padding:5px 12px; font-size:11px;">Public Site ↗</a>
            <div class="user-chip">
                <div class="user-avatar" title="Operator: {current_user}">{current_user[:1].upper()}</div>
                <span>{current_user}</span>
            </div>
            <a href="/logout" class="btn-secondary" style="padding:5px 10px; font-size:11px;">Sign Out</a>
        </div>
    </div>

    <!-- Main Content -->
    <div class="container">
        <div class="page-header">
            <div class="page-title">
                <h1>Mod Telemetry & Control Plane</h1>
                <p>Operator: <b>{current_user}</b> • Status: <b>Healthy</b></p>
            </div>
            <div class="btn-group">
                <form method="POST" action="/api/action" style="display:inline;">
                    <input type="hidden" name="action" value="build">
                    <button type="submit" class="btn-blue">⚡ Instant Pull & Sync</button>
                </form>
                <a href="/changelog" class="btn-secondary" target="_blank">View Changelog</a>
            </div>
        </div>

        <!-- Metric Stat Boxes -->
        <div class="stats-grid">
            <div class="stat-box">
                <div class="stat-label">Active Clients</div>
                <div class="stat-value">
                    <span>{connected_count}</span>
                    <span class="status-pill status-online"><span class="pulse-dot"></span> {connected_count} online</span>
                </div>
            </div>
            <div class="stat-box">
                <div class="stat-label">Git Branch / Commit</div>
                <div class="stat-value" style="font-family:'JetBrains Mono',monospace; font-size:14px;">
                    <code>{short_hash}</code>
                    <span style="font-size:11px; color:var(--text-secondary); font-weight:normal;">({GIT_BRANCH})</span>
                </div>
            </div>
            <div class="stat-box">
                <div class="stat-label">Pipeline Status</div>
                <div class="stat-value" style="color: {'var(--accent-green)' if LAST_BUILD_STATUS == 'Healthy' else 'var(--accent-red)'}; font-size:15px;">
                    {LAST_BUILD_STATUS}
                </div>
            </div>
            <div class="stat-box">
                <div class="stat-label">Last Release Sync</div>
                <div class="stat-value" style="font-size: 13px; font-family:'JetBrains Mono',monospace; color:var(--text-secondary);">
                    {LAST_BUILD_TIME}
                </div>
            </div>
        </div>

        <!-- Connected Clients Table -->
        <div class="section-box">
            <div class="section-header">
                <span class="section-title">Connected Client Instances ({len(clients)})</span>
                <span style="font-size:11px; font-family:'JetBrains Mono',monospace; color:var(--text-secondary);">WebSocket Telemetry</span>
            </div>
            <table id="instancesTable">
                <thead>
                    <tr>
                        <th>Client Instance</th>
                        <th>Status</th>
                        <th>IP Address</th>
                        <th>Version</th>
                        <th>Connected At</th>
                        <th style="text-align:right;">Action</th>
                    </tr>
                </thead>
                <tbody>
                    {player_rows}
                </tbody>
            </table>
        </div>

        <!-- Builds & Distribution -->
        <div class="section-box">
            <div class="section-header">
                <span class="section-title">Mod Build Endpoints & Artifacts</span>
                <span style="font-size:11px; font-family:'JetBrains Mono',monospace; color:var(--text-secondary);">HTTP 304 Cache Enabled</span>
            </div>
            <table>
                <thead>
                    <tr>
                        <th>Component</th>
                        <th>Endpoint</th>
                        <th>Size</th>
                        <th>Checksum</th>
                        <th style="text-align:right;">Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td><span class="status-pill status-online">MOD PAYLOAD</span></td>
                        <td>
                            <code class="code-badge">/loaders/noemtaddons.jar</code>
                        </td>
                        <td style="font-family:'JetBrains Mono',monospace;">{meta['endpoints']['mod']['size'] / 1024:.1f} KB</td>
                        <td><small style="color:var(--text-secondary); font-family:'JetBrains Mono',monospace;">{meta['endpoints']['mod']['sha256'][:14]}...</small></td>
                        <td style="text-align:right;">
                            <button class="btn-secondary" style="padding:3px 8px; font-size:11px;" onclick="copyText('https://addons.noemt.dev/loaders/noemtaddons.jar')">Copy URL</button>
                            <a href="/download/mod" class="btn-blue" style="padding:3px 10px; font-size:11px; margin-left:4px;">Download</a>
                        </td>
                    </tr>
                    <tr>
                        <td><span class="status-pill" style="background:rgba(26,115,232,0.15); color:var(--accent-blue-light);">BOOTSTRAP LOADER</span></td>
                        <td>
                            <code class="code-badge">/download/loader</code>
                        </td>
                        <td style="font-family:'JetBrains Mono',monospace;">{meta['endpoints']['loader']['size'] / 1024:.1f} KB</td>
                        <td><small style="color:var(--text-secondary); font-family:'JetBrains Mono',monospace;">{meta['endpoints']['loader']['sha256'][:14]}...</small></td>
                        <td style="text-align:right;">
                            <button class="btn-secondary" style="padding:3px 8px; font-size:11px;" onclick="copyText('https://addons.noemt.dev/download/loader')">Copy URL</button>
                            <a href="/download/loader" class="btn-blue" style="padding:3px 10px; font-size:11px; margin-left:4px;">Download</a>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>

        <!-- Split Grid: Remote Control & Webhook -->
        <div class="split-grid">
            <!-- Remote Command Dispatch -->
            <div class="section-box" style="padding: 20px;">
                <span class="section-title" style="display:block; margin-bottom:16px;">In-Game Remote Dispatch</span>
                <form method="POST" action="/api/action">
                    <label class="form-label">Action Type</label>
                    <select name="action" class="form-control">
                        <option value="msg">💬 Chat Message</option>
                        <option value="title">🔔 Screen Title Alert</option>
                        <option value="chat">⚡ Execute In-Game Command</option>
                    </select>

                    <label class="form-label">Target Player</label>
                    <input type="text" name="target" class="form-control" value="all" placeholder="Player IGN or 'all'">

                    <label class="form-label">Payload Content</label>
                    <input type="text" name="text" class="form-control" placeholder="Message or command" required>

                    <button type="submit" class="btn-blue" style="width:100%; justify-content:center; padding:10px;">Dispatch to Client →</button>
                </form>
            </div>

            <!-- Emergency Failsafe & Webhook -->
            <div class="section-box" style="padding: 20px;">
                <span class="section-title" style="display:block; margin-bottom:16px; color: #FFA8B5;">Emergency Failsafe & Webhook</span>
                
                <form method="POST" action="/api/action" onsubmit="return confirm('Trigger emergency shutdown for selected target?');" style="margin-bottom:20px;">
                    <input type="hidden" name="action" value="kill">
                    <label class="form-label">Emergency Client Shutdown</label>
                    <div style="display:flex; gap:8px;">
                        <input type="text" name="target" class="form-control" value="all" placeholder="Player IGN or 'all'" style="margin-bottom:0;" required>
                        <button type="submit" class="btn-danger-full" style="width:auto; white-space:nowrap;">Close Client</button>
                    </div>
                </form>

                <div style="border-top:1px solid var(--border-color); pt-4; padding-top:16px;">
                    <label class="form-label">GitHub CI/CD Webhook URL</label>
                    <div style="display:flex; gap:8px; align-items:center;">
                        <input type="text" class="form-control" style="margin-bottom:0; font-family:'JetBrains Mono',monospace; font-size:11px;" value="https://addons.noemt.dev/api/webhook" readonly id="webhookInput">
                        <button class="btn-secondary" style="white-space:nowrap; padding:9px 14px;" onclick="copyText(document.getElementById('webhookInput').value)">Copy</button>
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

                    # Send all player sizes upon handshake
                    await send_ws_json(writer, {
                        "type": "PLAYER_SIZE_SYNC",
                        "players": get_all_player_sizes_payload()
                    })

                elif msg_type in ("PLAYER_SIZE_UPDATE", "SET_PLAYER_SIZE"):
                    uuid_val = data.get("uuid") or data.get("Uuid") or (clients[player_name]["uuid"] if player_name in clients else "")
                    name_val = data.get("name") or data.get("DevName") or player_name
                    scale_val = data.get("scale") or data.get("Size") or [1.0, 1.0, 1.0]
                    cname_val = data.get("customName") or data.get("CustomName") or ""
                    if uuid_val:
                        rec = save_player_size(str(uuid_val), str(name_val), scale_val, str(cname_val))
                        logger.info(f"📏 Player Size updated for {name_val} ({uuid_val}): {rec['scale']}")
                        await send_to_target("all", {
                            "type": "PLAYER_SIZE_BROADCAST",
                            "uuid": rec["uuid"],
                            "name": rec["name"],
                            "scale": rec["scale"],
                            "customName": rec["customName"],
                            "timestamp": int(datetime.now().timestamp() * 1000)
                        })

                elif msg_type == "PLAYER_SIZE_QUERY":
                    await send_ws_json(writer, {
                        "type": "PLAYER_SIZE_SYNC",
                        "players": get_all_player_sizes_payload()
                    })

                elif msg_type in ("PLAYER_SIZE_RESET", "PLAYER_SIZE_REMOVE"):
                    uuid_val = data.get("uuid") or (clients[player_name]["uuid"] if player_name in clients else "")
                    if uuid_val:
                        delete_player_size(str(uuid_val))
                        logger.info(f"📏 Player Size reset for {player_name} ({uuid_val})")
                        await send_to_target("all", {
                            "type": "PLAYER_SIZE_RESET",
                            "uuid": str(uuid_val)
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
  size <player|uuid> <scale>          - Set player uniform scale (e.g. size nom 1.5)
  size <player|uuid> <x> <y> <z>      - Set player XYZ scale (e.g. size nom 1.2 0.8 1.2)
  size reset <player|uuid>            - Reset player scale override
  sizes                               - List all synchronized player scales
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

            elif cmd in ("sizes", "player-sizes", "playersizes"):
                if not player_sizes:
                    print("No player sizes recorded.")
                else:
                    print(f"\n--- Synchronized Player Sizes ({len(player_sizes)}) ---")
                    for u, info in player_sizes.items():
                        c_str = f" ({info['customName']})" if info.get('customName') else ""
                        print(f"  • {info['name']}{c_str} | Scale: {info['scale']} | UUID: {info['uuid']}")
                    print()

            elif cmd == "size":
                sub = args.split(" ")
                if len(sub) < 2:
                    print("Usage: size <player|uuid> <scale> OR size <player|uuid> <x> <y> <z> OR size reset <player|uuid>")
                    continue
                if sub[0].lower() == "reset":
                    target = sub[1]
                    target_uuid = None
                    if target in clients:
                        target_uuid = clients[target]["uuid"]
                    else:
                        for u, p in player_sizes.items():
                            if p["name"].lower() == target.lower() or u == target.lower():
                                target_uuid = p["uuid"]
                                break
                    if target_uuid:
                        delete_player_size(target_uuid)
                        await send_to_target("all", {"type": "PLAYER_SIZE_RESET", "uuid": target_uuid})
                        print(f"Reset size for '{target}' ({target_uuid}).")
                    else:
                        print(f"Player '{target}' not found.")
                    continue

                target = sub[0]
                target_uuid = target
                target_name = target
                if target in clients:
                    target_uuid = clients[target]["uuid"]
                    target_name = target
                else:
                    for u, p in player_sizes.items():
                        if p["name"].lower() == target.lower() or u == target.lower():
                            target_uuid = p["uuid"]
                            target_name = p["name"]
                            break

                try:
                    if len(sub) == 2:
                        val = float(sub[1])
                        scale = [val, val, val]
                    elif len(sub) >= 4:
                        scale = [float(sub[1]), float(sub[2]), float(sub[3])]
                    else:
                        print("Usage: size <player|uuid> <scale> OR size <player|uuid> <x> <y> <z>")
                        continue
                except ValueError:
                    print("Scale values must be numbers.")
                    continue

                rec = save_player_size(target_uuid, target_name, scale)
                await send_to_target("all", {
                    "type": "PLAYER_SIZE_BROADCAST",
                    "uuid": rec["uuid"],
                    "name": rec["name"],
                    "scale": rec["scale"],
                    "customName": rec["customName"],
                    "timestamp": int(time.time() * 1000)
                })
                print(f"Updated size for '{target_name}' ({target_uuid}) to {scale} and broadcasted to all clients.")

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
        print(f"    Public Site:     http://{host}:{port}/")
        print(f"    Admin Dashboard: http://{host}:{port}/admin")
        if has_admin_user():
            print(f"    Operator:        {get_admin_username()} (Protected)")
        else:
            print("    Status:          Initial Setup Required (Open /admin to register)")
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
    parser.add_argument("--jars-dir", default=None, help="Directory containing built jars")
    parser.add_argument("--branch", default="master", help="Git branch to track")
    parser.add_argument("--poll-interval", type=int, default=0, help="Git polling interval in seconds")
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
    print(f"    Public Site:     http://{args.host}:{args.port}/")
    print(f"    Admin Dashboard: http://{args.host}:{args.port}/admin")
    if has_admin_user():
        print(f"    Operator:        {get_admin_username()} (Protected)")
    else:
        print("    Status:          Initial Setup Required (Open /admin to register)")
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
