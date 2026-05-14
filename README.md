# MarisSpawn

MarisSpawn is a Folia-safe spawn, warp, and AFK location plugin for Paper based servers.

## What It Handles

- Spawn teleport and selection
- Warp teleport and management
- AFK location teleport and management
- Admin setup commands for every location type
- GUI-driven player flow backed by plugin config files

## Requirements

- Paper / Folia 1.21+
- Java 21

## Installation

1. Drop the plugin jar into the `plugins` folder.
2. Start the server once.
3. Edit the generated files in `plugins/MarisSpawn` if needed.
4. Restart the server or reload the plugin through your normal maintenance flow.

## First Setup

1. Stand where you want the default spawn.
2. Run `/setspawn <name>`.
3. Stand where you want a warp.
4. Run `/setwarp <name>`.
5. Stand where you want an AFK point.
6. Run `/setafk <name>`.

Saved locations are stored in `location.yml`.

## Player Commands

- `/spawn [name]` - Open the spawn GUI or teleport to a saved spawn.
- `/warp <destination>` - Teleport to a warp.
- `/afk [name]` - Open the AFK GUI or teleport to a saved AFK location.

## Admin Commands

- `/setspawn <name>` - Save a spawn location.
- `/setwarp <name>` - Save a warp location.
- `/setafk <name>` - Save an AFK location.
- `/delwarp <name>` - Remove a warp.

## Permission

- `marisspawn.admin` - Access admin location commands.

## Files

- `config.yml` - Main settings.
- `message.yml` - Messages shown to players.
- `location.yml` - Saved spawn, warp, and AFK locations.

## Notes

- This plugin is marked as Folia supported.
- Keep location names clean and consistent if you expose them in GUIs.