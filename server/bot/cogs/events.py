import discord
from discord.ext import commands
from datetime import datetime


class EventsCog(commands.Cog, name="Events"):
    def __init__(self, bot):
        self.bot = bot

    async def send_to_channel(self, embed: discord.Embed):
        channel = self.bot.get_notification_channel()
        if channel:
            try:
                await channel.send(embed=embed)
            except Exception as e:
                pass

    @commands.Cog.listener()
    async def on_player_join(self, player_name: str, info: dict):
        embed = discord.Embed(
            title="🎮 Player Connected",
            description=f"**{player_name}** established connection to the Cloud WebSocket.",
            color=0x34A853,
            timestamp=datetime.utcnow()
        )
        embed.add_field(name="UUID", value=f"`{info.get('uuid', 'N/A')}`", inline=True)
        embed.add_field(name="IP", value=f"`{info.get('ip', 'N/A')}`", inline=True)
        embed.add_field(name="Mod Version", value=f"`v{info.get('version', '1.0.2')}`", inline=True)
        embed.set_footer(text="NoemtAddons Telemetry")
        await self.send_to_channel(embed)

    @commands.Cog.listener()
    async def on_player_leave(self, player_name: str):
        embed = discord.Embed(
            title="🚪 Player Disconnected",
            description=f"**{player_name}** disconnected from the Cloud WebSocket.",
            color=0xF28B82,
            timestamp=datetime.utcnow()
        )
        embed.set_footer(text="NoemtAddons Telemetry")
        await self.send_to_channel(embed)

    @commands.Cog.listener()
    async def on_build_started(self, trigger_source: str, branch: str, short_hash: str, commit_lines: str):
        embed = discord.Embed(
            title=f"⚡ CI/CD Build Started (`{short_hash}`)",
            description=f"**Trigger:** `{trigger_source}`\n**Branch:** `{branch}`\n\n**Commit Details:**\n{commit_lines}",
            color=0xFBBC04,
            timestamp=datetime.utcnow()
        )
        embed.add_field(name="Status", value="⏳ Executing Gradle compilation...", inline=True)
        embed.set_footer(text="NoemtAddons CI/CD Pipeline")
        await self.send_to_channel(embed)

    @commands.Cog.listener()
    async def on_build_completed(self, success: bool, duration: float, short_hash: str, author: str, latest_msg: str, mod_size_kb: float, error_tail: str = ""):
        if success:
            embed = discord.Embed(
                title=f"🚀 Deployment Succeeded (`{short_hash}`)",
                description=f"**New version deployed**\n\n> 📝 *\"{latest_msg}\"*",
                color=0x34A853,
                timestamp=datetime.utcnow()
            )
            embed.add_field(name="Author", value=author, inline=True)
            embed.add_field(name="Build Time", value=f"`{duration}s`", inline=True)
            embed.add_field(name="Mod Artifact", value=f"`{mod_size_kb:.1f} KB`", inline=True)
            embed.set_footer(text="NoemtAddons CI/CD Pipeline")
            await self.send_to_channel(embed)
        else:
            embed = discord.Embed(
                title=f"❌ Build Failed (`{short_hash}`)",
                description=f"**Compilation error encountered after {duration}s:**\n```\n{error_tail[:1000]}\n```",
                color=0xEA4335,
                timestamp=datetime.utcnow()
            )
            embed.add_field(name="Author", value=author, inline=True)
            embed.set_footer(text="NoemtAddons CI/CD Pipeline")
            await self.send_to_channel(embed)


def setup(bot):
    bot.add_cog(EventsCog(bot))
