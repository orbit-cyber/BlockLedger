package net.blockledger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
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
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;

/**
 * Turns Bukkit events into ledger entries. Everything here runs on the main thread and only ever
 * queues; the database write happens later on an async task.
 *
 * <p>Covered: block breaks and places, bucket use, containers and furnaces, explosions, fire,
 * pistons, natural changes, entity kills, player deaths, drops and pickups, crafting and smithing,
 * eating and drinking, fishing, items breaking, entity and armor stand interactions, placing
 * boats/minecarts/armor stands, sign and book edits, and commands.
 */
public final class BlockListeners implements Listener {
    private final BlockLedgerPlugin plugin;

    public BlockListeners(BlockLedgerPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ helpers

    private boolean skip(Block block) {
        return plugin.isOwnWrite()
                || block == null
                || block.getWorld() == null
                || plugin.ignoresWorld(block.getWorld().getName())
                || plugin.ignoresBlock(block.getType());
    }

    /** The items in a container block, or null when it is not a container or holds nothing. */
    private static String contentsOf(Block block) {
        ItemStack[] items = containerItems(block);
        return items == null ? null : BlockStates.serializeItems(items);
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

    /** Records something a player did at a location, without changing a block. */
    private void recordAt(Player player, Location location, ActionType action, String extra) {
        if (player == null || bypasses(player) || location == null) {
            return;
        }
        World world = location.getWorld();
        if (world == null || plugin.ignoresWorld(world.getName())) {
            return;
        }
        plugin.record(LedgerEntry.event(action, world.getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                player.getName(), extra));
    }

    /** "stone x32" - how items are described. */
    private static String describeItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "nothing";
        }
        String name = stack.getType().name().toLowerCase().replace('_', ' ');
        return stack.getAmount() > 1 ? name + " x" + stack.getAmount() : name;
    }

    private static String entityName(Entity entity) {
        return entity == null ? "something" : entity.getType().name().toLowerCase().replace('_', ' ');
    }

    private static String blockName(Material material) {
        return material.name().toLowerCase().replace('_', ' ');
    }

    private static ItemStack heldItem(Player player, EquipmentSlot hand) {
        PlayerInventory inventory = player.getInventory();
        return hand == EquipmentSlot.OFF_HAND
                ? inventory.getItemInOffHand()
                : inventory.getItemInMainHand();
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.settings().logBreak() || skip(event.getBlock()) || bypasses(event.getPlayer())) {
            return;
        }
        Block block = event.getBlock();
        LedgerEntry entry = LedgerEntry.blockChange(ActionType.BREAK, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), describe(event.getPlayer()),
                BlockStates.serialize(block), air());
        // A broken chest keeps its items only if they are read now, before the block is gone.
        if (plugin.settings().logContainerContents()) {
            entry = entry.withContents(contentsOf(block));
        }
        plugin.record(entry);
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
    public void onSignChange(SignChangeEvent event) {
        if (!plugin.settings().logInteractions()) {
            return;
        }
        List<Component> lines = event.lines();
        StringBuilder text = new StringBuilder();
        for (Component line : lines) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line).trim();
            if (plain.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(plain);
        }
        recordAt(event.getPlayer(), event.getBlock().getLocation(), ActionType.SIGN,
                text.length() == 0 ? "an erased sign" : "a sign saying \"" + text + "\"");
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

    // ------------------------------------------------------------------ containers

    /**
     * Chest logger: records what a container click actually moved, not just that one happened.
     *
     * <p>The event fires before the click is applied, so the inventory is read now and again one tick
     * later, and the difference is what the player took or put. That also covers shift-clicks and
     * drags, which touch many slots at once.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.settings().logContainers()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || bypasses(player)) {
            return;
        }
        // Only block containers have a location; the player's own inventory returns null.
        Location location = event.getInventory().getLocation();
        if (location == null || location.getWorld() == null
                || plugin.ignoresWorld(location.getWorld().getName())) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        String kind = event.getInventory().getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        Block container = location.getBlock();
        String world = location.getWorld().getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        boolean keepContents = plugin.settings().logContainerContents();

        ItemStack[] before = ContainerItems.copy(event.getInventory().getContents());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!container.getChunk().isLoaded()) {
                return;
            }
            ItemStack[] after = containerItems(container);
            List<String> moves = after == null
                    ? List.of()
                    : ContainerItems.delta(before, after);
            String extra;
            if (moves.isEmpty()) {
                // Nothing could be compared - the block is gone, say - so fall back to the click itself.
                if (clicked == null || clicked.getType().isAir()) {
                    return;
                }
                extra = "%s in slot %d of a %s (%s)".formatted(
                        describeItem(clicked), event.getSlot(), kind, event.getClick());
            } else {
                extra = String.join(", ", moves) + " in a " + kind;
            }
            LedgerEntry entry = LedgerEntry.event(ActionType.CONTAINER, world, x, y, z, player.getName(), extra);
            plugin.record(keepContents && after != null
                    ? entry.withContents(BlockStates.serializeItems(after))
                    : entry);
        });
    }

    /** The items inside a container block, or null when the block is no longer a container. */
    private static ItemStack[] containerItems(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            return null;
        }
        return ContainerItems.copy(container.getInventory().getContents());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        if (!plugin.settings().logContainers() || bypasses(event.getPlayer())) {
            return;
        }
        Block block = event.getBlock();
        if (skip(block)) {
            return;
        }
        String extra = "took " + event.getItemAmount() + " x " + blockName(event.getItemType())
                + " out of a " + blockName(block.getType());
        plugin.record(LedgerEntry.event(ActionType.CONTAINER, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), event.getPlayer().getName(), extra));
    }

    // ------------------------------------------------------------------ items

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.settings().logItems()) {
            return;
        }
        recordAt(event.getPlayer(), event.getPlayer().getLocation(), ActionType.DROP,
                describeItem(event.getItemDrop().getItemStack()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.settings().logItems() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        recordAt(player, player.getLocation(), ActionType.PICKUP,
                describeItem(event.getItem().getItemStack()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!plugin.settings().logItems() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        recordAt(player, player.getLocation(), ActionType.CRAFT,
                describeItem(event.getRecipe().getResult()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        if (!plugin.settings().logItems() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) {
            return;
        }
        recordAt(player, player.getLocation(), ActionType.CRAFT, describeItem(result));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!plugin.settings().logItems()) {
            return;
        }
        recordAt(event.getPlayer(), event.getPlayer().getLocation(), ActionType.CONSUME,
                describeItem(event.getItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!plugin.settings().logItems() || event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (event.getCaught() instanceof Item caught) {
            recordAt(event.getPlayer(), event.getPlayer().getLocation(), ActionType.USE,
                    "fished up " + describeItem(caught.getItemStack()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        if (!plugin.settings().logItems()) {
            return;
        }
        recordAt(event.getPlayer(), event.getPlayer().getLocation(), ActionType.USE,
                "broke a " + describeItem(event.getBrokenItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        if (!plugin.settings().logInteractions()) {
            return;
        }
        String title = event.getNewBookMeta().getTitle();
        recordAt(event.getPlayer(), event.getPlayer().getLocation(), ActionType.SIGN,
                title == null ? "a written book" : "the book \"" + title + "\"");
    }

    // ------------------------------------------------------------------ entities

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractAtEntityEvent event) {
        // Fires once per hand; only record the main hand to avoid duplicate entries.
        if (!plugin.settings().logInteractions() || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack hand = heldItem(event.getPlayer(), event.getHand());
        String extra = hand == null || hand.getType().isAir()
                ? "interacted with a " + entityName(event.getRightClicked())
                : "used a " + describeItem(hand) + " on a " + entityName(event.getRightClicked());
        recordAt(event.getPlayer(), event.getRightClicked().getLocation(), ActionType.USE, extra);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!plugin.settings().logInteractions() || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String extra = event.getPlayerItem().getType().isAir()
                ? "took " + describeItem(event.getArmorStandItem()) + " off an armor stand"
                : "put " + describeItem(event.getPlayerItem()) + " on an armor stand";
        recordAt(event.getPlayer(), event.getRightClicked().getLocation(), ActionType.USE, extra);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (!plugin.settings().logInteractions() || event.getPlayer() == null) {
            return;
        }
        recordAt(event.getPlayer(), event.getBlock().getLocation(), ActionType.ENTITY,
                "a " + entityName(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!plugin.settings().logEntities()) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        recordAt(killer, event.getEntity().getLocation(), ActionType.KILL, entityName(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.settings().logDeaths()) {
            return;
        }
        Player player = event.getEntity();
        Player killer = player.getKiller();
        String extra;
        if (killer != null) {
            extra = "died to " + killer.getName();
        } else if (player.getLastDamageCause() != null) {
            extra = "died to " + player.getLastDamageCause().getCause().name()
                    .toLowerCase().replace('_', ' ');
        } else {
            extra = "died";
        }
        recordAt(player, player.getLocation(), ActionType.DEATH, extra);
    }

    // ------------------------------------------------------------------ world

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.settings().logExplosions()) {
            return;
        }
        String actor = entityName(event.getEntity());
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
        logBlock(ActionType.FIRE, event.getBlock(), actor, air(),
                BlockStates.serialize(Material.FIRE.createBlockData()));
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
        logBlock(ActionType.NATURAL, block, entityName(event.getEntity()),
                BlockStates.serialize(block), BlockStates.serialize(event.getBlockData()));
    }

    // ------------------------------------------------------------------ commands

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.settings().logCommands()) {
            return;
        }
        recordAt(event.getPlayer(), event.getPlayer().getLocation(), ActionType.COMMAND,
                event.getMessage());
    }
}
