# ChunkReset

A server-side Fabric mod for **Minecraft 26.3** that records every block change and lets operators roll any
region of the world back to exactly how it looked at a chosen moment, e.g. one hour ago, **while the server
keeps running**. Players stay online; only the selected blocks change.

Built and tested against Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.162.0+26.3, Java 25.

## What it does

* Logs every block change on the server (players, explosions, fire, water, pistons, mobs, commands, ...)
  with the previous block state and, for chests, barrels, signs, furnaces etc., the previous contents.
* Logs content changes of block entities that happen without the block itself changing (items taken out of
  a chest, a sign edited, ...).
* Rolls back a region, a chunk or a radius around you to its state at any point in the past.
* Keeps rolling back exact: things that did **not** change since the chosen time are left untouched, and the
  rollback itself is recorded, so `/rollback undo` reverts it.
* Stores everything in an SQLite database inside the world folder (`<world>/chunkreset/changes.db`), so the
  history survives restarts. SQLite is bundled; no extra install.
* Applies large rollbacks in batches over several ticks so the server does not freeze.

Not covered: entities (item frames, armor stands, mobs, dropped items) and player inventories. Block
history recorded *before* the mod was installed does not exist, so rollbacks can only go back to the moment
the mod started recording.

## Installation

1. Install a Fabric server for Minecraft 26.3 (Java 25 or newer is required by the game).
2. Put `fabric-api-0.162.0+26.3.jar` (or newer for 26.3) and `chunkreset-1.0.0.jar` into the `mods/` folder.
3. Start the server. The log shows `Recording block changes to .../chunkreset/changes.db`.

The mod works on dedicated servers and in single player (it is server-side only; clients do not need it).

## Commands

All commands need operator permission level 2 (`gamemasters`, the same as `/fill`); `purge` needs level 3.
`/chunkreset` is an alias for `/rollback`.

| Command | Effect |
| --- | --- |
| `/rollback region <from> <to> <time> [<dimension>]` | Restore the box between the two corners to how it was `<time>` ago. |
| `/rollback chunk <time> [<chunkRadius>]` | Restore the chunk you stand in (plus `chunkRadius` chunks around it), full height. |
| `/rollback radius <blocks> <time>` | Restore a cube of `blocks` radius around you. |
| `... preview` | Append `preview` to any of the above to only count what would change. |
| `/rollback undo` | Revert your last rollback (or undo), restoring the state from right before it. |
| `/rollback lookup <pos> [<count>]` | Show the recorded history of one block: when, who, what. |
| `/rollback status` | Database statistics. |
| `/rollback purge <olderThan>` | Delete history older than the given duration. |

`<time>` is a duration: `90s`, `30m`, `1h`, `2d12h`, `1w`. Coordinates accept `~` relative notation.

Example from the original request, restoring a griefed area to how it was one hour ago:

```
/rollback region 100 12 100 300 14 500 1h
```

Check first without changing anything:

```
/rollback region 100 12 100 300 14 500 1h preview
```

Rollbacks report the number of blocks and block entities restored. Changes made by a rollback show up in
`/rollback lookup` with the cause `#rollback:<name>`; player actions show the player name, explosions show
`#explosion:<source>`, everything else (fire, water, mobs, commands) shows `#environment`.

## Configuration

`config/chunkreset.json` is created on first start:

| Key | Default | Meaning |
| --- | --- | --- |
| `retentionDays` | `30` | Delete history older than this many days (checked hourly). `0` keeps everything. |
| `blocksPerTick` | `4096` | Positions restored per tick. Lower for less lag, higher for faster rollbacks. |
| `maxRegionChunks` | `16384` | Refuse rollback regions spanning more chunks than this. |
| `trackBlockEntityContents` | `true` | Also record container/sign content changes that do not change the block. |
| `blockEntityMinIntervalMs` | `1000` | Minimum time between two recorded content changes of the same block entity (prevents hoppers etc. from flooding the log). |
| `ignoredBlockEntityKeys` | furnace timers, brewing progress, ... | Transient NBT keys that are ignored and not restored. |
| `loadUnloadedChunks` | `true` | Load chunks a rollback needs. If `false`, blocks in unloaded chunks are skipped and reported. |

## Building from source

Requires JDK 25.

```
./gradlew build
```

The mod jar is written to `build/libs/chunkreset-1.0.0.jar`.

## How it works

* `LevelChunk.setBlockState` is wrapped with a mixin; every block modification of a loaded chunk goes through
  it. Before the original runs, the previous block state and the previous block entity data are captured and
  queued; a background thread writes them to SQLite in batches.
* `Level.blockEntityChanged` (what `BlockEntity.setChanged()` calls) marks a block entity as dirty. At the end
  of the tick its data is compared with a snapshot of its previous data and the difference is recorded.
  Snapshots are taken when a chunk loads and dropped when it unloads.
* A rollback to time `T` asks the database for every position inside the region that changed after `T`.
  For each position the oldest change after `T` tells what was there at `T` (the old block state of the
  oldest block change, the old data of the oldest change of any kind). Positions without changes after `T`
  are already correct and are not touched.
* Historic blocks are placed without neighbour updates, drops, placement logic or container side effects, so
  the region comes back exactly as recorded and nothing spills or cascades.

## Notes

* The database can grow quickly on busy servers; `retentionDays` keeps it bounded. Space of deleted rows is
  reused by SQLite but the file does not shrink by itself.
* The bundled SQLite driver extracts a small native library to the system temp directory on first use. If
  your server's temp directory is mounted `noexec`, start Java with `-Dorg.sqlite.tmpdir=/some/writable/dir`.
* If the database cannot be opened the server still starts; the mod logs an error and `/rollback` reports
  that it is unavailable.
