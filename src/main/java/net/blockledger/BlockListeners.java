package net.blockledger;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Turns Bukkit events into ledger entries. Everything here runs on the main thread and only ever
 * queues; the database write happens later on an async task.
 */
public final class BlockListeners implements Listener {
    private final BlockLedgerPlugin plugin;

    public BlockListeners(BlockLedgerPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean skip(Block block) {
        return plugin.isChangingBlocks()
                || block == null
                || block.getWorld() == null
                || plugin.ignoresWorld(block.getWorld().getName())
                || plugin.ignoresBlock(block.getType());
    }

    private void logBlock(ActionType action, Block block, String actor, String before, String after) {
        plugin.record(LedgerEntry.blockChange(action, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), actor, before, after));
    }

    private static String air() {
        return "minecraft:air";
    }

    private static String describe(Player player) {
        return player == null ? null : player.getName();
    }

    private static boolean bypasses(Player player) {
        return player != null && player.hasPermission("blockledger.bypass");
    }

    // ------------------------------------------------------------------ players

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.settings().logBreak() || skip(event.getBlock()) || bypasses(event.getPlayer())) {
            return;
        }
        logBlock(ActionType.BREAK, event.getBlock(), describe(event.getPlayer()),
                BlockStates.serialize(event.getBlock()), air());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.settings().logPlace() || bypasses(event.getPlayer())) {
            return;
        }
        Block placed = event.getBlockPlaced();
        if (skip(placed)) {
            return;
        }
        String before = BlockStates.serialize(event.getBlockReplacedState().getBlockData());
        logBlock(ActionType.PLACE, placed, describe(event.getPlayer()), before, BlockStates.serialize(placed));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!plugin.settings().logBuckets() || bypasses(event.getPlayer())) {
            return;
        }
        Block block = event.getBlock();
        if (skip(block)) {
            return;
        }
        Material placed = switch (event.getBucket()) {
            case WATER_BUCKET -> Material.WATER;
            case LAVA_BUCKET -> Material.LAVA;
            case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW;
            default -> null;
        };
        if (placed == null) {
            return;
        }
        logBlock(ActionType.BUCKET, block, describe(event.getPlayer()),
                BlockStates.serialize(block), BlockStates.serialize(placed.createBlockData()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!plugin.settings().logBuckets() || bypasses(event.getPlayer())) {
            return;
        }
        Block block = event.getBlock();
        if (skip(block)) {
            return;
        }
        logBlock(ActionType.BUCKET, block, describe(event.getPlayer()), BlockStates.serialize(block), air());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.settings().logContainers()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || bypasses(player)) {
            return;
        }
        // Only block containers have a location; the player's own inventory returns null.
        org.bukkit.Location location = event.getInventory().getLocation();
        if (location == null || location.getWorld() == null
                || plugin.ignoresWorld(location.getWorld().getName())) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) {
            return;
        }
        String where = event.getClickedInventory() == event.getInventory() ? "container" : "player inventory";
        String extra = "%s x%d, slot %d of the %s (%s)".formatted(
                item.getType().name().toLowerCase().replace('_', ' '),
                item.getAmount(),
                event.getSlot(),
                where,
                event.getClick());
        plugin.record(LedgerEntry.event(ActionType.CONTAINER, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), player.getName(), extra));
    }

    // ------------------------------------------------------------------ world

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.settings().logExplosions()) {
            return;
        }
        String actor = event.getEntityType().name().toLowerCase().replace('_', ' ');
        for (Block block : event.blockList()) {
            if (skip(block)) {
                continue;
            }
            logBlock(ActionType.EXPLOSION, block, actor, BlockStates.serialize(block), air());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.settings().logExplosions()) {
            return;
        }
        for (Block block : event.blockList()) {
            if (skip(block)) {
                continue;
            }
            logBlock(ActionType.EXPLOSION, block, "explosion", BlockStates.serialize(block), air());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (!plugin.settings().logFire() || skip(event.getBlock())) {
            return;
        }
        logBlock(ActionType.FIRE, event.getBlock(), "fire", BlockStates.serialize(event.getBlock()), air());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (!plugin.settings().logFire() || skip(event.getBlock())) {
            return;
        }
        String actor = event.getPlayer() != null
                ? event.getPlayer().getName()
                : event.getCause().name().toLowerCase().replace('_', ' ');
        logBlock(ActionType.FIRE, event.getBlock(), actor, air(), BlockStates.serialize(Material.FIRE.createBlockData()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!plugin.settings().logPistons()) {
            return;
        }
        for (Block block : event.getBlocks()) {
            move(block, block.getRelative(event.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!plugin.settings().logPistons()) {
            return;
        }
        // A retracting piston pulls blocks towards itself, the opposite way from its facing.
        for (Block block : event.getBlocks()) {
            move(block, block.getRelative(event.getDirection().getOppositeFace()));
        }
    }

    /** Records a piston move as "gone from here" plus "arrived there". */
    private void move(Block from, Block to) {
        if (skip(from)) {
            return;
        }
        String state = BlockStates.serialize(from);
        logBlock(ActionType.PISTON, from, "piston", state, air());
        if (to.getWorld() != null && !plugin.ignoresWorld(to.getWorld().getName())) {
            logBlock(ActionType.PISTON, to, "piston", air(), state);
        }
    }

    // ------------------------------------------------------------------ natural

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (!plugin.settings().logNatural() || skip(event.getBlock())) {
            return;
        }
        BlockState state = event.getNewState();
        logBlock(ActionType.NATURAL, event.getBlock(), "natural",
                BlockStates.serialize(event.getBlock()), BlockStates.serialize(state.getBlockData()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (!plugin.settings().logNatural() || skip(event.getBlock())) {
            return;
        }
        logBlock(ActionType.NATURAL, event.getBlock(), "natural", BlockStates.serialize(event.getBlock()), air());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent event) {
        if (!plugin.settings().logNatural()) {
            return;
        }
        Block to = event.getToBlock();
        if (skip(to)) {
            return;
        }
        // The destination still holds the old block at this point; what fills it is the source's type.
        String after = BlockStates.serialize(event.getBlock().getType().createBlockData());
        logBlock(ActionType.NATURAL, to, "natural", BlockStates.serialize(to), after);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!plugin.settings().logNatural()) {
            return;
        }
        Block block = event.getBlock();
        if (skip(block)) {
            return;
        }
        String actor = event.getEntityType().name().toLowerCase().replace('_', ' ');
        logBlock(ActionType.NATURAL, block, actor,
                BlockStates.serialize(block), BlockStates.serialize(event.getBlockData()));
    }

    // ------------------------------------------------------------------ entities

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!plugin.settings().logEntities()) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || bypasses(killer)) {
            return;
        }
        org.bukkit.Location location = event.getEntity().getLocation();
        if (location.getWorld() == null || plugin.ignoresWorld(location.getWorld().getName())) {
            return;
        }
        String extra = event.getEntityType().name().toLowerCase().replace('_', ' ');
        plugin.record(LedgerEntry.event(ActionType.KILL, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), killer.getName(), extra));
    }
}
