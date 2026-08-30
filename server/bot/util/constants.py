import os
from typing import List, Optional
import discord
from discord.ext import commands

GUILD_IDS = 1538276207550931206
ADMIN_ROLE_ID = 1543546298370621580

def get_guild_ids(): return [GUILD_IDS]

def is_authorized():
    async def predicate(ctx: discord.ApplicationContext):
        if ctx.author.id in ctx.bot.owner_ids:
            return True

        role_object = ctx.guild.get_role(ADMIN_ROLE_ID)
        if role_object not in ctx.author.roles:
            return False

        return True

    return commands.check(predicate)