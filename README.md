# Planarium Panorama

*An addon for [Ars Nouveau](https://github.com/baileyholl/Ars-Nouveau) (NeoForge 1.21.1).*

> [!NOTE]
> **Made with AI.** This mod was designed and written with **Claude** (Anthropic). The code was generated
> in conversation with Claude, then tested and refined in game. Please keep this in mind when reporting issues
> or reusing the code.

Ars Nouveau's **Planarium** lets you shrink yourself into a pocket dimension inside a jar.
Normally, the world around that jar is a black void. **Planarium Panorama turns the jar into a window:**
from inside, you see the area around the Planarium as a giant world, 32 times bigger,
under the real sky, with real lighting.

---

## Features

### Giant outside view
- The blocks around the Planarium are drawn around the jar, 32 times bigger. You are standing inside the block.
- Fluids and special blocks (chests, signs, banners, heads, beds, bells) are included.
- The view refreshes automatically (every 5 s by default). The Planarium and the block above it are hidden.

### Synced sky and weather
- The jar shows the outside sky: sun, moon phase, stars, sunrise and sunset, the biome's sky color, and grey skies in bad weather.
- Rain and snow fall on the giant view, only where the sky is open. You hear the rain when it rains next to the Planarium.
- Lightning: you hear the thunder of lightning around the Planarium (from its direction), the jar sky flashes, and a bolt that strikes close enough shows up giant like the scenery.
- Clouds: when the Planarium is close to the cloud layer, the outside clouds show up in the jar at the right height, giant like the scenery (vanilla cloud shape, follows the Clouds video setting). Can be turned off in the menu; turned off automatically while Better Clouds is active, since its clouds can't be shown in the jar.
- Only for outside worlds that have a sky. In the Nether (or any world without a sky), the jar shows no sky, but its light still follows the Planarium's surroundings.

### Lighting
One **Lighting** option with three levels:
- **Full** (default): everything from **Sky and scenery**, plus the light at the Planarium's spot inside the jar:
  - fully lit outdoors in daylight, darker at night, dark in a closed room or a cave;
  - a torch or lamp next to the Planarium lights the whole jar through the glass, a bit brighter on its side,
    like the light inside the single block the jar stands for. Lights you place inside the jar still work;
  - with Contraption Lights, this light takes the color of the source outside (blue for a soul campfire, for example);
- **Sky and scenery:** the outside sky in the jar, and real light (sky and block light, smooth lighting) on what you see
  through the glass: the giant view from inside, and the miniature drawn in the Planarium block from outside
  (dark in a cave, lit by a torch next to the Planarium, in its color with Contraption Lights). Lights placed inside
  the jar also light the Planarium block's top and glass from outside. The jar interior stays fully lit, like in Ars Nouveau.
- **OFF:** like in Ars Nouveau (no sky, everything at full brightness: scenery, miniature and jar interior).
- Mobs still can't spawn inside the jar.

### Biome effects
- Grass, leaves and water in the giant view use the colors of the real biomes around the Planarium.
- The jar interior takes the Planarium's biome (client-side only, nothing is changed in the save): its grass, leaves and
  water colors, and also its music, ambient sounds, particles, fog and sky colors (Alex's Caves biomes included).
- The miniature seen from outside uses the colors of the Planarium's biome too.

---

## Mod integrations

All optional and detected automatically.

- **[Dynamic Surroundings](https://modrinth.com/mod/dynamicsurroundingsfabric):** inside the jar, you hear what you would hear next to the Planarium.
    That means biome sounds outdoors, silence in a room, cave ambience underground, and wind high in the sky (in the Aether, for example). The biome's fog follows too.
    With Serene Seasons, the sounds follow the outside world's season (no crickets or birds when it is too cold).
- **[AmbientSounds](https://modrinth.com/mod/ambientsounds):** inside the jar, AmbientSounds plays the sounds of the Planarium's spot:
    its dimension, biome, weather, height, and the blocks around it (leaves, sand, water, open sky...), read from the giant view.
- **[Contraption Lights](https://www.curseforge.com/minecraft/mc-mods/contraption-lights):** colored light also tints the giant view and the miniature. Light coming through the jar's glass
  takes the color Contraption Lights gives to the light source next to the Planarium (blue for a soul campfire, for example).
- **[Serene Seasons](https://www.curseforge.com/minecraft/mc-mods/serene-seasons):** grass and leaves in the giant view and inside the jar follow the outside world's season,
  and it snows in winter.

### Compatibility

Tested together with Planarium Panorama:

| Mod | Status |
|---|---|
| Sodium | Compatible |
| Flywheel | Compatible |
| Veil | Compatible |
| Sable | Compatible |
| True Darkness Biomes | Compatible |
| Dynamic Surroundings | Compatible (integration above) |
| AmbientSounds | Compatible (integration above) |
| Contraption Lights | Compatible (integration above) |
| Serene Seasons | Compatible (integration above) |
| Traveler's Titles | Compatible: entering a jar shows the Planarium's name (or "Planarium") instead of "???" |
| Better Clouds | Compatible: the jar's clouds turn off while Better Clouds is active |
| Modonomicon | Rare issue in singleplayer, see **Known issues** |

---

## Configuration

All options can also be changed in game, from the mod list (**Mods** > **Planarium Panorama** > **Config**), in a YACL screen with tabs, descriptions and reset buttons.

**`config/planariumview-common.toml`**: set by the server (greyed out in the config screen on a multiplayer server).

| Option | Default | Description |
|---|---|---|
| `radius` | `12` | Blocks captured around the Planarium (1–32). |
| `refreshSeconds` | `5` | How often the view updates, in seconds. `0` = OFF (frozen snapshot). |

**`config/planariumview-client.toml`**: each player's own choices.

| Option | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch. `false` = everything like in Ars Nouveau (other choices are kept). |
| `lighting` | `FULL` | `FULL`, `SKY_AND_SCENERY` or `OFF` (see **Lighting** above). |
| `fog` | `VIEW_RADIUS` | Fog on the giant view: `OFF`, `VIEW_RADIUS` (fades out at the edge of the scenery) or `RENDER_DISTANCE` (fades out at your render distance). |
| `biomeColors` | `true` | Biome effects: real biome colors (and seasons) for the giant view and the jar; the Planarium's biome music, sounds and particles in the jar. Shown as **Biome effects**. |
| `weather` | `true` | Rain and snow on the giant view, with the rain sound, and lightning (thunder, sky flash, giant bolts). |
| `clouds` | `true` | Outside clouds in the jar near the cloud layer (off while Better Clouds is active). |
| `coloredLight` | `true` | Contraption Lights colored light. |
| `outsideAmbience` | `true` | Dynamic Surroundings ambience of the Planarium's surroundings. |
| `ambientSounds` | `true` | AmbientSounds ambience of the Planarium's surroundings. |

**Performance tip:** a radius of 16–24 works well. For 32, raise `refreshSeconds` to 10–20.

---

## Requirements

- Minecraft **1.21.1**, NeoForge **21.1+**
- **Ars Nouveau 5.13+**, on both client and server
- **[YACL (Yet Another Config Lib)](https://modrinth.com/mod/yacl) 3.5+**, on the client (for the config screen)
- Optional: Dynamic Surroundings, AmbientSounds, Contraption Lights, Serene Seasons

## Known limitations

- Entities (players, mobs, items) are not shown in the giant view.
- The view is a snapshot: it updates at the refresh interval, and animations (chest lids) don't move.
- Some option changes only fully apply after leaving and re-entering the jar.
- Shader packs (Iris) have not been tested.
- A big Ars Nouveau update may require an update of this addon.

## Known issues

- **Modonomicon (singleplayer):** very rarely, entering a jar fails halfway. "Loading terrain" takes a long time,
  then you float in the void without the jar. The log shows `saveData is null` from Modonomicon.
  This is a timing bug in Modonomicon, not in Planarium Panorama. **Fix:** Save and Quit, then rejoin the world.

---

## How it works

1. **Server:** finds the Planarium the player came from, captures the blocks and light around it,
   and sends this snapshot with the outside time, weather and light.
2. **Client:** turns the snapshot into a mesh with Ars Nouveau's own renderer and draws it 32 times bigger.
3. **Sky and light:** the jar gets a normal sky driven by the synced time, and a built-in datapack gives it sky light.

## Building

1. Put the Ars Nouveau and YACL jars from your modpack in `libs/`.
2. Run `./gradlew build`, or push to GitHub and let the **Build** workflow make the jar.

> [!NOTE]
> Every build is scanned by [VirusTotal](https://www.virustotal.com) through GitHub Actions.
> The link to each scan is in the **VirusTotal Scan** step of the build, in the **Actions** tab.

---

## Credits

- Made with **Claude** (Anthropic): design, code and debugging were done in conversation with Claude.
- [Ars Nouveau](https://github.com/baileyholl/Ars-Nouveau) by Bailey Hollingsworth (LGPL-3.0). Planarium Panorama reuses parts of its Planarium rendering.
- Translucent config screen style inspired by [Better Clouds](https://modrinth.com/mod/better-clouds) by Qendolin. The config screen is built with [YACL](https://modrinth.com/mod/yacl) by isXander.
- Integrations work at runtime, without bundling or modifying the other mods.

## License

LGPL-3.0, matching Ars Nouveau.
