package net.blockledger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.function.Consumer;

/**
 * Applies a list of entries to the world a few blocks per tick, so a large rollback does not freeze
 * the server.
 *
 * <p>For a rollback the entries are applied newest first, so the oldest recorded state wins. For a
 * restore they are applied oldest first, so the newest recorded state wins.
 */
public final class BlockChanger implements Runnable {
    private final BlockLedgerPlugin plugin;
    private final List<LedgerEntry> entries;
    private final boolean rollback;
    private final Consumer<Result> onFinish;

    private int index;
    private int changed;
    private int skipped;
    private final long startedAt = System.currentTimeMillis();
    private BukkitTask task;

    public BlockChanger(BlockLedgerPlugin plugin, List<LedgerEntry> entries, boolean rollback,
                        Consumer<Result> onFinish) {
        this.plugin = plugin;
        this.entries = entries;
        this.rollback = rollback;
        this.onFinish = onFinish;
    }

    public void start() {
        plugin.setChangingBlocks(true);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    @Override
    public void run() {
        int budget = plugin.settings().blocksPerTick();
        int processed = 0;
        while (index < entries.size() && processed < budget) {
            LedgerEntry entry = entries.get(index++);
            processed++;
            if (!entry.changesBlock()) {
                skipped++;
                continue;
            }
            World world = Bukkit.getWorld(entry.world());
            if (world == null) {
                skipped++;
                continue;
            }
            Block block = world.getBlockAt(entry.x(), entry.y(), entry.z());
            String desired = entry.targetFor(rollback);
            // Comparing the compact block data strings skips blocks that already hold the right state.
            if (BlockStates.serialize(block).equals(desired)) {
                skipped++;
                continue;
            }
            block.setBlockData(BlockStates.parse(desired), false);
            changed++;
        }

        if (index >= entries.size()) {
            finish();
        }
    }

    private void finish() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        plugin.setChangingBlocks(false);
        if (onFinish != null) {
            onFinish.accept(new Result(changed, skipped, System.currentTimeMillis() - startedAt));
        }
    }

    public record Result(int changed, int skipped, long millis) {
    }
}
