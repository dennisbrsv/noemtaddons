package dev.noemt.client.utils

import dev.noemt.client.event.EventBus
import dev.noemt.client.event.impl.*
import dev.noemt.client.event.priority.EventPriority
import dev.noemt.client.mixin.IPlayerInfo
import dev.noemt.client.utils.ChatUtils.formattedText
import dev.noemt.client.utils.ChatUtils.removeFormatting
import dev.noemt.client.utils.ItemUtils.skyblockId
import dev.noemt.client.utils.LocationUtils.inDungeon
import dev.noemt.client.utils.NumbersUtils.romanToDecimal
import dev.noemt.client.utils.dungeon.DungeonClass
import dev.noemt.client.utils.dungeon.DungeonPlayer
import dev.noemt.client.utils.map.core.DoorType
import dev.noemt.client.utils.map.core.RoomState
import dev.noemt.client.utils.map.core.RoomType
import dev.noemt.client.utils.map.handlers.DungeonScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.EntityType

object DungeonListener {
    private val tablistRegex = Regex("""^\[\d+] (?:\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\((\w+)(?: (\w+))?\)$""")
    private val deathRegex = Regex("^ ☠ (?:You were|(?<username>\\w+)) (?<reason>.+?)(?: and became a ghost)?\\.$")
    private val keyPickupRegex = Regex("^§e§lRIGHT CLICK §7on §7.+?§7 to open it\\. This key can only be used to open §a(?<num>\\d+)§7 door!$")
    private val witherDoorOpenedRegex = Regex("^(?:\\[.+?] )?(?<name>\\w+) opened a WITHER door!$")
    private val watcherMessageRegex = Regex("^\\[BOSS] The Watcher: .+$")
    private val runEndRegex = Regex("^\\s*(Master Mode)? ?(?:The)? Catacombs - (Floor (.{1,3})|Entrance)$")

    private val scope = CoroutineScope(Dispatchers.Default)

    var dungeonTeammates = mutableListOf<DungeonPlayer>()
    var dungeonTeammatesNoSelf = listOf<DungeonPlayer>()
    var thePlayer: DungeonPlayer? = null

    data class PuzzleEntry(val name: String, var state: RoomState)
    var puzzles = mutableListOf<PuzzleEntry>()

    data class DualTime(val ticks: Long, val real: Long = System.currentTimeMillis())

    var dungeonStarted = false
    var dungeonStartTime: DualTime? = null
    var dungeonEnded = false

    var bloodOpenTime: DualTime? = null
    var watcherClearTime: DualTime? = null
    var watcherFinishSpawnTime: Long? = null
    var dungeonEndTime: Long? = null

    var lastDoorOpener: DungeonPlayer? = null
    var currentTime: Long = 0L

    fun init() {
        val mc = Minecraft.getInstance()

        EventBus.register<MainThreadPacketReceivedEvent.Post>(EventPriority.HIGH) {
            if (!inDungeon) return@register

            when (val packet = event.packet) {
                is ClientboundPlayerInfoUpdatePacket -> {
                    for (entry in TabListUtils.getTabList()) {
                        val text = entry.first.formattedText
                        updateDungeonTeammates(text, entry.second)
                    }
                }

                is ClientboundContainerSetSlotPacket -> {
                    thePlayer?.isDead = PlayerUtils.getHotbarSlot(0)?.skyblockId == "HAUNT_ABILITY"
                }

                is ClientboundRemoveEntitiesPacket -> dungeonTeammates.forEach {
                    val id = it.entity?.id ?: return@forEach
                    if (id in packet.entityIds) {
                        it.entity = null
                    }
                }

                is ClientboundAddEntityPacket -> {
                    if (packet.type != EntityType.PLAYER) return@register
                    val entity = mc.level?.getEntity(packet.id) as? AbstractClientPlayer ?: return@register
                    dungeonTeammates.find { it.entity == null && it.name == entity.name.string }?.entity = entity
                }
            }
        }

        EventBus.register<ChatMessageEvent>(EventPriority.HIGHEST) {
            if (!inDungeon) return@register
            val text = event.formattedText
            val unformatted = event.unformattedText

            when {
                unformatted.matches(runEndRegex) -> {
                    dungeonEnded = true
                    dungeonEndTime = currentTime
                    EventBus.post(DungeonEvent.RunEndedEvent)
                }

                text == "§cThe §c§lBLOOD DOOR§c has been opened!" -> DoorType.BLOOD.keys--

                "§c ☠" in text && "reconnected" !in unformatted -> {
                    val match = deathRegex.find(unformatted) ?: return@register
                    val username = match.groups["username"]?.value?.takeUnless { it == "You" } ?: mc.user.name
                    val reason = match.groups["reason"]?.value ?: ""
                    scope.launch {
                        while (thePlayer == null) delay(1)
                        if (username == mc.user.name) thePlayer?.isDead = true
                        EventBus.post(DungeonEvent.PlayerDeathEvent(username, reason))
                    }
                }

                unformatted == "[BOSS] The Watcher: You have proven yourself. You may pass." -> {
                    DungeonScanner.uniqueRooms["Blood"]?.mainRoom?.state = RoomState.GREEN
                    watcherClearTime = DualTime(currentTime)
                }

                unformatted == "[BOSS] The Watcher: That will be enough for now." -> {
                    DungeonScanner.uniqueRooms["Blood"]?.mainRoom?.state = RoomState.CLEARED
                    watcherFinishSpawnTime = currentTime
                }

                watcherMessageRegex.matches(unformatted) && bloodOpenTime == null -> {
                    bloodOpenTime = DualTime(currentTime)
                }

                unformatted.startsWith("[NPC] Mort:", ignoreCase = true) ||
                unformatted.startsWith("Dungeon starts in", ignoreCase = true) ||
                unformatted.startsWith("Starting in", ignoreCase = true) ||
                unformatted.contains("Entering The Catacombs", ignoreCase = true) ||
                unformatted.contains("The dungeon has begun!", ignoreCase = true) -> {
                    if (!dungeonStarted || dungeonEnded) {
                        dungeonStartTime = DualTime(currentTime)
                        dungeonStarted = true
                        dungeonEnded = false
                        EventBus.post(DungeonEvent.RunStatedEvent)
                    }
                }

                else -> {
                    witherDoorOpenedRegex.find(unformatted)?.destructured?.let { (name) ->
                        lastDoorOpener = dungeonTeammates.find { it.name == name }
                        DoorType.WITHER.keys--
                        return@register
                    }

                    keyPickupRegex.find(text)?.destructured?.let { (num) ->
                        val type = when {
                            "WITHER door" in unformatted -> DoorType.WITHER
                            "BLOOD DOOR" in unformatted -> DoorType.BLOOD
                            else -> null
                        } ?: return@register

                        type.keys += num.toInt()
                        return@register
                    }
                }
            }
        }

        EventBus.register<TickEvent.Start>(EventPriority.HIGHEST) {
            currentTime++
            val player = mc.player ?: return@register
            LocationUtils.updateBossStatus(player.x, player.y, player.z)
        }

        EventBus.register<WorldChangeEvent>(EventPriority.HIGHEST) {
            dungeonStarted = false
            dungeonTeammates = mutableListOf()
            dungeonTeammatesNoSelf = mutableListOf()
            thePlayer = null
            puzzles.clear()
            dungeonStartTime = null
            dungeonEnded = false
            bloodOpenTime = null
            watcherClearTime = null
            watcherFinishSpawnTime = null
            dungeonEndTime = null
            lastDoorOpener = null
            currentTime = 0L
            DoorType.reset()
        }

        EventBus.register<DungeonEvent.RoomEvent.onStateChange> {
            if (lastDoorOpener == null) return@register
            if (event.room.data.type != RoomType.BLOOD) return@register
            if (event.newState != RoomState.DISCOVERED && event.newState != RoomState.CLEARED && event.newState != RoomState.GREEN) return@register
            lastDoorOpener = null
        }
    }

    private fun updateDungeonTeammates(tabName: String, tabEntry: PlayerInfo) {
        val mc = Minecraft.getInstance()
        val line = (tabEntry as? IPlayerInfo)?.rawTabListDisplayName?.string ?: tabName.removeFormatting()
        val (name, clazz, clazzLevel) = tablistRegex.matchEntire(line)?.destructured ?: return
        val playerInfo = if (name == tabEntry.profile.name) tabEntry
        else mc.connection?.getPlayerInfo(name) ?: tabEntry
        val skin = playerInfo.skin.body.texturePath()

        dungeonTeammates.find { it.name == name }?.let { currentTeammate ->
            currentTeammate.clazz = if (clazz != "DEAD") DungeonClass.fromName(clazz) else currentTeammate.clazz
            currentTeammate.clazzLvl = clazzLevel.romanToDecimal()
            currentTeammate.skin = skin
            currentTeammate.isDead = clazz == "DEAD"
        } ?: dungeonTeammates.add(
            DungeonPlayer(
                name,
                DungeonClass.fromName(clazz),
                clazzLevel.romanToDecimal(),
                skin,
                clazz == "DEAD",
            )
        )

        thePlayer = dungeonTeammates.find { it.name == mc.user.name }
        dungeonTeammatesNoSelf = dungeonTeammates.filter { it != thePlayer }

        dungeonTeammates.forEach { teammate ->
            if (teammate.entity != null) return@forEach
            teammate.entity = mc.level?.players()?.find { it.name.string == teammate.name }
        }
    }
}
