# Deathswap

A Fabric mod for the classic **Deathswap** minigame: every few minutes, players swap places in random pairs.
Build traps, survive the ones built for you. **Fewest deaths after all rounds wins.**

Every round is played in a **freshly generated world** (overworld, nether and end), so last round's traps are gone.
Unlimited players, and everything is configurable in-game.

> Built for **Minecraft 26.3** (Fabric). Older versions (1.20.x – 26.2) are being ported, see [Versions](#versions).

## Features

- **Swaps**: random pairs at every swap. With an odd player count, three players rotate. Positions, dimension and fall state are swapped.
- **Rounds**: fixed-length rounds (default 20 min, swap every 5 min, 3 rounds). Every death counts.
  Deaths shortly after a swap give the trap builder a **trap kill**, used as a tiebreaker.
- **Fresh world per round**: new seed each round (or a fixed seed, or the main world's seed), any world preset
  (`minecraft:normal`, `amplified`, `large_biomes`, datapack presets). Portals link to the round's own nether and end.
  Old worlds are deleted.
- **HUD on the left side**, without a visible boss bar:
  - **With the mod on the client**: drawn natively at the left screen edge (`/dshud x|y|scale|background`).
  - **Vanilla clients**: a small server resource pack draws the HUD with shifted fonts, anchored to an invisible boss bar.
    Players can move it with `/ds hud x <px>` / `/ds hud y <px>`.
  - Fallback (pack declined): a one-line action bar.
- **Timer**: visible countdown, or `HIDDEN` (only shown during the warning). Big countdown titles, sounds and particles at the swap.
- **Random items** every 10 seconds (configurable, with a blacklist).
- **Structure placers**: a rare random item such as *Bastion Placer* or *Mansion Placer*. Right-click to build that structure on the spot.
- **Random events** (TNT rain, anvil rain, lightning, mystery potion, zero gravity, mob swarm, inventory shuffle, speed rush).
- **Start kit**, spread spawns with a minimum distance, optional invulnerability after swaps, keep inventory, world border.
- **Lobby with ready system** (`/ready`), auto-start, spectators and late joiners (spectate or join next round).
- **Stats**: per-round results, final ranking with causes of death, best trapper.
- **Config**: `config/deathswap.json`, `/ds config set <key> <value>`, a chest-GUI menu (vanilla clients),
  and a full settings screen when the client has the mod.
- **English & German**, chosen per player by client language (server-side, works for vanilla clients too).

## Install

**Server**: drop `deathswap-<version>.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) into `mods/`.
Vanilla clients can join. They get the HUD resource pack automatically.

**Client (optional)**: install the same jar for the native HUD and the settings screen.

## Commands

| Command | Who | |
|---|---|---|
| `/ready` | everyone | toggle ready in the lobby |
| `/ds spectate` | everyone | sit out / rejoin future games |
| `/ds status`, `/ds stats` | everyone | game state, deaths, traps |
| `/ds hud x\|y <px>`, `reset`, `hide`, `show` | everyone | move the resource-pack HUD |
| `/ds start` (`/ds start vote`) | op | start now (or wait for everyone to be ready) |
| `/ds stop`, `/ds swap`, `/ds endround` | op | control the game |
| `/ds time swap\|round <seconds>` | op | change the timers of the running round |
| `/ds event [name]`, `/ds item`, `/ds placer <structure> [player]` | op | trigger features |
| `/ds config` | op | open the settings (screen or chest menu) |
| `/ds config get\|set\|list\|reload` | op | edit settings by key, e.g. `/ds config set game.swapIntervalSeconds 300` |

`/ds` is short for `/deathswap`.

## Resource pack hosting

`resourcePack.mode` in the config:

- `GITHUB` (default): `resourcepack/dist/` in this repository, served by GitHub.
- `BUILTIN_HOST`: the mod serves the pack itself on `resourcePack.hostPort` (default 25580). Set `resourcePack.hostAddress`
  to the address players connect to, and open the port.
- `CUSTOM_URL`: host `resourcepack/dist/Deathswap-HUD-v1.zip` anywhere (the SHA-1 must match the bundled pack).
- `OFF`: vanilla clients get the action-bar fallback.

The pack contains no Mojang textures: its fonts reference the vanilla glyph texture with shifted offsets.
Regenerate it with `python tools/generate_resourcepack.py <minecraft client jar>`.

## Versions

| Minecraft | Status |
|---|---|
| 26.3 | ✅ |
| 26.1 – 26.2, 1.21.x, 1.20.x | being ported |

Forge/NeoForge and a Paper plugin may follow.

## Building & testing

```
./gradlew build                     # jar in build/libs
./gradlew runClientGameTest         # automated end-to-end test (real client + dedicated server + bot)
./gradlew runClientGameTest -PvanillaHud   # same, testing the resource-pack HUD
```

The client test plays a short game (swap, trap kill, events, structure placer, menus, death at round end,
round 2, game over) and saves screenshots. `tools/sandbox/run-tests.sh` runs it headless in a remote Linux box under Xvfb.

## License

MIT
