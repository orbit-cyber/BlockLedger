package net.blockledger;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Block logging with inspect, rollback and restore.
 */
public final class BlockLedgerPlugin extends JavaPlugin {
    private Settings settings;
    private Database database;
    private BukkitTask flushTask;
    private BukkitTask purgeTask;

    private final Set<UUID> inspecting = ConcurrentHashMap.newKeySet();
    private volatile boolean changingBlocks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = Settings.load(getConfig());

        File folder = getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            getLogger().severe("Could not create " + folder.getAbsolutePath() + " - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.database = new Database(this, new File(folder, settings.databaseFile()));
        if (!database.open()) {
            getLogger().severe("The database could not be opened - disabling BlockLedger.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new BlockListeners(this), this);
        getServer().getPluginManager().registerEvents(new InspectListener(this), this);

        PluginCommand command = getCommand("blockledger");
        if (command == null) {
            getLogger().severe("The /blockledger command is missing from plugin.yml - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        BlockLedgerCommand executor = new BlockLedgerCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        startTasks();

        getLogger().info("BlockLedger " + getPluginMeta().getVersion() + " enabled. Recording to "
                + settings.databaseFile() + " (flush every " + settings.flushIntervalTicks() + " ticks).");
    }

    @Override
    public void onDisable() {
        stopTasks();
        if (database != null) {
            database.close();
            database = null;
        }
        inspecting.clear();
        getLogger().info("BlockLedger disabled.");
    }

    private void startTasks() {
        int interval = settings.flushIntervalTicks();
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                database.flush();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Could not flush the write queue", t);
            }
        }, interval, interval);

        if (settings.purgeEnabled()) {
            long sixHours = 20L * 60L * 60L * 6L;
            purgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                int removed = database.purgeOlderThan(settings.purgeDays());
                if (removed > 0) {
                    getLogger().info("Purged " + removed + " records older than " + settings.purgeDays() + " days.");
                }
            }, sixHours, sixHours);
        }
    }

    private void stopTasks() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (purgeTask != null) {
            purgeTask.cancel();
            purgeTask = null;
        }
    }

    /** Re-reads config.yml. The database stays open, so nothing recorded is lost. */
    public void reload() {
        stopTasks();
        reloadConfig();
        this.settings = Settings.load(getConfig());
        startTasks();
    }

    public Settings settings() {
        return settings;
    }

    public Database database() {
        return database;
    }

    public Set<UUID> inspecting() {
        return inspecting;
    }

    /** True while a rollback or restore is writing blocks, so those writes are not logged again. */
    public boolean isChangingBlocks() {
        return changingBlocks;
    }

    public void setChangingBlocks(boolean changingBlocks) {
        this.changingBlocks = changingBlocks;
    }

    /** True when this world must not be recorded. */
    public boolean ignoresWorld(String worldName) {
        return settings.isIgnoredWorld(worldName);
    }

    /** True when this block type must not be recorded. */
    public boolean ignoresBlock(org.bukkit.Material type) {
        return type != null && settings.isIgnoredBlock(type.name());
    }

    /** Queues an entry, unless the player is allowed to bypass logging. */
    public void record(LedgerEntry entry) {
        if (entry == null) {
            return;
        }
        if (database != null) {
            database.queue(entry);
        }
    }
}
