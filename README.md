# PearlStasisFix

Ender pearl stasis chamber fix plugin - turns ender pearls from stasis chamber into. 
Basically the open-source & free/libre equivalent to [PearlFixFolia](https://www.curseforge.com/minecraft/bukkit-plugins/pearlfixfolia).
(not based on that plugin at all, plus I never tried it since it's closed source and not very downloaded/known).
Also makes stasis chambers not dissapear when the player disconnects!
Now also works with most nether ender pearl stasis chambers!

## WARNING: Made with AI (cuz idk how to use Java)

I am a random dev, and you should really not trust random stuff you find on the Internet. Take a look at the source code which is only a few hundred lines long. (click on the GitHub "view source" link on the side). And if you prefer, compile the project yourself with `mvn clean package` (requires Java JDK 21+ and Maven installed).

## Configuration

`plugins/PearlStasisFix/config.yml` is created on first start.

| Option | Default | Description |
| --- | --- | --- |
| `chunk-radius` | `1` | Chunks kept loaded around each stasis chamber, in every direction, while its pearl is in stasis. `0` = just the pearl's chunk, `1` = 3x3, `2` = 5x5, ... (max `8`). |

The chunks are kept loaded with Paper plugin chunk tickets (the same kind of load as `/forceload`), so they stay loaded **and ticking** even with nobody nearby. The cost grows with the square of the radius - keep it as low as you can. Overlapping stasis chambers share chunks safely (tickets are reference-counted), so retrieving one pearl never unloads chunks another one still needs.

### Stasis detection

A thrown pearl only becomes a stasis when it is actually *held* by a stasis medium for a few ticks in a row:

| Option | Default | Description |
| --- | --- | --- |
| `detection.bubble-columns` | `true` | Upward bubble columns (water over soul sand). Plain water and downward (magma) columns never count, so pearls thrown into lakes behave normally. |
| `detection.honey-blocks` | `true` | Pearls sliding down the side of a honey block (Nether-style chambers). |
| `detection.extra-blocks` | `[]` | Extra block types a pearl counts as held by when touching them, using the same touching rule as honey. Bukkit material names, e.g. `[SLIME_BLOCK, COBWEB]`. |
| `detection.min-ticks-bubble-column` | `2` | Consecutive ticks in an upward bubble column before it counts. |
| `detection.min-ticks-touch-medium` | `10` | Consecutive ticks touching honey / an extra block before it counts. A pearl that merely grazes one on the way past is ignored. |

### Pearl height

`dummy-height` (default `0.87`) is how far above the floor of its water block the parked pearl sits. The water surface is at about `0.89`; lower the value in `0.01` steps to sink the pearl a little. It only applies to pearls that enter stasis after the change.

Things to be aware of:

- **Mob spawners / mob farms still need a player nearby.** Vanilla only activates a spawner (and only spawns mobs) when a *player* is within range, and a loaded chunk is not a player. `chunk-radius` keeps redstone, crops, hoppers, furnaces etc. running - it will not make a spawner work on its own.
- **Servers that pause when empty.** Since Minecraft 1.21.2 vanilla servers stop ticking after `pause-when-empty-seconds` (default `60`) with no players online, which also stops any loaded chunks. Paper defaults this to `-1` (never pause). The plugin logs a warning on startup if it finds a value above `0`.

## Limitations

- Not very well tested (I made it for a private SMP with friends where we use pearl stasis from time to time)
- Only tested for 26.1.x/26.2, but may be compatible with 1.20.x/1.21.x
