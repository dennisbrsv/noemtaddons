# NoemtAddons — Developer & Agent Architecture Guide

> Comprehensive reference for AI coding agents and developers contributing to **NoemtAddons** on Minecraft `26.1.2`.

---

## 1. Project Overview & Tech Stack

- **Minecraft Version:** `26.1.2`
- **Target Platform:** Modern Fabric (Client-Side)
- **Java Version:** Java 25 (`JavaVersion.VERSION_25`)
- **Kotlin Version:** Kotlin 2.4.x (`fabric-language-kotlin 1.13.13`)
- **Build System:** Gradle with Fabric Loom `1.17.20` & Shadow Gradle plugin (`com.gradleup.shadow`)
- **Config Library:** MoulConfig (`modern-26.1:4.7.2`)
- **Build Artifacts:**
  - `shadowJar`: Primary client payload jar (`noemtaddons.jar`).
  - `loaderJar`: Lightweight bootstrap loader jar (`noemtaddons-loader.jar`).

---

## 2. Directory & Source Layout

```
noemtaddons/
├── build.gradle                     # Multi-task shadow jar & compilation config
├── gradle.properties                # Versions (minecraft 26.1.2, loader 0.19.3, etc.)
└── src/
    ├── main/resources/
    │   ├── fabric.mod.json          # Mod metadata & entrypoints
    │   └── noemtaddons.accesswidener# Access wideners for Minecraft internals
    ├── client/java/                 # Java source set (Mixins ONLY)
    │   └── dev/noemt/client/mixin/
    └── client/kotlin/               # Kotlin source set (All features & core logic)
        └── dev/noemt/client/
            ├── config/              # MoulConfig models & ConfigManager
            ├── event/               # EventBus & event classes
            ├── features/            # Gameplay modules (blood, loadout, map, etc.)
            ├── module/              # Module interface & registry
            ├── render/              # Render2D, Render3D, GuiGraphicsExtractor
            └── utils/               # Centralized shared services & parsers
```

---

## 3. Core Architectural Patterns

### A. Module Registration
All features implement the `Module` interface and are registered in `ModuleManager`:
```kotlin
object MyFeature : Module {
    override val id = "my_feature"
    override val name = "My Feature"
    override val description = "Feature description"
    override val type = ModuleType.LEGIT // or ModuleType.CHEAT

    override fun init() {
        EventBus.register<TickEvent.Start> { onTick() }
        EventBus.register<RenderWorldEvent> { onRender() }
    }
}
```

### B. Event System & Subscriptions
Use `EventBus.register<EventType>(priority) { event -> ... }`:
- **Available Priorities:** `HIGHEST`, `HIGH`, `NORMAL` (default), `LOW`, `LOWEST`.
- **Common Events:**
  - `TickEvent.Start` / `TickEvent.End`: 20Hz game ticks.
  - `RenderWorldEvent`: 3D in-game rendering pass.
  - `RenderOverlayEvent`: 2D screen/HUD rendering pass.
  - `ChatMessageEvent`: Inbound chat packet parsing.
  - `MainThreadPacketReceivedEvent.Pre` / `Post`: Packets dispatched onto client main thread.
  - `WorldChangeEvent`: Player changes dimension, world, or lobby.

---

## 4. Centralized Services & Parsing Utilities (DO NOT DUPLICATE)

Never write separate loops or regexes across features for Scoreboard, TabList, Chat, Items, or Entities. Use the centralized services:

### 1. `ScoreboardUtils`
- **Pre-parsed 50ms snapshot:**
  ```kotlin
  val snap = ScoreboardUtils.getSnapshot()
  val inDungeon = snap.inDungeon               // Boolean
  val floor = snap.dungeonFloor                // "F7", "M6", "E"
  val cleared = snap.dungeonClearedPercent     // 84
  val timeSec = snap.dungeonTimeElapsedSeconds // 134
  val purse = snap.purseCoins                  // Double (parses "14.2M", "500k")
  val isFresh = snap.isFreshDungeonRun         // Boolean
  ```
- **Search Helpers:**
  ```kotlin
  ScoreboardUtils.findLineContaining("Kuudra")
  ScoreboardUtils.findLineStartingWith("Party:")
  ScoreboardUtils.findLineMatching(Regex("""Wave:\s*(\d+)"""))
  ```

### 2. `TabListUtils`
- **Pre-parsed dirty-flag snapshot:**
  ```kotlin
  val snap = TabListUtils.getSnapshot()
  val cleanLines = snap.cleanLines
  val area = snap.area
  val profile = snap.profile
  ```
- **Search Helpers:** `TabListUtils.findLineContaining()`, `TabListUtils.hasLineContaining()`.

### 3. `ChatUtils`
- **Structured Channel Message Parser:**
  ```kotlin
  val parsed = ChatUtils.parseChatMessage(event.unformattedText)
  // parsed.channel: ChatChannel (PARTY, GUILD, OFFICER, COOP, PRIVATE_MESSAGE, ALL, SYSTEM)
  // parsed.sender: "PlayerName"
  // parsed.rank: "MVP+"
  // parsed.message: "clean message body"
  ```
- **Color Stripping:** `text.removeFormatting()` (fast single-pass array copy).

### 4. `ItemUtils` (Modern DataComponents)
- `stack.skyblockId`: Reads Skyblock ID (`id` or `ExtraAttributes.id`).
- `stack.cleanDisplayName`: Formatted-stripped item name.
- `stack.cleanLore`: List of stripped lore strings.
- `stack.getSkyblockRarity()`: Returns `ItemRarity` (`COMMON`..`VERY_SPECIAL`).
- `stack.getStars()`: Count of regular (✪) and master stars (➊–➎).
- `stack.isRecombobulated()`: Checks `rarity_upgrades`.
- `stack.hasLoreContaining("text")` / `stack.findLoreContaining("text")`.
- `ItemUtils.getSkullTexture(stack)`: Zero-copy texture reader from `DataComponents.PROFILE`.

### 5. `LocationUtils`
- Cached fields: `inDungeon`, `inSkyblock`, `onHypixel`, `dungeonFloorNumber`, `inBoss`.

### 6. `MobMatcher`
- `MobMatcher.matches(entity, category, nameFilter, skullTexture)`
- `MobMatcher.getEntityHealth(entity)`: Parses `Pair(currentHp, maxHp)`.
- `MobMatcher.getNametagArmorStands(entity)`: Finds floating nametag armor stands.
- `MobMatcher.getTrueMinibossBody(entity)`: Resolves armor stands to their living mob body.

---

## 5. Performance & Zero-Allocation Rules

1. **Zero-Allocation Render Loops:**
   - **DO NOT** allocate `Color(...)`, `color.darker()`, or `Vector3f` inside `RenderWorldEvent` or `RenderOverlayEvent`.
   - Pre-cache static `Color` constants.
   - Use primitive float normalization for line normals in `Render3D.addLine`.
2. **Held-Item Checking:**
   - Check the player's held item (`heldItem.skyblockId`) **once per frame** outside entity rendering loops.
3. **Chunk & World Queries:**
   - Always restrict chunk scanning in dungeons to the coordinate boundary (`-13..2` chunks).
   - Throttle continuous scans (like mimic detection) to >= 1000ms.
4. **Pathfinding & Static Blocks:**
   - Cache static room floor positions (`getBloodRoomFloorPositions()`) keyed by the room center.

---

## 6. Minecraft 26.1.2 Modding Quirks & Gotchas

### A. DataComponents API vs Legacy NBT
Minecraft 1.20.5+ / 26.1.x replaced traditional NBT tags with `DataComponents`:
- **DO NOT** call `stack.tag` (it no longer exists).
- To read custom Hypixel NBT:
  ```kotlin
  val customData = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
  ```
- To read player skull skins:
  ```kotlin
  val profile = stack.get(DataComponents.PROFILE)
  val texture = profile?.partialProfile()?.properties?.get("textures")?.firstOrNull()?.value
  ```
- To read dye color: `stack.get(DataComponents.DYED_COLOR)?.rgb()`.

### B. Packet Movement Coordinates
- Entity delta move packets (`ClientboundMoveEntityPacket`) encode coordinates as 12-bit fixed-point numbers:
  ```kotlin
  val dx = packet.xa / 4096.0
  val dy = packet.ya / 4096.0
  val dz = packet.za / 4096.0
  ```

### C. Hypixel Composite Mobs
- Hypixel Skyblock mobs are constructed using detached `ArmorStand` nametags floating 1.0m to 3.5m above the entity.
- The Watcher altar zombies spawn at Y >= 73.0 with detached nametags `﴾  The Watcher ﴿` and `Watchful Eye`.
- Always check both `entity.name` and nearby floating `ArmorStand` tags using `MobMatcher.getAllEntityNames()`.

### D. Input Blocking in Menus
- In container screens (e.g. `LoadoutManager`), mixin into `keyPressed`, `mouseClicked`, `mouseReleased`, `mouseDragged`, and `mouseScrolled` on `AbstractContainerScreen` to block user interference during automated swaps.
- Always include an **anti-stall safety timeout** (e.g. 4000ms) to ensure input is never permanently locked during network lag.

---

## 7. Build, Verification & Testing Commands

Always verify changes using Gradle before committing:

```bash
# Fast compile check (Kotlin + Java mixins)
./gradlew compileKotlin compileClientJava

# Full client build and shadow jar creation
./gradlew build

# Check modified files
git status -s
```
