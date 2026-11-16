package net.blockledger;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;

/**
 * Click-to-inspect: while a player has inspect mode on, hitting or right-clicking a block shows who
 * changed it instead of using it.
 */
public final class InspectListener implements Listener {
    private static final int SHOWN = 10;

    private final BlockLedgerPlugin plugin;

    public InspectListener(BlockLedgerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // The event fires once per hand; only answer the main hand.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.inspecting().contains(player.getUniqueId())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // Inspecting must not open the chest or place the block being held.
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);
        show(player, block);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.inspecting().remove(event.getPlayer().getUniqueId());
    }

    private void show(Player player, Block block) {
        Query query = new Query();
        query.world = block.getWorld().getName();
        query.x = block.getX();
        query.y = block.getY();
        query.z = block.getZ();
        query.limit = SHOWN;
        query.ascending = false;

        player.sendMessage(Text.info("History of " + Text.blockName(BlockStates.serialize(block))
                + " at " + block.getX() + ", " + block.getY() + ", " + block.getZ() + ":"));

        plugin.database().queryAsync(query).thenAccept(entries ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (entries.isEmpty()) {
                        player.sendMessage(Text.warn("No recorded changes here."));
                        return;
                    }
                    List<LedgerEntry> newestFirst = entries;
                    for (LedgerEntry entry : newestFirst) {
                        player.sendMessage(BlockLedgerCommand.describe(entry));
                    }
                    if (newestFirst.size() >= SHOWN) {
                        player.sendMessage(Text.info("Showing the " + SHOWN + " newest changes."));
                    }
                }));
    }
}
