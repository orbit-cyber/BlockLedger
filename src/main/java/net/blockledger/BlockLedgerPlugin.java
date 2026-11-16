package net.blockledger;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.List;
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

    /**
     * True only while this plugin is writing a block itself, on the thread doing the writing.
     *
     * <p>A thread local rather than a plain flag on purpose: a rollback takes many ticks, and a flag
     * that stays set for its whole run would silently drop every break and place a player makes
     * meanwhile. Setting it around the single {@code setBlockData} call suppresses exactly the writes
     * the rollback causes and nothing else.
     */
    private final ThreadLocal<Boolean> ownWrite = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The last rollback or restore, so /blockledger undo can put it back. */
    private volatile LastChange lastChange;

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

        // Top-level shortcuts: /inspect, /restore and /rollback, declared in plugin.yml.
        registerShortcut(executor, "inspect");
        registerShortcut(executor, "restore");
        registerShortcut(executor, "rollback");

        startTasks();

        // Published last, once the database is open and the commands are wired: another plugin
        // asking for it a moment after startup must not get a half-built ledger.
        LedgerApi.install(new LedgerApi(this));

        getLogger().info("BlockLedger " + getPluginMeta().getVersion() + " enabled. Recording to "
                + settings.databaseFile() + " (flush every " + settings.flushIntervalTicks() + " ticks).");
    }

    @Override
    public void onDisable() {
        // Cleared first, so a panel call during shutdown gets "not installed" instead of reaching
        // into a database that is about to close.
        LedgerApi.uninstall();
        stopTasks();
        if (database != null) {
            database.close();
            database = null;
        }
        inspecting.clear();
        getLogger().info("BlockLedger disabled.");
    }

    /** Wires a plugin.yml command such as /restore to one /blockledger subcommand. */
    private void registerShortcut(BlockLedgerCommand delegate, String name) {
        PluginCommand shortcut = getCommand(name);
        if (shortcut == null) {
            getLogger().warning("The /" + name + " command is missing from plugin.yml - it is skipped.");
            return;
        }
        AliasCommand handler = new AliasCommand(delegate, name);
        shortcut.setExecutor(handler);
        shortcut.setTabCompleter(handler);
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

    /**
     * Re-reads config.yml. The database stays open, so nothing recorded is lost.
     *
     * <p>If {@code storage.file} now names a different file the ledger is moved over to it; when the
     * new file cannot be opened the old one is kept, so a typo in the config never loses records.
     *
     * @return null when the reload worked, or a sentence saying what did not
     */
    public String reload() {
        stopTasks();
        reloadConfig();
        Settings fresh = Settings.load(getConfig());
        if (database != null && !fresh.databaseFile().equals(settings.databaseFile())) {
            Database next = new Database(this, new File(getDataFolder(), fresh.databaseFile()));
            if (!next.open()) {
                getLogger().severe("Could not open " + fresh.databaseFile() + " - keeping "
                        + settings.databaseFile() + ".");
                startTasks();
                return "The new database file could not be opened, so " + settings.databaseFile()
                        + " is still in use.";
            }
            Database previous = database;
            database = next;
            settings = fresh;
            // The old file is closed last, and closing flushes whatever is still queued into it.
            previous.close();
            getLogger().info("Switched the ledger to " + fresh.databaseFile() + ".");
        } else {
            settings = fresh;
        }
        startTasks();
        return null;
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

    /** True while this plugin's own block write is in progress on this thread. */
    public boolean isOwnWrite() {
        return Boolean.TRUE.equals(ownWrite.get());
    }

    public void setOwnWrite(boolean ownWrite) {
        this.ownWrite.set(ownWrite);
    }

    /** Remembers what the last rollback or restore did, for /blockledger undo. */
    public void setLastChange(List<LedgerEntry> entries, boolean rollback, String actor) {
        this.lastChange = new LastChange(List.copyOf(entries), rollback, actor, System.currentTimeMillis());
    }

    public LastChange lastChange() {
        return lastChange;
    }

    public void clearLastChange() {
        this.lastChange = null;
    }

    /** What the last rollback or restore touched. */
    public record LastChange(List<LedgerEntry> entries, boolean rollback, String actor, long time) {
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
