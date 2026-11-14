# TreeCapitator

TreeCapitator is a Fabric server-side mod for Minecraft 26.3. When a player breaks a
wooden log trunk with an axe, the connected tree is felled. The remaining trunk blocks are
removed, the vanilla loot tables are respected, and the axe consumes durability for every
additional log.

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

The mod only runs on the server. It has no client entrypoint and does not replace any vanilla
behavior on the client.

## Commands

All commands are available to every player. No operator permission is required.

| Command | Description |
| --- | --- |
| `/treecapitator` | Shows the current state. |
| `/treecapitator status` | Shows whether TreeCapitator is enabled for the player. |
| `/treecapitator on` | Enables the mod for the player. |
| `/treecapitator off` | Disables the mod for the player. |
| `/treecapitator toggle` | Toggles the mod for the player. |
| `/treecapitator me status` | Shows whether TreeCapitator is enabled for the player. |
| `/treecapitator me on` | Enables the mod for the player. |
| `/treecapitator me off` | Disables the mod for the player. |
| `/treecapitator me toggle` | Toggles the mod for the player. |

All commands are available to every player. No operator permission is required. The commands act on
the executing player.

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
| `break-time-per-log` | `0.05` | Server-side extra breaking time per additional trunk block. |
| `max-break-time-multiplier` | `8.0` | Upper bound for the total server-side breaking time multiplier. |

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

The breaking time of the block that starts the felling is multiplied by the size of the tree. The
extra time is calculated from `break-time-per-log` and capped by `max-break-time-multiplier`.

### Leaves and mushrooms

By default, normal leaves are left to decay naturally after the trunk is removed. The
`break-leaves` option can remove connected leaves immediately without generating drops.

Giant mushrooms are handled separately. When `include-mushrooms` is enabled and
`break-mushroom-caps` is enabled, the caps of a felled mushroom are removed as well.

## Messages

Messages shown to players are written in Italian. The mod logs configuration warnings in English
where the technical log is concerned.

The mod does not send chat messages except for command feedback and failures.

## Implementation notes

The server block break is intercepted before the destruction starts and after it succeeds. The
server-side destroy progress is adjusted through a mixin so the first block of the felling takes
longer as the tree grows.

## Limitations

- Other plugins or claims are not replaced by this mod. Vanilla block interaction rules are
  still respected through the standard server block destruction path.
- Protection systems that cancel block break events are respected.
- Diagonal connectivity is a structural approximation and very large custom-built structures may
  require a higher `max-logs` or `min-leaves` value.

## License

MIT
