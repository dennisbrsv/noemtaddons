import discord
from discord.ext import commands
from discord import SlashCommandGroup, option, slash_command
from datetime import datetime
from bot.util.constants import GUILD_IDS, is_authorized


class TelemetryCog(commands.Cog, name="Telemetry"):
    def __init__(self, bot):
        self.bot = bot

    telemetry = SlashCommandGroup(
        "telemetry",
        "View server telemetry and mod status",
        guild_ids=GUILD_IDS
    )

    @telemetry.command(name="help", description="Overview of all available operator slash commands")
    async def cmd_help(self, ctx: discord.ApplicationContext):
        embed = discord.Embed(
            title="🎮 NoemtAddons Control Plane Commands",
            description="Integrated Py-Cord Application Commands bound to your server.",
            color=0x8AB4F8,
            timestamp=datetime.utcnow()
        )
        embed.add_field(
            name="📊 Telemetry Commands",
            value=(
                "`/telemetry status` (or `/status`) - Server status & build health\n"
                "`/telemetry players` (or `/players`) - List active Minecraft client instances\n"
                "`/telemetry info` - Download URLs & SHA256 checksums\n"
                "`/telemetry logs` - Recent Gradle compilation logs"
            ),
            inline=False
        )
        embed.add_field(
            name="⚡ CI/CD & Build Pipeline",
            value="`/control build` - Trigger instant git pull & Gradle compilation",
            inline=False
        )
        embed.add_field(
            name="🛑 Remote Player Management",
            value=(
                "`/control kill [target]` - Emergency close game client\n"
                "`/control msg <target> <text>` - Send mod chat message\n"
                "`/control chat <target> <command>` - Dispatch command/chat\n"
                "`/control title <target> <title> [subtitle]` - Send screen title alert\n"
                "`/control goto <target> <x> <y> <z>` - Direct pathfinder\n"
                "`/control stop [target]` - Halt pathfinding\n"
                "`/control broadcast <message>` - Broadcast message to all players"
            ),
            inline=False
        )
        embed.set_footer(text="NoemtAddons Management Bot")
        await ctx.respond(embed=embed, ephemeral=True)

    @telemetry.command(name="status", description="Live server & mod telemetry")
    @is_authorized()
    async def cmd_status(self, ctx: discord.ApplicationContext):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        meta = server.compute_version_metadata()
        connected_count = len(server.clients)
        build_status = meta.get("build_status", "Unknown")
        is_healthy = build_status == "Healthy"

        embed = discord.Embed(
            title="🌐 NoemtAddons Server Status",
            color=0x34A853 if is_healthy else 0xEA4335,
            timestamp=datetime.utcnow()
        )
        embed.add_field(name="📦 Mod Version", value=f"`v{meta.get('version', '1.0.2')}`", inline=True)
        embed.add_field(name="🔨 Build Status", value=f"`{build_status}`", inline=True)
        embed.add_field(name="👥 Online Players", value=f"`{connected_count}`", inline=True)
        embed.add_field(name="🕒 Last Build Time", value=f"`{meta.get('last_build', 'N/A')}`", inline=True)
        embed.add_field(name="🌿 Git Branch", value=f"`{server.GIT_BRANCH}`", inline=True)

        endpoints = meta.get("endpoints", {})
        if "mod" in endpoints:
            mod_info = endpoints["mod"]
            size_kb = mod_info.get("size", 0) / 1024
            sha = mod_info.get("sha256", "")[:12]
            embed.add_field(name="📁 Mod Artifact", value=f"Size: `{size_kb:.1f} KB` | SHA: `{sha}`", inline=False)

        embed.set_footer(text="Integrated In-Memory Control Plane")
        await ctx.respond(embed=embed)

    @telemetry.command(name="players", description="List active connected Minecraft client instances")
    @is_authorized()
    async def cmd_players(self, ctx: discord.ApplicationContext):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        clients = server.clients
        if not clients:
            embed = discord.Embed(
                title="👥 Connected Players (0)",
                description="No Minecraft client instances currently connected.",
                color=0x8AB4F8,
                timestamp=datetime.utcnow()
            )
            embed.set_footer(text="NoemtAddons Telemetry")
            return await ctx.respond(embed=embed)

        embed = discord.Embed(
            title=f"👥 Connected Players ({len(clients)})",
            color=0x34A853,
            timestamp=datetime.utcnow()
        )

        for name, info in clients.items():
            value_str = (
                f"**UUID:** `{info.get('uuid', 'N/A')}`\n"
                f"**IP:** `{info.get('ip', 'N/A')}`\n"
                f"**Mod:** `v{info.get('version', '1.0.2')}`\n"
                f"**Connected:** `{info.get('connected_at', 'N/A')}`"
            )
            embed.add_field(name=f"🎮 {name}", value=value_str, inline=False)

        embed.set_footer(text="NoemtAddons Telemetry")
        await ctx.respond(embed=embed)

    @telemetry.command(name="info", description="View build artifact endpoints and SHA256 checksums")
    @is_authorized()
    async def cmd_info(self, ctx: discord.ApplicationContext):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        meta = server.compute_version_metadata()
        embed = discord.Embed(
            title="📦 NoemtAddons Distribution Info",
            color=0x8AB4F8,
            timestamp=datetime.utcnow()
        )
        endpoints = meta.get("endpoints", {})
        if "mod" in endpoints:
            mod = endpoints["mod"]
            embed.add_field(
                name="📥 Mod Payload Endpoint",
                value=f"`{mod.get('url')}`\nSize: `{mod.get('size', 0)/1024:.1f} KB`\nSHA256: `{mod.get('sha256')}`",
                inline=False
            )
        if "loader" in endpoints:
            loader = endpoints["loader"]
            embed.add_field(
                name="🚀 Bootstrap Loader Endpoint",
                value=f"`{loader.get('url')}`\nSize: `{loader.get('size', 0)/1024:.1f} KB`\nSHA256: `{loader.get('sha256')}`",
                inline=False
            )

        embed.set_footer(text=f"Repository Branch: {server.GIT_BRANCH}")
        await ctx.respond(embed=embed)

    @telemetry.command(name="logs", description="View recent Gradle compilation output")
    @is_authorized()
    async def cmd_logs(self, ctx: discord.ApplicationContext):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        logs = server.LAST_BUILD_OUTPUT[-1500:] if server.LAST_BUILD_OUTPUT else "No recent build output recorded."
        embed = discord.Embed(
            title="📜 Recent Compilation Logs",
            description=f"```\n{logs}\n```",
            color=0x8AB4F8,
            timestamp=datetime.utcnow()
        )
        embed.set_footer(text=f"Last build: {server.LAST_BUILD_TIME}")
        await ctx.respond(embed=embed, ephemeral=True)

    # Top-Level Direct Slash Commands for Quick Access
    @slash_command(name="status", description="Live server & mod telemetry", guild_ids=GUILD_IDS)
    @is_authorized()
    async def direct_status(self, ctx: discord.ApplicationContext):
        await self.cmd_status(ctx)

    @slash_command(name="players", description="List active connected Minecraft clients", guild_ids=GUILD_IDS)
    @is_authorized()
    async def direct_players(self, ctx: discord.ApplicationContext):
        await self.cmd_players(ctx)


def setup(bot):
    bot.add_cog(TelemetryCog(bot))
