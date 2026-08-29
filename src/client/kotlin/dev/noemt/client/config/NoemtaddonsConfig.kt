package dev.noemt.client.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.ChromaColour
import io.github.notenoughupdates.moulconfig.Config
import io.github.notenoughupdates.moulconfig.annotations.Category
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider
import dev.noemt.client.BuildConstants
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption
import io.github.notenoughupdates.moulconfig.common.text.StructuredText

open class NoemtaddonsConfig : Config() {
    override fun getTitle(): StructuredText {
        return StructuredText.of("NoemtAddons Config (${BuildConstants.buildDisplayName})")
    }

    @Expose
    @Category(name = "Dungeon Map", desc = "Dungeon Map and Cheater Map overlay settings")
    @JvmField
    var map: MapCategory = MapCategory()

    @Expose
    @Category(name = "Blood Room", desc = "Blood Room and Blood Camp helper settings")
    @JvmField
    var blood: BloodCategory = BloodCategory()

    @Expose
    @Category(name = "Discord & Remote", desc = "Discord Bot Notifications and Remote WebSocket Server Settings")
    @JvmField
    var remote: RemoteCategory = RemoteCategory()

    @Expose
    @Category(name = "Loadout Swapper", desc = "Conditional auto loadout swapper (Run \$noemt loadout for visual builder)")
    @JvmField
    var loadout: LoadoutCategory = LoadoutCategory()

    @Expose
    @Category(name = "Dungeon Gambling", desc = "SkyOcean-style Dungeon Chest Slot Machine animation inside chests")
    @JvmField
    var gambling: GamblingCategory = GamblingCategory()

    class GamblingCategory {
        @Expose
        @ConfigOption(name = "Enable Dungeon Slot Machine", desc = "Plays an animated 3-reel slot machine directly inside dungeon chests!")
        @ConfigEditorBoolean
        @JvmField
        var enabled: Boolean = true

        @Expose
        @ConfigOption(name = "Enable in Croesus", desc = "Plays slot machine animation when inspecting chests in Croesus.")
        @ConfigEditorBoolean
        @JvmField
        var croesusEnabled: Boolean = true

        @Expose
        @ConfigOption(name = "Hide Croesus Tooltip Preview", desc = "Hides reward items and cost preview in Croesus until clicked/opened.")
        @ConfigEditorBoolean
        @JvmField
        var hideCroesusContents: Boolean = true

        @Expose
        @ConfigOption(name = "Allowed Chest Types", desc = "0: Obsidian & Bedrock Only, 1: All Chests (Wood to Bedrock)")
        @ConfigEditorSlider(minValue = 0f, maxValue = 1f, minStep = 1f)
        @JvmField
        var chestTypes: Int = 0

        @Expose
        @ConfigOption(name = "Spin Duration (Seconds)", desc = "Base duration of the slot machine spinning animation.")
        @ConfigEditorSlider(minValue = 1.0f, maxValue = 10.0f, minStep = 0.5f)
        @JvmField
        var spinDuration: Float = 4.0f

        @Expose
        @ConfigOption(name = "Play Sound Effects", desc = "Plays reel spinning ticks, locks, and jackpot fanfare sounds.")
        @ConfigEditorBoolean
        @JvmField
        var playSounds: Boolean = true

        @Expose
        @ConfigOption(name = "Show Skip Button", desc = "Displays a clickable [Skip Animation] button inside the chest.")
        @ConfigEditorBoolean
        @JvmField
        var showSkipButton: Boolean = true

        @Expose
        @ConfigOption(name = "Spacebar to Skip", desc = "Allows pressing Space or Escape to instantly skip the slot machine animation.")
        @ConfigEditorBoolean
        @JvmField
        var allowSpaceSkip: Boolean = true
    }

    class LoadoutCategory {
        @Expose
        @ConfigOption(name = "Enable Loadout Swapper", desc = "Master toggle for conditional and keybind loadout swapping (Run \$loadout gui).")
        @ConfigEditorBoolean
        @JvmField
        var enabled: Boolean = true

        @Expose
        @ConfigOption(name = "HUD Loadout Display", desc = "Renders current active loadout name on screen.")
        @ConfigEditorBoolean
        @JvmField
        var showHud: Boolean = true

        @Expose
        @ConfigOption(name = "Notification Sound", desc = "Plays sound when loadout is swapped.")
        @ConfigEditorBoolean
        @JvmField
        var playSound: Boolean = true

        @Expose
        @ConfigOption(name = "Default Command Delay (ms)", desc = "Delay in milliseconds between loadout commands.")
        @ConfigEditorSlider(minValue = 50f, maxValue = 500f, minStep = 25f)
        @JvmField
        var defaultDelayMs: Float = 100f
    }

    class RemoteCategory {
        @Expose
        @ConfigOption(name = "Discord Notifications", desc = "Enables Discord bot notifications.")
        @ConfigEditorBoolean
        @JvmField
        var discordEnabled: Boolean = false

        @Expose
        @ConfigOption(name = "Discord Bot Token", desc = "Your Discord bot token.")
        @ConfigEditorText
        @JvmField
        var discordBotToken: String = ""

        @Expose
        @ConfigOption(name = "Discord Channel ID", desc = "The Discord channel ID to send messages/alerts to.")
        @ConfigEditorText
        @JvmField
        var discordChannelId: String = ""

        @Expose
        @ConfigOption(name = "Discord Webhook URL (Optional)", desc = "Optional webhook URL (used if Bot Token is empty).")
        @ConfigEditorText
        @JvmField
        var discordWebhookUrl: String = ""

        @Expose
        @ConfigOption(name = "Remote WebSocket Enabled", desc = "Maintains an active connection to your remote server.")
        @ConfigEditorBoolean
        @JvmField
        var wsEnabled: Boolean = true

        @Expose
        @ConfigOption(name = "WebSocket Server URL", desc = "Remote WebSocket endpoint (default: wss://addons.noemt.dev).")
        @ConfigEditorText
        @JvmField
        var wsUrl: String = "wss://addons.noemt.dev"

        @Expose
        @ConfigOption(name = "WebSocket Secret Key", desc = "Authentication key for your remote WebSocket server.")
        @ConfigEditorText
        @JvmField
        var wsSecret: String = ""
    }

    class MapCategory {
        @Expose
        @ConfigOption(name = "Map Enabled", desc = "Renders the custom Dungeon Map overlay.")
        @ConfigEditorBoolean
        @JvmField
        var mapEnabled: Boolean = true

        @Expose
        @ConfigOption(name = "Cheater Map", desc = "Reveals undiscovered rooms and full dungeon layout ahead of time (Cheat build only).")
        @ConfigEditorBoolean
        @JvmField
        var dungeonMapCheater: Boolean = true

        @Expose
        @ConfigOption(name = "Map X Position", desc = "Horizontal screen position of the map.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 1000f, minStep = 1f)
        @JvmField
        var mapX: Float = 10f

        @Expose
        @ConfigOption(name = "Map Y Position", desc = "Vertical screen position of the map.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 1000f, minStep = 1f)
        @JvmField
        var mapY: Float = 10f

        @Expose
        @ConfigOption(name = "Map Scale", desc = "Scaling factor of the map overlay.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 3.0f, minStep = 0.1f)
        @JvmField
        var mapScale: Float = 1.0f

        @Expose
        @ConfigOption(name = "Show Extra Info Under Map", desc = "Shows Secrets, Crypts, Score, and Mimic status under the map.")
        @ConfigEditorBoolean
        @JvmField
        var mapExtraInfo: Boolean = true

        @Expose
        @ConfigOption(name = "Hide In Boss", desc = "Hides the map during boss fights.")
        @ConfigEditorBoolean
        @JvmField
        var mapHideInBoss: Boolean = false

        @Expose
        @ConfigOption(name = "Show Player Names", desc = "0: Off, 1: Holding Leap, 2: Always")
        @ConfigEditorSlider(minValue = 0f, maxValue = 2f, minStep = 1f)
        @JvmField
        var playerNames: Int = 0

        @Expose
        @ConfigOption(name = "Vanilla Head Marker", desc = "Uses a vanilla marker arrow for your own player.")
        @ConfigEditorBoolean
        @JvmField
        var mapVanillaMarker: Boolean = false

        @Expose
        @ConfigOption(name = "Show Room Names", desc = "Displays room names on the map tiles (e.g. Blood, Trap, Boulder).")
        @ConfigEditorBoolean
        @JvmField
        var showRoomNames: Boolean = true

        @Expose
        @ConfigOption(name = "Show Secrets on Map", desc = "Displays secrets found / total secrets on the map tiles.")
        @ConfigEditorBoolean
        @JvmField
        var showSecretsOnMap: Boolean = true

        @Expose
        @ConfigOption(name = "Checkmark Style", desc = "0: Checkmarks, 1: Secrets, 2: Room Name, 3: Room Name + Secrets")
        @ConfigEditorSlider(minValue = 0f, maxValue = 3f, minStep = 1f)
        @JvmField
        var checkmarkStyle: Int = 3

        @Expose
        @ConfigOption(name = "Center Checkmark", desc = "Centers checkmarks and labels in multi-tile rooms.")
        @ConfigEditorBoolean
        @JvmField
        var centerStyle: Boolean = true

        @Expose
        @ConfigOption(name = "Hide Unknown Room Checkmark", desc = "Hides question mark checkmarks on unopened rooms.")
        @ConfigEditorBoolean
        @JvmField
        var hideQuestionCheckmarks: Boolean = false

        @Expose
        @ConfigOption(name = "Limit Room Name Size", desc = "Dynamically shrinks room name text so it fits within room bounds.")
        @ConfigEditorBoolean
        @JvmField
        var limitRoomNameSize: Boolean = true

        @Expose
        @ConfigOption(name = "Highlight Mimic Room", desc = "Highlights the room containing the mimic chest on the map.")
        @ConfigEditorBoolean
        @JvmField
        var highlightMimicRoom: Boolean = true

        @Expose
        @ConfigOption(name = "Mimic ESP", desc = "Draws an in-world ESP box on the mimic trapped chest.")
        @ConfigEditorBoolean
        @JvmField
        var mimicEsp: Boolean = true

        @Expose
        @ConfigOption(name = "Mimic ESP Color", desc = "The color of the mimic ESP box.")
        @ConfigEditorColour
        @JvmField
        var mimicEspColor: ChromaColour = ChromaColour.fromStaticRGB(255, 0, 0, 80)

        @Expose
        @ConfigOption(name = "Map Text Scale", desc = "Scale of room names and secret labels.")
        @ConfigEditorSlider(minValue = 0.4f, maxValue = 1.5f, minStep = 0.1f)
        @JvmField
        var textScale: Float = 1.0f

        @Expose
        @ConfigOption(name = "Map Checkmark Scale", desc = "Scale of room checkmarks.")
        @ConfigEditorSlider(minValue = 0.3f, maxValue = 1.5f, minStep = 0.1f)
        @JvmField
        var checkmarkSize: Float = 1.0f

        @Expose
        @ConfigOption(name = "Player Heads Scale", desc = "Scale of player head icons on the map.")
        @ConfigEditorSlider(minValue = 0.3f, maxValue = 1.5f, minStep = 0.1f)
        @JvmField
        var playerHeadScale: Float = 1.0f

        @Expose
        @ConfigOption(name = "Player Name Scale", desc = "Scale of player names on the map.")
        @ConfigEditorSlider(minValue = 0.3f, maxValue = 1.5f, minStep = 0.1f)
        @JvmField
        var playerNameScale: Float = 0.5f

        @Expose
        @ConfigOption(name = "Map Background Color", desc = "Background color of the map.")
        @ConfigEditorColour
        @JvmField
        var mapBackground: ChromaColour = ChromaColour.fromStaticRGB(0, 0, 0, 100)

        @Expose
        @ConfigOption(name = "Map Border Color", desc = "Border color of the map.")
        @ConfigEditorColour
        @JvmField
        var mapBorderColor: ChromaColour = ChromaColour.fromStaticRGB(255, 255, 255, 255)

        @Expose
        @ConfigOption(name = "Border Thickness", desc = "Thickness of the map border.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 5f, minStep = 1f)
        @JvmField
        var mapBorderWidth: Int = 1

        @Expose
        @ConfigOption(name = "Head Border Color", desc = "Border color around player heads.")
        @ConfigEditorColour
        @JvmField
        var mapPlayerHeadColor: ChromaColour = ChromaColour.fromStaticRGB(0, 0, 0, 255)

        @Expose
        @ConfigOption(name = "Vanilla Head Marker Color", desc = "Color of the vanilla arrow marker.")
        @ConfigEditorColour
        @JvmField
        var mapVanillaMarkerColor: ChromaColour = ChromaColour.fromStaticRGB(0, 255, 0, 255)

        @Expose
        @ConfigOption(name = "Head Border Class Based", desc = "Colors player head borders according to dungeon class.")
        @ConfigEditorBoolean
        @JvmField
        var mapPlayerHeadColorClassBased: Boolean = true

        @Expose
        @ConfigOption(name = "Player Names Class Based", desc = "Colors player names according to dungeon class.")
        @ConfigEditorBoolean
        @JvmField
        var mapPlayerNameClassColorBased: Boolean = true

        @Expose
        @ConfigOption(name = "Blood Room Color", desc = "Map color for the Blood room.")
        @ConfigEditorColour
        @JvmField
        var colorBlood: ChromaColour = ChromaColour.fromStaticRGB(178, 0, 0, 255)

        @Expose
        @ConfigOption(name = "Entrance Room Color", desc = "Map color for the Entrance room.")
        @ConfigEditorColour
        @JvmField
        var colorEntrance: ChromaColour = ChromaColour.fromStaticRGB(0, 255, 0, 255)

        @Expose
        @ConfigOption(name = "Fairy Room Color", desc = "Map color for Fairy rooms.")
        @ConfigEditorColour
        @JvmField
        var colorFairy: ChromaColour = ChromaColour.fromStaticRGB(227, 155, 226, 255)

        @Expose
        @ConfigOption(name = "Miniboss Room Color", desc = "Map color for Miniboss / Yellow rooms.")
        @ConfigEditorColour
        @JvmField
        var colorMiniboss: ChromaColour = ChromaColour.fromStaticRGB(255, 200, 0, 255)

        @Expose
        @ConfigOption(name = "Normal Room Color", desc = "Map color for standard rooms.")
        @ConfigEditorColour
        @JvmField
        var colorRoom: ChromaColour = ChromaColour.fromStaticRGB(121, 70, 0, 255)

        @Expose
        @ConfigOption(name = "Puzzle Room Color", desc = "Map color for Puzzle rooms.")
        @ConfigEditorColour
        @JvmField
        var colorPuzzle: ChromaColour = ChromaColour.fromStaticRGB(123, 0, 123, 255)

        @Expose
        @ConfigOption(name = "Mimic Room Highlight Color", desc = "Map highlight color for the room containing the mimic.")
        @ConfigEditorColour
        @JvmField
        var colorMimic: ChromaColour = ChromaColour.fromStaticRGB(255, 0, 0, 255)

        @Expose
        @ConfigOption(name = "Rare Room Color", desc = "Map color for Rare rooms.")
        @ConfigEditorColour
        @JvmField
        var colorRare: ChromaColour = ChromaColour.fromStaticRGB(178, 178, 178, 255)

        @Expose
        @ConfigOption(name = "Trap Room Color", desc = "Map color for Trap rooms.")
        @ConfigEditorColour
        @JvmField
        var colorTrap: ChromaColour = ChromaColour.fromStaticRGB(255, 130, 0, 255)

        @Expose
        @ConfigOption(name = "Unopened Room Color", desc = "Map color for unopened rooms.")
        @ConfigEditorColour
        @JvmField
        var colorUnopened: ChromaColour = ChromaColour.fromStaticRGB(65, 65, 65, 255)

        @Expose
        @ConfigOption(name = "Unopened Door Color", desc = "Map color for unopened doors.")
        @ConfigEditorColour
        @JvmField
        var colorUnopenedDoor: ChromaColour = ChromaColour.fromStaticRGB(65, 65, 65, 255)

        @Expose
        @ConfigOption(name = "Blood Door Color", desc = "Map color for Blood doors.")
        @ConfigEditorColour
        @JvmField
        var colorBloodDoor: ChromaColour = ChromaColour.fromStaticRGB(178, 0, 0, 255)

        @Expose
        @ConfigOption(name = "Wither Door Color", desc = "Map color for Wither doors.")
        @ConfigEditorColour
        @JvmField
        var colorWitherDoor: ChromaColour = ChromaColour.fromStaticRGB(16, 16, 16, 255)

        @Expose
        @ConfigOption(name = "Normal Door Color", desc = "Map color for normal doors.")
        @ConfigEditorColour
        @JvmField
        var colorRoomDoor: ChromaColour = ChromaColour.fromStaticRGB(121, 70, 0, 255)

        @Expose
        @ConfigOption(name = "Opened Wither Door Color", desc = "Map color for opened wither doors.")
        @ConfigEditorColour
        @JvmField
        var colorOpenWitherDoor: ChromaColour = ChromaColour.fromStaticRGB(121, 70, 0, 255)

        @Expose
        @ConfigOption(name = "Entrance Door Color", desc = "Map color for entrance doors.")
        @ConfigEditorColour
        @JvmField
        var colorEntranceDoor: ChromaColour = ChromaColour.fromStaticRGB(0, 255, 0, 255)

        @Expose
        @ConfigOption(name = "Box Wither Doors", desc = "Draws 3D bounding boxes around wither doors in the world.")
        @ConfigEditorBoolean
        @JvmField
        var boxDoors: Boolean = true

        @Expose
        @ConfigOption(name = "Door Box Mode", desc = "0: Outline, 1: Fill, 2: Filled Outline")
        @ConfigEditorSlider(minValue = 0f, maxValue = 2f, minStep = 1f)
        @JvmField
        var boxDoorsMode: Int = 2

        @Expose
        @ConfigOption(name = "Door No Key Color", desc = "Box color for doors when party has no key.")
        @ConfigEditorColour
        @JvmField
        var doorNoKeyColor: ChromaColour = ChromaColour.fromStaticRGB(255, 0, 0, 100)

        @Expose
        @ConfigOption(name = "Door Has Key Color", desc = "Box color for doors when party has key.")
        @ConfigEditorColour
        @JvmField
        var doorKeyColor: ChromaColour = ChromaColour.fromStaticRGB(0, 255, 0, 100)
    }

    class BloodCategory {
        @Expose
        @ConfigOption(name = "Auto Blood Camp", desc = "Automatically aims, pathfinds, evades TNT, and kills mobs in Blood Room.")
        @ConfigEditorBoolean
        @JvmField
        var autoBloodCamp: Boolean = false

        @Expose
        @ConfigOption(name = "Auto AOTV Teleport", desc = "Uses Aspect of the Void to teleport around the Blood Room and evade TNT.")
        @ConfigEditorBoolean
        @JvmField
        var autoBloodAotv: Boolean = true

        @Expose
        @ConfigOption(name = "Auto TNT Evasion", desc = "Automatically moves away from primed TNTs on the ground (>6 blocks).")
        @ConfigEditorBoolean
        @JvmField
        var autoBloodTntEvade: Boolean = true

        @Expose
        @ConfigOption(name = "Aim Speed", desc = "Speed multiplier for smooth aim rotation.")
        @ConfigEditorSlider(minValue = 0.2f, maxValue = 3.0f, minStep = 0.1f)
        @JvmField
        var autoBloodAimSpeed: Float = 0.9f

        @Expose
        @ConfigOption(name = "Human Combat Movement", desc = "Adds natural micro-strafing, jiggling, and positioning footwork while camping.")
        @ConfigEditorBoolean
        @JvmField
        var autoBloodHumanMovement: Boolean = true

        @Expose
        @ConfigOption(name = "Attack CPS", desc = "Clicks per second when attacking blood mobs.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 20f, minStep = 1f)
        @JvmField
        var autoBloodCps: Int = 12

        @Expose
        @ConfigOption(name = "Attack Range (Mage Beam)", desc = "Maximum range in blocks to target and shoot mobs.")
        @ConfigEditorSlider(minValue = 5f, maxValue = 30f, minStep = 1f)
        @JvmField
        var autoBloodAttackRange: Float = 26f

        @Expose
        @ConfigOption(name = "Weapon Hotbar Slot", desc = "Preferred weapon slot to always hold and attack with (0 = Current/Keep Held, 1-9 = Hotbar Slot 1-9).")
        @ConfigEditorSlider(minValue = 0f, maxValue = 9f, minStep = 1f)
        @JvmField
        var bloodWeaponSlot: Int = 0

        @Expose
        @ConfigOption(name = "Blood Camp Helper", desc = "Predicts spawn positions and renders boxes for blood mobs.")
        @ConfigEditorBoolean
        @JvmField
        var bloodCamp: Boolean = true

        @Expose
        @ConfigOption(name = "Timer Decimal Places", desc = "The number of decimal places shown on blood mob timers.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 2f, minStep = 1f)
        @JvmField
        var decimalPlaces: Int = 1

        @Expose
        @ConfigOption(name = "Timer Color", desc = "The color of the countdown timer text on blood mobs.")
        @ConfigEditorColour
        @JvmField
        var timerColor: ChromaColour = ChromaColour.fromStaticRGB(255, 255, 255, 255)

        @Expose
        @ConfigOption(name = "Box Color", desc = "The color of the predicted mob landing box.")
        @ConfigEditorColour
        @JvmField
        var boxColor: ChromaColour = ChromaColour.fromStaticRGB(255, 0, 255, 255)

        @Expose
        @ConfigOption(name = "Line Color", desc = "The color of the line connecting blood mobs to landing point.")
        @ConfigEditorColour
        @JvmField
        var lineColor: ChromaColour = ChromaColour.fromStaticRGB(0, 255, 255, 255)

        @Expose
        @ConfigOption(name = "Kill Title Alert", desc = "Displays a title when blood mobs are ready to be killed.")
        @ConfigEditorBoolean
        @JvmField
        var killTitle: Boolean = true

        @Expose
        @ConfigOption(name = "Watcher Speed Alert", desc = "Shows a title indicating Watcher speed (Fast, Normal, Slow) with sounds.")
        @ConfigEditorBoolean
        @JvmField
        var speedAlert: Boolean = true

        @Expose
        @ConfigOption(name = "Send Speed Alert to Party", desc = "Sends the Watcher speed in party chat.")
        @ConfigEditorBoolean
        @JvmField
        var partySpeedAlert: Boolean = false

        @Expose
        @ConfigOption(name = "Blood Room ESP Box", desc = "Draws a bounding box around the Blood room before dungeon starts.")
        @ConfigEditorBoolean
        @JvmField
        var bloodEsp: Boolean = true

        @Expose
        @ConfigOption(name = "Blood Door Tracer", desc = "Draws a tracer line to the Blood room door before dungeon starts.")
        @ConfigEditorBoolean
        @JvmField
        var espTracer: Boolean = true

        @Expose
        @ConfigOption(name = "Blood Room Box Color", desc = "The color of the Blood room ESP box.")
        @ConfigEditorColour
        @JvmField
        var espColor: ChromaColour = ChromaColour.fromStaticRGB(255, 0, 0, 255)

        @Expose
        @ConfigOption(name = "Blood Door Tracer Color", desc = "The color of the Blood room door tracer.")
        @ConfigEditorColour
        @JvmField
        var tracerColor: ChromaColour = ChromaColour.fromStaticRGB(255, 0, 0, 255)
    }
}
