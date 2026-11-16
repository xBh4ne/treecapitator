# TreeCapitator

TreeCapitator is a Fabric server-side mod for Minecraft 26.3. When a player breaks a wooden log
trunk with an axe, the connected tree is felled. The remaining trunk blocks are removed, the
vanilla loot tables are respected, and the axe consumes durability for every additional log.

The mod also handles giant mushrooms: a felled mushroom stem removes the connected mushroom
structure instead of leaving floating caps behind.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1
- Fabric API 0.165.2 or newer
- Java 25

## Installation

1. Place `treecapitator-1.0.0.jar` into the `mods` folder of a Fabric server.
2. Start the server once. The default configuration is created at
   `config/treecapitator.properties`.
3. Restart the server if an existing configuration file is present.

The mod only runs on the server. It has no client entrypoint, no mixins and no custom packets, so
vanilla clients work without installing anything. The first block is broken at the normal vanilla
speed; the rest of the tree is then felled by the server.

## Commands

All commands are available to every player. No operator permission is required.

| Command | Description |
| --- | --- |
| `/treecapitator` | Shows the current state. |
| `/treecapitator status` | Shows whether TreeCapitator is enabled for the player. |
| `/treecapitator toggle` | Toggles the mod for the player. |

All commands act on the executing player.

The default state is enabled for all players.

Player preferences are stored per UUID in the world folder, for example
`world/treecapitator_disabled.txt`. The preferences survive server restarts.

## Configuration

The file `config/treecapitator.properties` is created automatically on first start. Unknown keys
are ignored.

| Key | Default | Description |
| --- | --- | --- |
| `max-logs` | `256` | Maximum number of connected trunk blocks that may be felled at once. A larger tree is not felled at all. |
| `require-axe` | `true` | When true, only items in the vanilla `minecraft:axes` item tag can trigger felling. When false, any held item can trigger it. |
| `sneak-disables` | `true` | When true, sneaking while breaking a trunk block breaks only that block. |
| `break-leaves` | `false` | When true, leaves connected to the felled trunk are removed immediately. When false, leaves are left to decay naturally. |
| `leaf-radius` | `4` | Maximum distance in blocks from a trunk block for connected leaves used for detection and removal. |
| `min-leaves` | `1` | Minimum number of connected leaves required for a trunk block to be felled. Trees with fewer leaves are not felled. |
| `include-mushrooms` | `true` | When true, giant mushroom stems are treated like trunks. |
| `break-mushroom-caps` | `true` | When true, connected mushroom caps are removed when a mushroom is felled. Vanilla mushrooms do not decay naturally, so this defaults to true. |
| `allow-diagonal` | `true` | When true, trunk blocks that are connected by edge or corner neighbours are treated as one tree. |
| `break-delay-ms` | `0` | Milliseconds to wait between sequential trunk breaks during felling. |

Invalid values are replaced by the defaults and a warning is written to the server log.

## Behavior

### Activation

Felling starts only when all of the following are true:

1. The broken block belongs to the vanilla `minecraft:logs` block tag, or is a giant mushroom
   stem when `include-mushrooms` is enabled.
2. The held item is in the vanilla `minecraft:axes` item tag, unless `require-axe` is false.
3. The player has not disabled TreeCapitator for themselves.
4. The player is not sneaking and not in spectator mode.

Bamboo blocks and blocks that are not part of the connected trunk are ignored.

### Tree detection

The trunk scan starts from the broken block and performs a breadth-first search over connected
blocks. Face neighbours are always followed; edge and corner neighbours are followed when
`allow-diagonal` is enabled.

The scan stops when `max-logs` is reached. If the connected tree is larger, nothing is felled.

For every trunk, connected foliage is collected starting from blocks touching the trunk. A
natural tree must have at least `min-leaves` connected leaves. A log pillar that has no connected
canopy does not trigger felling.

### Felling

Additional trunk blocks are removed from the bottom upwards in a deterministic order. Breaks are
performed through the vanilla server block destruction path, so loot tables, Fortune, Silk Touch
and tool durability behave like vanilla.

The axe consumes durability for every additional trunk block. If the axe breaks during the
felling sequence, the remaining trunks are left in place.

In creative mode the blocks are removed without durability consumption or item drops, matching
vanilla creative behavior.

### Breaking time

The first block is broken by vanilla at the normal vanilla speed. The mod does not change the
hardness of any block, so no client-side mod is required.

Additional trunk blocks are then broken one at a time, with at least one server tick between
consecutive breaks. `break-delay-ms` adds further delay between them; the default value of `0`
breaks them back to back on consecutive ticks. The per-tick pacing keeps very large trees from
being removed in a single server tick.

### Leaves and mushrooms

By default, normal leaves are left to decay naturally after the trunk is removed. The
`break-leaves` option can remove connected leaves immediately without generating drops.

Giant mushrooms are handled separately. When `include-mushrooms` is enabled and
`break-mushroom-caps` is enabled, the caps of a felled mushroom are removed as well.

Leaves and caps are removed in batches of up to 128 blocks per tick, so a very large canopy does
not cause a single-tick spike.

## Messages

Messages shown to players are written in Italian. The mod logs configuration warnings in English
where the technical log is concerned.

The mod does not send chat messages except for command feedback and failures.

## Implementation notes

The server listens to the vanilla block break event. After the first block is destroyed, the
connected trunk is scanned and the remaining logs are queued in a per-tick step queue, so a large
tree is removed gradually instead of all at once. Each queued break goes through the standard
server block destruction path.

The implementation is written to stay cheap on a busy server:

- The break event returns after a handful of field and tag checks for blocks that cannot start a
  felling, before touching the world or the per-player preferences file.
- The scan tracks block positions as packed `long` values in primitive hash sets and lists, and
  reuses a single mutable position for world lookups. It allocates nothing per visited neighbour
  and reads each candidate block state once.
- Scanning stops early when the tree is too large, when it has no canopy, or when a felling cannot
  happen anyway, so foliage is only materialised and sorted when it will be used.
- One small job object per tree holds the plan and its cursors. There is no scheduled task or
  lambda per block, and the tick handler is a plain FIFO queue that exits immediately when idle.

## Limitations

- Other plugins or claims are not replaced by this mod. Vanilla block interaction rules are
  still respected through the standard server block destruction path.
- Protection systems that cancel block break events are respected.
- Diagonal connectivity is a structural approximation and very large custom-built structures may
  require a higher `max-logs` or `min-leaves` value.
- The first block always breaks at the vanilla speed: the mod does not change block hardness.

## License

MIT
