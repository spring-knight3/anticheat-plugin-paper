# AntiCheat

Paper anti-cheat plugin with PacketEvents movement packet monitoring, configurable checks,
staff alerts, setbacks, and flat-file, SQLite, or MySQL violation logging.

## Build and install

Build with Java 25 and Maven:

```sh
mvn package
```

Install `target/anticheat-plugin-paper-0.1.0-SNAPSHOT.jar` together with the
PacketEvents plugin on a Paper 26.2 server. The plugin requires PacketEvents at runtime.
Exemptions use Bukkit's permission API, so LuckPerms is not required.

Configuration files are created in the plugin data folder:

- `config.yml` controls global settings, exemptions, broadcasting, and storage.
- `checks.yml` controls thresholds, enablement, alerts/setbacks (`actions`), and punishments (`punishments`).
- `messages.yml` controls all plugin chat output, staff alerts, and player warning/kick/ban text.
- Existing configurations are merged with newly added defaults. Legacy default VL decay
  is migrated from `1.0` to `0.1` per interval so repeated findings can reach action thresholds.

Use `/ac config reload` to reload configuration, `/acmanage` to change checks,
`/acviolations` to inspect online player violations, and `/ac` for administration.
The SQLite and MySQL JDBC drivers are bundled in the plugin jar. Violations are stored per
player and check in the configured backend, restored asynchronously on join, and saved on
violations, quit, and periodic autosave. Set `punishments.enabled: false` in `config.yml`
to disable configured punishments without disabling alerts or setbacks. Each check's
`punishments` list uses `"VL:console command"` entries, for example
`"30:kick %player% Unfair Advantage"`; thresholds and commands can be changed without code.
Staff alerts go to players with `anticheat.alert` by default. Set `broadcast.BROADCAST GLOBAL`
to `true` to send plugin alerts to all online players; set `broadcast.BROADCAST TO CONSOLE`
to `true` to log them to the server console. `flag-alert`, `kick-alert`, `ban-alert`,
`warn-text`, `kick-text`, and `ban-text` can be edited in `messages.yml`.
Players are directly warned on their first violation and then at intervals configured by
`vl-settings.player-warning-cooldown-ms`.
Built-in punishment thresholds are in `checks.yml` under movement fly (30), speed (35),
NoFall (30), BoatFly (20), reach (10), and AutoClicker (15). Existing kick/ban entries
under `actions` are migrated to `punishments` on reload.

## Checks

The bundled `checks.yml` enables these checks by default:

| Category | Checks | What they inspect |
| --- | --- | --- |
| Movement | Fly, Speed, NoSlow, NoFall, Jesus, Step | Movement packet deltas, vertical motion, client ground claims, and Paper's reported ground/liquid state. |
| Vehicles | BoatFly, EntitySpeed, VehiclePhase | Boat vertical movement, vehicle displacement, and whether the vehicle destination is passable. |
| Combat | Aimbot, Hitbox, Reach, Velocity, Criticals, Rotation | Recent attack rotations, target hitbox/range geometry, observed response to server velocity, and ground-state signals around attacks. |
| Click and block actions | AutoClicker, FastPlace, FastBreak | Animation packet rate/interval regularity, block-place intervals, and block-break duration. |
| Inventory | AutoTotem, InventoryMove, ChestStealer | Totem action timing, inventory clicks during movement, and rapid container clicks. |
| World | AirPlace, Scaffold, Baritone, Timer | Block placement distance/support and facing, repeated movement routes, and movement packet timing. |
| Packets | BadPackets, SelfInteract | Movement packet rate and malformed/out-of-range movement or rotation values, plus attempts to interact with the player's own entity. |
| Chat | Spam | Messages exceeding the configured count in a time window; over-limit messages are cancelled. |

Checks accumulate violation levels independently. Their thresholds and actions are configurable;
the configured punishments listed above are enabled by default. The Baritone detector looks for
repeated quantized movement routes. It is a heuristic, is alert-only in the bundled defaults,
and cannot identify or prove which client mod caused a pattern.

## Scope notes

Checks use Paper events and PacketEvents packet observations. Movement is evaluated using
thresholds and observed server state; this is not a vanilla-complete client physics simulator.
In particular, movement limits do not fully predict effects from attributes, status effects,
blocks, fluids, collisions, knockback, or every movement ability. Timing and interaction
heuristics can also be affected by latency, packet bursts, server lag, tools, and legitimate
player behavior.

Before enabling punitive actions, test and tune `checks.yml` against the server's Minecraft
version, movement abilities, latency, and gameplay. Prefer reviewing staff alerts and violation
details first, especially for heuristic checks such as AutoClicker, Aimbot, Rotation, Baritone,
and FastBreak. A server-side prediction model that accounts for player state and collisions,
plus detector-specific tests against legitimate edge cases, would improve movement accuracy and
reduce false positives.
