# BlockLedger

Block logging with inspect, rollback and restore for Paper 26.3 - a lightweight CoreProtect
alternative. Every block break, place, explosion, fire, piston move, bucket use and container click is
written to a local SQLite database, and `/blockledger rollback` puts things back. It also records the
other things players do: drops, pickups, crafting, eating, fishing, item breaks, sign and book edits,
entity interactions, placed boats and minecarts, commands, kills and deaths.

**Download:** take `BlockLedger-1.0.0.jar` from the
[latest release](https://github.com/orbit-cyber/BlockLedger/releases/latest) and drop it in your
server's `plugins/` folder.

## Requirements

- Paper 26.3 (or any 26.3-compatible fork)
- Java 25, which 26.3 itself requires
- No other plugin. The SQLite driver is downloaded from Maven Central by Paper on first start
  (`libraries:` in `plugin.yml`), so the server needs internet access once.

## Building

```bash
mvn package
```

The jar lands in `target/BlockLedger-1.0.0.jar`. Drop it in `plugins/` and restart.

GitHub Actions builds it too: every push to `main` runs `.github/workflows/build.yml`, uploads the jar
as a build artifact and attaches it to the
[v1.0.0 release](https://github.com/orbit-cyber/BlockLedger/releases/latest), so you can download a
ready jar from the GitHub page without a local JDK.

## Commands

| Command | What it does |
| --- | --- |
| `/blockledger inspect` (alias `/bl i`) | Toggle inspect mode: left or right click a block to see who changed it and when |
| `/blockledger lookup <filters>` | List matching records, 10 per page |
| `/blockledger rollback <filters>` | Put the matching blocks back to how they were before |
| `/blockledger restore <filters>` | Re-apply the matching changes |
| `/blockledger purge <days>` | Delete records older than that many days |
| `/blockledger status` | Record count, file size, oldest record, write queue |
| `/blockledger reload` | Re-read `config.yml` |

`/restore` and `/rollback` also work on their own, without the `blockledger` prefix - same filters,
same permissions.

### Filters

| Filter | Meaning |
| --- | --- |
| `u:<player>` | Only changes made by this player (or mob, for explosions) |
| `t:<time>` | Only changes newer than this: `30s`, `15m`, `6h`, `7d`, `2w` |
| `r:<radius>` | Only changes within this many blocks of you, or of `x:` `y:` `z:` |
| `a:<action>` | Only this action: `break`, `place`, `explosion`, `fire`, `piston`, `bucket`, `natural`, `container`, `kill`, `drop`, `pickup`, `craft`, `consume`, `use`, `sign`, `entity`, `command`, `death` |
| `b:<block>` | Only changes involving a block whose name contains this |
| `p:<page>` | Page number for `lookup` |
| `x:` `y:` `z:` `w:` | Exact coordinates, and world |

Examples:

```
/bl lookup u:Steve t:1h
/bl rollback u:Griefer r:50 t:2h
/bl restore u:Steve b:diamond
/bl rollback a:explosion t:10m r:30
/bl lookup b:chest w:world
```

`rollback` and `restore` refuse to run without `u:`, `r:`, `a:` or `b:`, so a typo cannot wipe the
whole map. If you really mean everything, add `global`:

```
/bl rollback global t:5m
```

## What gets recorded

Every action below is on by default except `natural`, which is the noisy one.

| Action | What is stored |
| --- | --- |
| `break` / `place` | The full block state before and after, so rollback restores stairs, slabs and water levels exactly |
| `explosion` / `fire` | Every block destroyed, plus what caused it (creeper, TNT, a player with flint and steel) |
| `piston` | The moved block leaving one position and arriving at another |
| `bucket` | Water, lava and powder snow being placed or picked up |
| `container` | Chests, barrels, hoppers, shulkers and furnaces being used, down to the slot and item |
| `kill` / `death` | Mobs a player killed, and every player death with who or what killed them |
| `drop` / `pickup` | Items dropped and picked up, with the amount |
| `craft` | Crafting and smithing results |
| `consume` | Food, potions and milk being eaten or drunk |
| `use` | Fishing catches, items breaking, right-clicking entities and armor stands |
| `sign` | Sign text written or erased, and books written or titled |
| `entity` | Boats, minecarts, armor stands and end crystals placed |
| `command` | Every command a player runs, as typed |
| `natural` | Liquid flow, block spread, leaves decaying, entities changing blocks (off by default) |

## How it behaves

- **Writes are cheap.** Events only add to an in-memory queue; the database is written asynchronously
  every `storage.flush-interval-ticks` ticks (one second by default). A busy server never waits on
  the disk.
- **Rollbacks are gentle.** Blocks are changed `limits.blocks-per-tick` at a time (200 by default)
  instead of all at once, and a rollback's own writes are not recorded again.
- **Blocks keep their exact state.** Stairs, slabs, signs and water levels are stored as full block
  data (`minecraft:oak_stairs[facing=east,half=bottom]`), not just the material.
- **Order is respected.** A rollback applies the newest change first, so the oldest known state is
  what you end up with; a restore does the opposite.
- **Old records are pruned** automatically every six hours (`purge.enabled`, `purge.days`).

## Permissions

| Node | Default | Meaning |
| --- | --- | --- |
| `blockledger.command` | op | Use `/blockledger` |
| `blockledger.inspect` | op | Toggle inspect mode |
| `blockledger.lookup` | op | Look up records |
| `blockledger.rollback` | op | Roll back and restore |
| `blockledger.purge` | op | Delete old records |
| `blockledger.status` | op | Database statistics |
| `blockledger.reload` | op | Reload the config |
| `blockledger.bypass` | false | This player's changes are not recorded |

## Configuration

See `config.yml` in `plugins/BlockLedger/`. The interesting knobs:

- `log.natural-changes` - liquid flow, leaves decaying, block spreading. Very noisy; off by default.
- `log.commands` - every command typed. Useful for moderation, noisy on a big server.
- `log.items`, `log.interactions`, `log.deaths` - drops, pickups, crafting, sign edits, entity clicks,
  deaths. All on by default.
- `ignore.worlds` / `ignore.blocks` - skip a creative world or a block type entirely.
- `limits.max-rollback-results` - the hard cap on one rollback (20,000 by default).

## Notes and limits

- Block logging is per-block. Chest **contents** are recorded as "X took N of Y from slot Z", not as
  a full inventory snapshot, so a rollback restores blocks and not items.
- Pistons are recorded as the moved block leaving one position and arriving at another.
- `natural` logging covers block spread, liquid flow, leaves and entities changing blocks. Fire
  spread and growth of every plant are not all covered.
- Commands are stored as the player typed them, including anything they typed as arguments.

## Layout

```
src/main/java/net/blockledger/
  BlockLedgerPlugin.java   plugin entry point, config, tasks
  BlockListeners.java      Bukkit events to ledger entries
  InspectListener.java     click-to-inspect
  BlockLedgerCommand.java  /blockledger, filters, output
  BlockChanger.java        applies a rollback a few blocks per tick
  Database.java            SQLite storage, async flush and queries
  LedgerEntry.java         one recorded change
  ActionType.java          the kinds of change
  Query.java               filter for lookups and rollbacks
  Settings.java            parsed config
  BlockStates.java         block data to string and back
  Text.java                message formatting
```
