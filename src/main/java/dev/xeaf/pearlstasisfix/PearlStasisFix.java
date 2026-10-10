package dev.xeaf.pearlstasisfix;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.BubbleColumn;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class PearlStasisFix extends JavaPlugin implements Listener {

    private final ConcurrentHashMap<UUID, Entity> activeStasis = new ConcurrentHashMap<>();

    // Counts, per player, how many of their in-flight pearls are currently "armed"
    // (already inside a stasis medium, being tracked toward becoming a dummy but not
    // resolved yet). Used to suppress vanilla's own teleport-on-hit so it doesn't fire
    // a moment before/alongside our own logic - see onPearlTeleport() below.
    private final ConcurrentHashMap<UUID, Integer> armedPearlCounts = new ConcurrentHashMap<>();

    private NamespacedKey markerKey;
    private NamespacedKey ownerKey;

    private File dataFile;
    private FileConfiguration dataConfig;

    // ------------------------------------------------------------------
    // Chunk loading around stasis chambers (config.yml -> chunk-radius)
    // ------------------------------------------------------------------

    // Hard upper bound for chunk-radius. A radius of r means (2r+1)^2 chunk tickets per
    // stasis, so this exists mainly to stop a typo (e.g. 80 instead of 8) from asking the
    // server to keep thousands of chunks loaded.
    private static final int MAX_CHUNK_RADIUS = 8;

    private int chunkRadius = 1;

    // ------------------------------------------------------------------
    // Stasis detection settings (config.yml -> detection.*)
    // ------------------------------------------------------------------

    // How far above the floor of its water block the parked pearl sits (config.yml ->
    // dummy-height). The water surface is at ~0.89, so values just under that put the
    // pearl right on the waterline.
    private double dummyHeight = 0.87;

    private boolean detectBubbleColumns = true;
    private int minTicksBubbleColumn = 2;

    // Blocks a pearl has to be TOUCHING (not just inside) to count: honey blocks plus
    // whatever the admin listed under detection.extra-blocks.
    private final Set<Material> touchMediums = EnumSet.noneOf(Material.class);
    private int minTicksTouchMedium = 10;

    // Paper allows a plugin only ONE ticket per chunk, so two stasis chambers whose areas
    // overlap can't each hold their own ticket for the shared chunks - releasing one would
    // silently drop the other's. We therefore count how many stasis areas want each chunk
    // and only add/remove the real ticket on the 0->1 / 1->0 transitions.
    private final Object ticketLock = new Object();
    private final Map<ChunkPos, Integer> ticketRefs = new HashMap<>();
    private final Map<UUID, TicketArea> ticketAreas = new HashMap<>();

    private record ChunkPos(UUID world, int x, int z) {}

    private record TicketArea(UUID world, int centerX, int centerZ, int radius) {}

    @Override
    public void onEnable() {
        markerKey = new NamespacedKey(this, "stasis-marker");
        ownerKey = new NamespacedKey(this, "stasis-owner");

        getServer().getPluginManager().registerEvents(this, this);

        saveDefaultConfig();
        addMissingConfigDefaults();
        loadSettings();
        warnIfServerPausesWhenEmpty();

        loadDataFile();
        reattachAllPersistedStasis();

        getLogger().info("PearlStasisFix v" + getDescription().getVersion() + " enabled (dummy-height "
                + dummyHeight + ").");
    }

    @Override
    public void onDisable() {
        releaseAllTicketAreas();
        saveDataFile();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadSettings();
            sender.sendMessage("PearlStasisFix config reloaded (dummy-height " + dummyHeight
                    + "). Existing pearls keep the height they were created at; "
                    + "chunk-radius only applies to stasis chambers created from now on.");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /**
     * saveDefaultConfig() only writes config.yml when it doesn't exist, so after an update
     * the file on disk would silently lack any newly added options. Copy in whatever is
     * missing (existing values are never overwritten).
     */
    private void addMissingConfigDefaults() {
        FileConfiguration config = getConfig();
        if (config.getDefaults() == null) return;
        if (config.getKeys(true).containsAll(config.getDefaults().getKeys(true))) return;
        config.options().copyDefaults(true);
        saveConfig();
        getLogger().info("Added new options to config.yml.");
    }

    private void loadSettings() {
        int configured = getConfig().getInt("chunk-radius", 1);
        chunkRadius = Math.max(0, Math.min(configured, MAX_CHUNK_RADIUS));
        if (configured != chunkRadius) {
            getLogger().warning("chunk-radius " + configured + " is out of range, using " + chunkRadius
                    + " instead (allowed: 0-" + MAX_CHUNK_RADIUS + ").");
        }
        int side = chunkRadius * 2 + 1;
        getLogger().info("Chunk radius around each stasis: " + chunkRadius + " (" + side + "x" + side + " chunks).");

        dummyHeight = Math.max(0.0, Math.min(0.99, getConfig().getDouble("dummy-height", 0.87)));
        detectBubbleColumns = getConfig().getBoolean("detection.bubble-columns", true);
        minTicksBubbleColumn = Math.max(1, getConfig().getInt("detection.min-ticks-bubble-column", 2));
        minTicksTouchMedium = Math.max(1, getConfig().getInt("detection.min-ticks-touch-medium", 10));

        touchMediums.clear();
        if (getConfig().getBoolean("detection.honey-blocks", true)) {
            touchMediums.add(Material.HONEY_BLOCK);
        }
        List<String> extras = getConfig().getStringList("detection.extra-blocks");
        for (String name : extras) {
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material == null || !material.isBlock()) {
                getLogger().warning("detection.extra-blocks: \"" + name + "\" is not a valid block type, ignoring it.");
                continue;
            }
            touchMediums.add(material);
        }
        getLogger().info("Stasis detection: bubble columns " + (detectBubbleColumns ? "on" : "off")
                + ", touch mediums " + touchMediums + ".");
    }

    /**
     * Since Minecraft 1.21.2 the vanilla server stops ticking entirely once nobody has been
     * online for pause-when-empty-seconds (vanilla default 60; Paper defaults to -1 = never).
     * A paused server ticks nothing - including chunks we keep loaded - so warn if it's on.
     */
    private void warnIfServerPausesWhenEmpty() {
        File props = new File("server.properties");
        if (!props.isFile()) return;
        try (FileReader reader = new FileReader(props, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(reader);
            String value = p.getProperty("pause-when-empty-seconds");
            if (value != null && Integer.parseInt(value.trim()) > 0) {
                getLogger().warning("server.properties has pause-when-empty-seconds=" + value.trim()
                        + ". With nobody online the server will pause and stasis chunks will NOT tick. "
                        + "Set it to -1 (and restart) if you want them to keep running.");
            }
        } catch (IOException | NumberFormatException ignored) {
            // best-effort check only
        }
    }

    // ------------------------------------------------------------------
    // Chunk ticket management (reference-counted, see ticketRefs above)
    // ------------------------------------------------------------------

    /**
     * Keeps the stasis chunk and every chunk within chunk-radius of it loaded (and ticking,
     * as plugin tickets use the same level as /forceload) until releaseTicketArea() is called
     * for this player. Safe to call repeatedly: re-acquiring the same area is a no-op, and
     * acquiring a different one first releases the player's previous area.
     */
    private void acquireTicketArea(UUID playerId, Location anchor) {
        World world = anchor.getWorld();
        if (world == null) return;

        TicketArea area = new TicketArea(world.getUID(), anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4, chunkRadius);

        synchronized (ticketLock) {
            TicketArea existing = ticketAreas.get(playerId);
            if (area.equals(existing)) return;
            if (existing != null) {
                releaseAreaLocked(existing);
            }

            for (int dx = -area.radius(); dx <= area.radius(); dx++) {
                for (int dz = -area.radius(); dz <= area.radius(); dz++) {
                    ChunkPos pos = new ChunkPos(area.world(), area.centerX() + dx, area.centerZ() + dz);
                    if (ticketRefs.merge(pos, 1, Integer::sum) == 1) {
                        world.addPluginChunkTicket(pos.x(), pos.z(), this);
                    }
                }
            }
            ticketAreas.put(playerId, area);
        }
    }

    private void releaseTicketArea(UUID playerId) {
        synchronized (ticketLock) {
            TicketArea area = ticketAreas.remove(playerId);
            if (area != null) {
                releaseAreaLocked(area);
            }
        }
    }

    private void releaseAllTicketAreas() {
        synchronized (ticketLock) {
            for (UUID playerId : new ArrayList<>(ticketAreas.keySet())) {
                try {
                    releaseTicketArea(playerId);
                } catch (RuntimeException e) {
                    getLogger().warning("Could not release chunk tickets for " + playerId + ": " + e.getMessage());
                }
            }
        }
    }

    // Caller must hold ticketLock.
    private void releaseAreaLocked(TicketArea area) {
        World world = Bukkit.getWorld(area.world());
        for (int dx = -area.radius(); dx <= area.radius(); dx++) {
            for (int dz = -area.radius(); dz <= area.radius(); dz++) {
                ChunkPos pos = new ChunkPos(area.world(), area.centerX() + dx, area.centerZ() + dz);
                Integer count = ticketRefs.get(pos);
                if (count == null) continue;
                if (count <= 1) {
                    ticketRefs.remove(pos);
                    if (world != null) {
                        world.removePluginChunkTicket(pos.x(), pos.z(), this);
                    }
                } else {
                    ticketRefs.put(pos, count - 1);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence (survives server restarts)
    // ------------------------------------------------------------------

    private void loadDataFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "stasis.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Could not create stasis.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveDataFile() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save stasis.yml: " + e.getMessage());
        }
    }

    private void persistStasisRecord(UUID playerId, Location anchorLoc) {
        String path = playerId.toString();
        dataConfig.set(path + ".world", anchorLoc.getWorld().getName());
        dataConfig.set(path + ".x", anchorLoc.getX());
        dataConfig.set(path + ".y", anchorLoc.getY());
        dataConfig.set(path + ".z", anchorLoc.getZ());
        saveDataFile();
    }

    private void removeStasisRecord(UUID playerId) {
        dataConfig.set(playerId.toString(), null);
        saveDataFile();
    }

    /**
     * Called on startup. For every stasis record we have saved to disk, request the
     * chunk it lives in to load. If the world containing it isn't loaded yet, we skip
     * it here and pick it back up in onWorldLoad once that world comes online.
     */
    private void reattachAllPersistedStasis() {
        for (String key : new HashSet<>(dataConfig.getKeys(false))) {
            reattachRecord(key);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        String worldName = event.getWorld().getName();
        for (String key : new HashSet<>(dataConfig.getKeys(false))) {
            if (worldName.equals(dataConfig.getString(key + ".world"))) {
                reattachRecord(key);
            }
        }
    }

    private void reattachRecord(String playerIdStr) {
        UUID playerId;
        try {
            playerId = UUID.fromString(playerIdStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        if (activeStasis.containsKey(playerId)) {
            return; // already tracking this one
        }

        String worldName = dataConfig.getString(playerIdStr + ".world");
        if (worldName == null) return;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return; // world not loaded yet - onWorldLoad will retry this later
        }

        double x = dataConfig.getDouble(playerIdStr + ".x");
        double y = dataConfig.getDouble(playerIdStr + ".y");
        double z = dataConfig.getDouble(playerIdStr + ".z");
        Location anchorLoc = new Location(world, x, y, z);

        // Re-establish the chunk tickets for the whole area right away (they live in memory
        // only, so they're gone after every restart).
        acquireTicketArea(playerId, anchorLoc);

        // Request the chunk to load. If the dummy entity survived (it's marked persistent),
        // ChunkLoadEvent below will find it and resume monitoring automatically. As a
        // fallback - in case the entity didn't survive for whatever reason - we check a
        // few ticks later and respawn it from the saved coordinates if still missing.
        world.getChunkAtAsync(anchorLoc.getBlockX() >> 4, anchorLoc.getBlockZ() >> 4, true).thenAccept(ignored ->
                Bukkit.getRegionScheduler().runDelayed(this, anchorLoc, (task) -> {
                    if (activeStasis.containsKey(playerId)) {
                        return; // ChunkLoadEvent already reattached it
                    }
                    getLogger().info("Stasis entity for " + playerId + " wasn't found on disk, respawning from saved coordinates.");
                    Bukkit.getRegionScheduler().execute(this, anchorLoc, () -> {
                        Item dummy = world.dropItem(anchorLoc, new ItemStack(Material.ENDER_PEARL));
                        configureDummy(dummy, playerId);
                        activeStasis.put(playerId, dummy);
                        monitorStasisTrigger(playerId, dummy, anchorLoc);
                        pinDummyInPlace(dummy, anchorLoc);
                    });
                }, 5L)
        );
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof Item dummy)) continue;
            if (!dummy.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) continue;

            String ownerStr = dummy.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerStr == null) continue;

            UUID ownerId;
            try {
                ownerId = UUID.fromString(ownerStr);
            } catch (IllegalArgumentException e) {
                continue;
            }

            if (activeStasis.containsKey(ownerId)) continue; // already being monitored

            activeStasis.put(ownerId, dummy);

            // Prefer the saved anchor coordinates over the entity's current position -
            // if it had drifted from bubble column push before a restart, the entity's
            // own location would just re-lock it at the wrong (drifted) spot.
            Location anchorLoc = readPersistedAnchor(ownerId);
            if (anchorLoc == null) {
                anchorLoc = dummy.getLocation();
            }

            acquireTicketArea(ownerId, anchorLoc);
            monitorStasisTrigger(ownerId, dummy, anchorLoc);
            pinDummyInPlace(dummy, anchorLoc);
        }
    }

    private Location readPersistedAnchor(UUID playerId) {
        String path = playerId.toString();
        String worldName = dataConfig.getString(path + ".world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        double x = dataConfig.getDouble(path + ".x");
        double y = dataConfig.getDouble(path + ".y");
        double z = dataConfig.getDouble(path + ".z");
        return new Location(world, x, y, z);
    }

    /**
     * Bubble columns re-apply upward velocity to any entity sitting in them every tick -
     * including our own "frozen" item, which would otherwise slowly (or not so slowly)
     * drift up out of the chamber and out of click range. Continuously re-pin it instead
     * of only zeroing its velocity once at creation.
     */
    private void pinDummyInPlace(Item dummy, Location anchorLoc) {
        Bukkit.getRegionScheduler().runAtFixedRate(this, anchorLoc, (task) -> {
            if (!dummy.isValid()) {
                task.cancel();
                return;
            }
            dummy.setVelocity(new Vector(0, 0, 0));
            if (dummy.getLocation().distanceSquared(anchorLoc) > 0.0001) {
                dummy.teleportAsync(anchorLoc);
            }
        }, 1L, 1L);
    }

    // ------------------------------------------------------------------
    // Pearl tracking: let the bubble column (or, in the Nether, a honey-block shaft)
    // carry it up naturally, and lock it in once it's genuinely done rising - the top
    // of its arc.
    // ------------------------------------------------------------------

    // How many consecutive ticks of confirmed descent we require before committing to
    // a peak. Bubble columns re-apply their upward push in discrete pulses rather than
    // continuously, so there are often one or two ticks of natural deceleration (or even
    // a tiny dip) between pulses while the pearl is still well inside the water. Locking
    // on the very first such tick is what causes the dummy to end up frozen a block (or
    // more) below the water's actual top. Requiring a short run of sustained descent
    // filters that noise out without meaningfully delaying real detection.
    private static final int REQUIRED_FALL_TICKS = 3;

    @EventHandler
    public void onPearlThrow(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player)) return;

        // Only arm once the pearl has actually been held by a recognized stasis medium for
        // a few consecutive ticks - that is what tells us "this is a stasis chamber
        // throw", not just an ordinary pearl that splashed into a lake or grazed a block.
        // See requiredMediumTicks() for what counts:
        //  - an UPWARD bubble column (soul sand) - the Overworld-style chamber. Plain
        //    water doesn't count: a pearl in still water just sinks and lands.
        //  - a honey block (or a block from detection.extra-blocks) the pearl is touching,
        //    e.g. the Nether-style chamber where water evaporates.
        final AtomicBoolean armed = new AtomicBoolean(false);
        final int[] dwell = {0};
        final double[] lastY = {Double.NEGATIVE_INFINITY};
        final Location[] peak = {null};
        // Last place the pearl was inside the chamber's water/bubble column. Used to anchor
        // an UNCAPPED chamber's dummy at the water surface instead of at the top of the
        // arc, because the column launches the pearl several blocks above the water.
        final Location[] surface = {null};
        final int[] fallTicks = {0};


        pearl.getScheduler().runAtFixedRate(this, (task) -> {
            if (!pearl.isValid()) {
                if (armed.get()) decrementArmed(player.getUniqueId());
                task.cancel();
                return;
            }

            Location loc = pearl.getLocation();

            if (!armed.get()) {
                // Count CONSECUTIVE ticks in a medium; leaving it resets the count, so a
                // pearl that only clips a medium on its way past is never armed (and
                // vanilla's normal teleport-on-hit is left completely alone for it).
                int required = requiredMediumTicks(loc.getBlock());
                if (required < 0) {
                    dwell[0] = 0;
                    return;
                }
                dwell[0]++;
                if (dwell[0] >= required) {
                    armed.set(true);
                    incrementArmed(player.getUniqueId());
                    lastY[0] = loc.getY();
                    peak[0] = loc.clone();
                    if (isFluidColumn(loc.getBlock())) surface[0] = loc.clone();
                }
                return;
            }

            // Most reliable signal: it's physically pinned against a solid ceiling
            // (closed trapdoor, solid block, etc.) and literally cannot rise any further.
            // Check this BEFORE the Y-decrease check, since a solid cap can cause a tiny
            // bounce-back the very tick it lands, which would otherwise get misread as
            // "started falling" one block early.
            if (loc.getBlock().getRelative(0, 1, 0).getType().isSolid()) {
                createStasisDummy(player, pearl, loc);
                task.cancel();
                return;
            }

            // While still genuinely inside water/a bubble column, NEVER treat a per-tick
            // dip as descent - it's just the gap between bubble-column push pulses, not
            // the pearl actually turning around. This is what stops the "locks a block
            // below the real top" bug.
            boolean stillSubmerged = isFluidColumn(loc.getBlock());
            if (stillSubmerged) surface[0] = loc.clone();

            // Small tolerance so tiny per-tick jitter isn't mistaken for descent.
            if (stillSubmerged || loc.getY() >= lastY[0] - 0.02) {
                // Still rising (or holding steady) - remember this as the highest point so far.
                if (loc.getY() > lastY[0]) {
                    lastY[0] = loc.getY();
                    peak[0] = loc.clone();
                }
                fallTicks[0] = 0;
                return;
            }

            // It looks like it's started falling - but don't commit off a single tick.
            // Require a short run of sustained descent first (see REQUIRED_FALL_TICKS).
            fallTicks[0]++;
            if (fallTicks[0] < REQUIRED_FALL_TICKS) {
                return;
            }

            // It's now clearly and consistently falling, so it has been launched out of the
            // column and is on its way back down. If it came out of water, park the dummy
            // at the top of that water (between water and air) rather than at the peak of
            // the arc, which the bubbles' acceleration puts well above the surface. With
            // no water involved (e.g. a honey chamber) the peak is still what we use.
            Location lockAt = surface[0] != null ? topOfFluidColumn(surface[0]) : peak[0];
            createStasisDummy(player, pearl, lockAt);
            task.cancel();
        }, null, 1L, 1L);
    }

    private void incrementArmed(UUID playerId) {
        armedPearlCounts.merge(playerId, 1, Integer::sum);
    }

    private void decrementArmed(UUID playerId) {
        armedPearlCounts.computeIfPresent(playerId, (id, count) -> count <= 1 ? null : count - 1);
    }

    /**
     * The moment a pearl we're tracking physically touches a solid surface (e.g. clipping
     * the side of the chamber shaft, or the same ceiling our own solid-cap check is about
     * to catch on this very tick), vanilla fires its own hit handling and teleports the
     * player immediately - independently of, and often a tick ahead of, our own detection
     * logic actually locking the dummy in. Without this, both paths run: the player gets
     * yanked there instantly by vanilla, AND our code separately finishes creating a
     * frozen "leftover" dummy at the same spot a moment later - the duplicate the player
     * sees. Suppressing the teleport itself (not the entity's removal/hit handling, which
     * isn't reliably cancellable) leaves our own tracked-pearl logic as the single source
     * of truth for when this pearl's teleport actually happens.
     */
    @EventHandler
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        if (armedPearlCounts.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * True for water and bubble-column blocks. Only used AFTER a pearl is already armed
     * (i.e. confirmed to be in a real chamber), to tell "still in the chamber's water"
     * from "has left it" - it is deliberately NOT used to decide whether to arm, since
     * plain water is not a stasis medium.
     */
    private boolean isFluidColumn(Block block) {
        Material type = block.getType();
        return type == Material.BUBBLE_COLUMN || type == Material.WATER;
    }

    /**
     * Walks up from a point inside a water/bubble column to the topmost water block of
     * that column - the one whose surface is the boundary between water and air.
     */
    private Location topOfFluidColumn(Location from) {
        Block block = from.getBlock();
        for (int i = 0; i < 64 && isFluidColumn(block.getRelative(BlockFace.UP)); i++) {
            block = block.getRelative(BlockFace.UP);
        }
        return block.getLocation();
    }

    /**
     * If the pearl at this block is being held by a stasis medium, returns how many
     * consecutive ticks that must last before we treat it as a stasis; otherwise -1.
     */
    private int requiredMediumTicks(Block block) {
        int required = Integer.MAX_VALUE;
        if (detectBubbleColumns && isUpwardBubbleColumn(block)) {
            required = minTicksBubbleColumn;
        }
        if (!touchMediums.isEmpty() && isTouchingMedium(block)) {
            required = Math.min(required, minTicksTouchMedium);
        }
        return required == Integer.MAX_VALUE ? -1 : required;
    }

    /**
     * True only for an UPWARD bubble column (soul sand underneath), which is what holds a
     * pearl up. Downward columns (magma) push pearls into the floor, and plain water does
     * nothing, so neither is a stasis.
     */
    private boolean isUpwardBubbleColumn(Block block) {
        if (block.getType() != Material.BUBBLE_COLUMN) return false;
        return !(block.getBlockData() instanceof BubbleColumn column) || !column.isDrag();
    }

    /**
     * True if the given block, or one of its horizontal neighbors, is one of the touch
     * mediums (honey by default). Honey doesn't need to be entered/passed through to
     * affect an entity - sliding down alongside its face is what applies the heavy
     * drag - so neighbors count too.
     */
    private boolean isTouchingMedium(Block block) {
        if (touchMediums.contains(block.getType())) return true;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (touchMediums.contains(block.getRelative(face).getType())) return true;
        }
        return false;
    }

    private void createStasisDummy(Player player, EnderPearl realPearl, Location peakLoc) {
        // The anchor is used for game-logic (collision antenna math, persistence) and
        // doubles as the exact spawn point for the visible item.
        Location anchorLoc = peakLoc.clone();
        anchorLoc.setX(anchorLoc.getBlockX() + 0.5);
        anchorLoc.setY(anchorLoc.getBlockY() + dummyHeight);
        anchorLoc.setZ(anchorLoc.getBlockZ() + 0.5);

        UUID playerId = player.getUniqueId();

        // Keep the stasis chunk (and chunk-radius chunks around it) loaded. This uses
        // World#addPluginChunkTicket by coordinates, so it doesn't force a synchronous
        // chunk load on this thread the way Location#getChunk() would.
        acquireTicketArea(playerId, anchorLoc);

        Bukkit.getRegionScheduler().execute(this, anchorLoc, () -> {
            Item dummy = anchorLoc.getWorld().dropItem(anchorLoc, new ItemStack(Material.ENDER_PEARL));
            configureDummy(dummy, playerId);

            activeStasis.put(playerId, dummy);
            persistStasisRecord(playerId, anchorLoc);
            realPearl.remove();
            // Only NOW is it safe to stop suppressing vanilla's teleport-on-hit for this
            // player - the real pearl that could still trigger it is actually gone. This
            // execute() call runs on a later tick than the code that decided to call
            // createStasisDummy() in the first place, so decrementing any earlier would
            // leave a window where the (still-alive) real pearl sitting right against the
            // trigger block could slip a vanilla teleport past our own suppression - the
            // exact cause of the duplicate-pearl bug.
            decrementArmed(playerId);

            monitorStasisTrigger(playerId, dummy, anchorLoc);
            pinDummyInPlace(dummy, anchorLoc);
        });
    }

    private void configureDummy(Item dummy, UUID playerId) {
        // A plain dropped-item entity renders identically on Java and Bedrock (via Geyser) -
        // unlike Display entities (unsupported on Bedrock) or items in an armor stand's
        // head slot (a confirmed, permanent Geyser limitation for non-armor items).
        dummy.setGravity(false);
        dummy.setVelocity(new Vector(0, 0, 0));
        dummy.setPickupDelay(Integer.MAX_VALUE);
        dummy.setUnlimitedLifetime(true); // don't let it despawn after the usual 5 minutes
        dummy.setInvulnerable(true);
        dummy.setSilent(true);
        // Persistent so it's saved as part of normal chunk/entity data and survives restarts.
        dummy.setPersistent(true);

        dummy.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        dummy.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, playerId.toString());
    }

    // ------------------------------------------------------------------
    // Retrieval trigger #1: touch the dummy directly.
    //
    // Note: a plain dropped item entity (unlike mobs, armor stands, or item frames) was
    // never designed to be attack/interact-targetable by the vanilla client at all - no
    // attack or interact packet gets sent for it no matter what the server does, so
    // right-click/attack handlers below won't actually fire for it in practice. The
    // reliable equivalent is proximity: walking close enough to touch it already fires a
    // pickup event before any item transfer happens, so we hijack that as the trigger
    // instead of just blocking it.
    // ------------------------------------------------------------------

    @EventHandler
    public void onDummyRightClick(PlayerInteractEntityEvent event) {
        if (isStasisDummy(event.getRightClicked())) {
            event.setCancelled(true);
            handleDummyClick(event.getRightClicked());
        }
    }

    @EventHandler
    public void onDummyAttack(EntityDamageByEntityEvent event) {
        if (isStasisDummy(event.getEntity())) {
            event.setCancelled(true); // it's invulnerable anyway, but stop knockback/particles too
            handleDummyClick(event.getEntity());
        }
    }

    @EventHandler
    public void onDummyTouch(EntityPickupItemEvent event) {
        if (isStasisDummy(event.getItem())) {
            event.setCancelled(true); // never actually let it be picked up
            handleDummyClick(event.getItem());
        }
    }

    private boolean isStasisDummy(Entity entity) {
        return entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private void handleDummyClick(Entity dummy) {
        String ownerStr = dummy.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerStr == null) return;

        UUID ownerId;
        try {
            ownerId = UUID.fromString(ownerStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        Location destination = dummy.getLocation();

        triggerTeleport(ownerId, destination);
        dummy.remove();
        cleanUp(ownerId);
    }

    // ------------------------------------------------------------------
    // Retrieval trigger #2: watch for a NEW solid block appearing around the dummy
    // (e.g. someone toggling a trapdoor via redstone) to know when to teleport back.
    // ------------------------------------------------------------------

    private void monitorStasisTrigger(UUID playerId, Entity dummy, Location anchorLoc) {
        Block center = anchorLoc.getBlock();

        // Stretch the hitbox slightly upward (Y + 0.8) to act as a collision "antenna"
        // This guarantees it catches trapdoors closing in the block directly above it
        BoundingBox pearlBox = new BoundingBox(
                anchorLoc.getX() - 0.2,
                anchorLoc.getY() - 0.2,
                anchorLoc.getZ() - 0.2,
                anchorLoc.getX() + 0.2,
                anchorLoc.getY() + 0.8,
                anchorLoc.getZ() + 0.2
        );

        // Rolling edge-detection: track which relative positions are solid on THIS poll
        // vs. the PREVIOUS poll, and only trigger when a position transitions from
        // non-solid to solid. This correctly ignores the chamber's own cap (e.g. a
        // trapdoor that's already closed when monitoring starts, holding the pearl in
        // place) without permanently blacklisting that position - so the player can
        // still open and re-close that very same trapdoor later to trigger retrieval.
        final Set<Long> previousSolid = new HashSet<>();
        final boolean[] initialized = {false};

        Bukkit.getRegionScheduler().runAtFixedRate(this, anchorLoc, (task) -> {
            if (!dummy.isValid()) {
                cleanUp(playerId);
                task.cancel();
                return;
            }

            Set<Long> currentSolid = new HashSet<>();
            boolean newCollisionDetected = false;

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Block b = center.getRelative(x, y, z);
                        long key = offsetKey(x, y, z);

                        boolean solidNow = b.getType().isSolid() && b.getBoundingBox().overlaps(pearlBox);
                        if (solidNow) {
                            currentSolid.add(key);
                            if (initialized[0] && !previousSolid.contains(key)) {
                                newCollisionDetected = true;
                            }
                        }
                    }
                }
            }

            previousSolid.clear();
            previousSolid.addAll(currentSolid);
            initialized[0] = true;

            if (newCollisionDetected) {
                triggerTeleport(playerId, anchorLoc);
                dummy.remove();
                cleanUp(playerId);
                task.cancel();
            }
        }, 10L, 2L);
    }

    private long offsetKey(int x, int y, int z) {
        return ((long) (x + 1) << 8) | ((long) (y + 1) << 4) | (long) (z + 1);
    }

    private void triggerTeleport(UUID playerId, Location destination) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.teleportAsync(destination).thenAccept(success -> {
                if (!success) {
                    player.sendMessage("§cThere was an internal unhandled error while teleporting you.");
                }
            });
        }
    }

    private void cleanUp(UUID playerId) {
        activeStasis.remove(playerId);
        releaseTicketArea(playerId);
        removeStasisRecord(playerId);
    }
}
