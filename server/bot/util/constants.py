import os
from typing import List, Optional


def get_guild_ids() -> Optional[List[int]]:
    """Retrieves list of target Discord Guild IDs for instant Slash Command deployment."""
    raw = (
        os.getenv("GUILD_IDS")
        or os.getenv("DISCORD_GUILD_IDS")
        or os.getenv("DISCORD_GUILD_ID")
        or os.getenv("MAIN_GUILD")
        or ""
    ).strip()

    if not raw:
        return None

    ids = []
    for item in raw.replace(";", ",").split(","):
        item = item.strip()
        if item.isdigit():
            ids.append(int(item))

    return ids if ids else None


GUILD_IDS = get_guild_ids()
