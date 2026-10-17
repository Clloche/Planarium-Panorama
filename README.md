# Planarium Panorama

*An addon for [Ars Nouveau](https://github.com/baileyholl/Ars-Nouveau) (NeoForge 1.21.1).*

> [!NOTE]
> **Made with the help AI.** The code was written with the help of **Claude** (Anthropic) and **Codex** (OpenAI).

Ars Nouveau's Planarium lets you shrink yourself into a pocket dimension inside a jar. Normally, the world around
that jar is a black void. Planarium Panorama turns the jar into a window: from inside, you see the area around the
Planarium as a giant world, 32 times bigger, under the real sky, with real lighting!

## Features

Include : 
- **Giant view:** the blocks around the Planarium, drawn 32 times bigger around the jar.
- **Synced sky and weather:** the outside time, sun, moon, rain, snow, clouds and lightning.
- **Real lighting:** the giant view, the miniature and the jar interior are lit like the Planarium's real spot.
- **Live scenery:** block changes outside (redstone, pistons, doors...) show up in the giant view within seconds.
- **Outside sounds:** explosions, blocks, mobs and jukebox music from around the Planarium, heard from their direction.

Everything can be changed in game: **Mods** > **Planarium Panorama** > **Config**.

## Optional integrations

The mods are detected automatically and their integration can be turned on or off in the config : 
- [AmbientSounds](https://www.curseforge.com/minecraft/mc-mods/ambientsounds)
- [Contraption Lights](https://www.curseforge.com/minecraft/mc-mods/contraption-lights) (Colored light)
- [Dynamic Surroundings](https://www.curseforge.com/minecraft/mc-mods/dynamic-surroundings)
- [Enhanced Celestials 2](https://www.curseforge.com/minecraft/mc-mods/enhanced-celestials-2-default-lunar-events)

## Compatible mods

Planarium Panorama is made to be compatible with as many mods as possible. This list does not include every compatible mod, just the most popular ones or some that require additional information.

| Mod and version | Status | Details |
|---|:---:|---|
| [Create](https://www.curseforge.com/minecraft/mc-mods/create) / [Flywheel](https://modrinth.com/mod/flywheel)<br>Create v6.0.10 + Flywheel v1.0.6 | ✅ | |
| [NeoVoxy (JohnSnow)](https://github.com/NHblock-Johnsnow/neo-voxy-multiversion)<br>v0.5.0-alpha.12 | ✅ | The giant scenery can go past the render distance |
| [Sable](https://www.curseforge.com/minecraft/mc-mods/sable)<br>v2.0.6 | ✅ | |
| [Serene Seasons](https://modrinth.com/mod/serene-seasons)<br>v10.1.0.3 | ✅ | Grass and leaves in the jar take the season colors of the Planarium's world |
| [Sodium](https://modrinth.com/mod/sodium)<br>v0.8.13 | ✅ | |
| [Traveler's Titles](https://modrinth.com/mod/travelers-titles)<br>v5.1.3 | ✅ | Shows the Planarium's name when entering a jar, instead of "???" |
| [Windy: Configurable](https://www.curseforge.com/minecraft/mc-mods/windy-configurable)<br>v1.2.1 | ✅ | The ambiant winds can be viewed in the Planarium (Follows the Windy config) |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons)<br>v3.3.3 | ⚠️ | Doesn't work inside a jar: turned off automatically there (it would draw the void around it), unchanged everywhere else |
| [Jaden's Nether Expansion](https://www.curseforge.com/minecraft/mc-mods/jadens-nether-expansion)<br>v2.4.1 | ⚠️ | Its custom particles (flames, smoke...) are not shown in the giant scenery and the miniature; vanilla particles are |

The version indicated is the version it has been tested with.

`✅` means compatible, `⚠️` means partly compatible. Versions shown are the ones that were tested.

## Known issues

Modonomicon (singleplayer): very rarely, entering a jar fails halfway. Loading takes a long time, then you float in the void without the jar. This comes from a timing bug with Modonomicon. To fix it, just Save and Quit then rejoin the world.

## Requirements

- Minecraft **1.21.1**, NeoForge **21.1+**
- **Ars Nouveau 5.13+** (client and server)
- **[YACL](https://modrinth.com/mod/yacl) 3.5+** (client, for the config screen)

## Credits and license

- [Ars Nouveau](https://github.com/baileyholl/Ars-Nouveau) by Bailey Hollingsworth. Planarium Panorama reuses parts of
  its Planarium rendering.
- Config screen style inspired by [Better Clouds](https://modrinth.com/mod/better-clouds) by Qendolin.
- License: LGPL-3.0, matching Ars Nouveau.
