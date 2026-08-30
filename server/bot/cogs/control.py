import discord
from discord.ext import commands
from discord import SlashCommandGroup, option, slash_command
from datetime import datetime
from bot.util.constants import GUILD_IDS


class ControlCog(commands.Cog, name="Control"):
    def __init__(self, bot):
        self.bot = bot

    control = SlashCommandGroup(
        "control",
        "Manage NoemtAddons Minecraft clients, execution, and CI/CD builds",
        guild_ids=GUILD_IDS
    )

    @control.command(name="build", description="Trigger instant git pull & gradle compilation")
    async def cmd_build(self, ctx: discord.ApplicationContext):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        if server.IS_BUILDING:
            return await ctx.respond("⚠️ A build pipeline is already executing! Please wait.", ephemeral=True)

        author_name = str(ctx.author)
        await ctx.defer()

        success = await server.AutoBuilder.run_build(trigger_source=f"Discord ({author_name})")

        if success:
            meta = server.compute_version_metadata()
            embed = discord.Embed(
                title="✅ Build Pipeline Succeeded",
                description="Git origin pulled and mod JARs successfully compiled!",
                color=0x34A853,
                timestamp=datetime.utcnow()
            )
            embed.add_field(name="📦 Version", value=f"`v{meta.get('version')}`", inline=True)
            embed.add_field(name="🌿 Branch", value=f"`{server.GIT_BRANCH}`", inline=True)
            embed.add_field(name="Triggered By", value=author_name, inline=True)
            embed.set_footer(text="NoemtAddons CI/CD")
            await ctx.respond(embed=embed)
        else:
            embed = discord.Embed(
                title="❌ Build Pipeline Failed",
                description="An error occurred during Gradle compilation. Check `/telemetry logs` for output details.",
                color=0xEA4335,
                timestamp=datetime.utcnow()
            )
            embed.set_footer(text="NoemtAddons CI/CD")
            await ctx.respond(embed=embed)

    @control.command(name="kill", description="Emergency remote kill switch to terminate game client(s)")
    @option("target", description="Target player IGN or 'all'", default="all")
    async def cmd_kill(self, ctx: discord.ApplicationContext, target: str):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        author_name = str(ctx.author)
        count = await server.send_to_target(target, {
            "type": "SHUTDOWN",
            "reason": f"Remote kill switch activated by Discord operator {author_name}"
        })

        embed = discord.Embed(
            title="🛑 Emergency Kill Switch Dispatched",
            description=f"Sent game termination signal to **`{count}`** player client instance(s).",
            color=0xEA4335,
            timestamp=datetime.utcnow()
        )
        embed.add_field(name="Target", value=f"`{target}`", inline=True)
        embed.add_field(name="Operator", value=author_name, inline=True)
        embed.set_footer(text="NoemtAddons Remote Failsafe")
        await ctx.respond(embed=embed)

    @control.command(name="msg", description="Send in-game mod chat message to client(s)")
    @option("target", description="Target player IGN or 'all'")
    @option("message", description="Message text to display")
    async def cmd_msg(self, ctx: discord.ApplicationContext, target: str, message: str):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        count = await server.send_to_target(target, {
            "type": "MESSAGE",
            "message": message
        })
        await ctx.respond(f"📨 In-game message dispatched to **`{count}`** client(s) (Target: `{target}`): `{message}`")

    @control.command(name="chat", description="Execute command or chat message on player client")
    @option("target", description="Target player IGN or 'all'")
    @option("command", description="Command to execute (e.g. /warp hub)")
    async def cmd_chat(self, ctx: discord.ApplicationContext, target: str, command: str):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        count = await server.send_to_target(target, {
            "type": "CHAT",
            "text": command
        })
        await ctx.respond(f"💬 Command dispatched to **`{count}`** client(s) (Target: `{target}`): `{command}`")

    @control.command(name="title", description="Display custom screen title alert on client")
    @option("target", description="Target player IGN or 'all'")
    @option("title", description="Main title text")
    @option("subtitle", description="Subtitle text", default="Discord Operator Alert")
    async def cmd_title(self, ctx: discord.ApplicationContext, target: str, title: str, subtitle: str):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        count = await server.send_to_target(target, {
            "type": "TITLE",
            "title": title,
            "subtitle": subtitle
        })
        await ctx.respond(f"📢 Title alert dispatched to **`{count}`** client(s): `{title}`")

    @control.command(name="goto", description="Direct player pathfinder to coordinates")
    @option("target", description="Target player IGN or 'all'")
    @option("x", description="X coordinate (integer)")
    @option("y", description="Y coordinate (integer)")
    @option("z", description="Z coordinate (integer)")
    async def cmd_goto(self, ctx: discord.ApplicationContext, target: str, x: int, y: int, z: int):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        count = await server.send_to_target(target, {
            "type": "PATHFIND",
            "x": x,
            "y": y,
            "z": z
        })
        await ctx.respond(f"🧭 Pathfind target `({x}, {y}, {z})` sent to **`{count}`** client(s).")

    @control.command(name="stop", description="Cancel active pathfinder navigation")
    @option("target", description="Target player IGN or 'all'", default="all")
    async def cmd_stop(self, ctx: discord.ApplicationContext, target: str):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        count = await server.send_to_target(target, {
            "type": "PATHFIND_STOP"
        })
        await ctx.respond(f"🛑 Pathfind stop dispatched to **`{count}`** client(s).")

    @control.command(name="broadcast", description="Broadcast an in-game announcement to all connected clients")
    @option("message", description="Broadcast message text")
    async def cmd_broadcast(self, ctx: discord.ApplicationContext, message: str):
        server = self.bot.server
        if not server:
            return await ctx.respond("❌ Server instance not attached.", ephemeral=True)

        count = await server.send_to_target("all", {
            "type": "MESSAGE",
            "message": f"&6[Broadcast] &e{message}"
        })
        await ctx.respond(f"📢 Broadcast sent to **`{count}`** connected client(s).")

    # Top-Level Direct Slash Command for Rebuilds
    @slash_command(name="build", description="Trigger instant git pull & gradle compilation", guild_ids=GUILD_IDS)
    async def direct_build(self, ctx: discord.ApplicationContext):
        await self.cmd_build(ctx)


def setup(bot):
    bot.add_cog(ControlCog(bot))
