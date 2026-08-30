import os
import sys
import asyncio
import logging
import traceback
from datetime import datetime
from typing import Optional, List
from bot.util.constants import GUILD_IDS, get_guild_ids

logger = logging.getLogger("NoemtBot")

try:
    import discord
    from discord.ext import commands
    HAS_DISCORD = True
except ImportError:
    HAS_DISCORD = False
    commands = object


class BotBase:
    pass


class Bot(commands.Bot if HAS_DISCORD else BotBase):
    def __init__(self, *args, **kwargs):
        if HAS_DISCORD:
            super().__init__(*args, **kwargs)
        self.bot_name = "NoemtAddons-Bot"
        self.server = None  # Reference to the API / WebSocket server instance
        self.channel_id: Optional[str] = os.getenv("DISCORD_CHANNEL_ID")
        self.guild_ids: Optional[List[int]] = GUILD_IDS or get_guild_ids()

    def load_cogs(self):
        if not HAS_DISCORD:
            return
        cogs_dir = os.path.join(os.path.dirname(__file__), "cogs")
        if not os.path.exists(cogs_dir):
            return

        for filename in sorted(os.listdir(cogs_dir)):
            if filename.endswith(".py") and not filename.startswith("__"):
                ext_name = f"bot.cogs.{filename[:-3]}"
                try:
                    self.load_extension(ext_name)
                    logger.info(f"Loaded cog extension: {ext_name}")
                except Exception as e:
                    # Fallback to direct import setup
                    try:
                        mod = __import__(ext_name, fromlist=["setup"])
                        if hasattr(mod, "setup"):
                            mod.setup(self)
                        logger.info(f"Loaded cog via direct setup: {ext_name}")
                    except Exception as e2:
                        logger.error(f"Failed to load cog '{ext_name}': {e2}\n{traceback.format_exc()}")

    async def on_ready(self):
        logger.info(f"🤖 NoemtAddons Bot connected as {self.user} (ID: {self.user.id})")
        if self.server:
            logger.info(f"Connected to integrated server API on port {self.server.port}")
        if self.guild_ids:
            logger.info(f"Application commands synced to Guild IDs: {self.guild_ids}")

        await self.change_presence(
            activity=discord.Activity(type=discord.ActivityType.watching, name="NoemtAddons Telemetry"),
            status=discord.Status.online
        )

        channel = self.get_notification_channel()
        if channel:
            embed = discord.Embed(
                title="🟢 NoemtAddons Control Plane & Bot Online",
                description="Integrated Cloud Control Plane, WebSocket & Application Commands active.",
                color=0x34A853,
                timestamp=datetime.utcnow()
            )
            embed.set_footer(text="NoemtAddons Control Plane")
            try:
                await channel.send(embed=embed)
            except Exception as e:
                logger.warning(f"Could not send startup embed to channel: {e}")

    def get_notification_channel(self):
        if not HAS_DISCORD or not self.channel_id:
            return None
        try:
            return self.get_channel(int(self.channel_id))
        except Exception:
            return None

    def run(self, app, port: int = 8765, host: str = "0.0.0.0"):
        token = os.getenv("DISCORD_BOT_TOKEN") or os.getenv("TOKEN")
        app.bot = self
        self.server = app
        app.port = port
        app.host = host

        # Set event loop
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        if HAS_DISCORD:
            self.loop = loop

        # Start the integrated WebSocket & HTTP Server
        loop.create_task(app.run_task(host=host, port=port))

        # Start the Py-Cord Discord bot if library & token are available
        if HAS_DISCORD:
            if token:
                self.load_cogs()
                loop.create_task(self.start(token))
                logger.info("Discord Bot task registered on asyncio event loop.")
            else:
                logger.warning("⚠️ DISCORD_BOT_TOKEN is not set. API server is running, waiting for token.")
        else:
            logger.warning("⚠️ py-cord is not installed. API server is running without Discord bot. Run 'pip install py-cord' to enable bot.")

        try:
            loop.run_forever()
        except KeyboardInterrupt:
            logger.info("Server shutting down.")


def create_bot() -> Bot:
    guild_ids = get_guild_ids()
    if HAS_DISCORD:
        intents = discord.Intents.default()
        intents.message_content = True
        return Bot(command_prefix=["!", ">"], intents=intents, help_command=None, debug_guilds=guild_ids)
    return Bot()
