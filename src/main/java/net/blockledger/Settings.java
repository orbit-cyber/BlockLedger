package net.blockledger;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A parsed, immutable snapshot of config.yml.
 */
public final class Settings {
    private final String databaseFile;
    private final int flushIntervalTicks;
    private final boolean logBreak;
    private final boolean logPlace;
    private final boolean logExplosions;
    private final boolean logFire;
    private final boolean logPistons;
    private final boolean logBuckets;
    private final boolean logContainers;
    private final boolean logEntities;
    private final boolean logNatural;
    private final boolean logItems;
    private final boolean logInteractions;
    private final boolean logDeaths;
    private final boolean logCommands;
    private final boolean logContainerContents;
    private final Set<String> ignoredWorlds;
    private final Set<String> ignoredBlocks;
    private final boolean purgeEnabled;
    private final int purgeDays;
    private final int maxLookupResults;
    private final int maxRollbackResults;
    private final int maxExportResults;
    private final int blocksPerTick;

    private Settings(FileConfiguration config) {
        this.databaseFile = config.getString("storage.file", "blockledger.db");
        this.flushIntervalTicks = Math.max(1, config.getInt("storage.flush-interval-ticks", 20));
        this.logBreak = config.getBoolean("log.block-break", true);
        this.logPlace = config.getBoolean("log.block-place", true);
        this.logExplosions = config.getBoolean("log.explosions", true);
        this.logFire = config.getBoolean("log.fire", true);
        this.logPistons = config.getBoolean("log.pistons", true);
        this.logBuckets = config.getBoolean("log.buckets", true);
        this.logContainers = config.getBoolean("log.containers", true);
        this.logEntities = config.getBoolean("log.entities", true);
        this.logNatural = config.getBoolean("log.natural-changes", false);
        this.logItems = config.getBoolean("log.items", true);
        this.logInteractions = config.getBoolean("log.interactions", true);
        this.logDeaths = config.getBoolean("log.deaths", true);
        this.logCommands = config.getBoolean("log.commands", true);
        this.logContainerContents = config.getBoolean("log.container-contents", false);
        Set<String> worlds = new HashSet<>();
        List<String> worldList = config.getStringList("ignore.worlds");
        for (String world : worldList) {
            if (world != null && !world.isBlank()) {
                worlds.add(world.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.ignoredWorlds = Set.copyOf(worlds);

        Set<String> blocks = new HashSet<>();
        List<String> blockList = config.getStringList("ignore.blocks");
        for (String block : blockList) {
            if (block != null && !block.isBlank()) {
                blocks.add(block.trim().toUpperCase(Locale.ROOT));
            }
        }
        this.ignoredBlocks = Set.copyOf(blocks);

        this.purgeEnabled = config.getBoolean("purge.enabled", true);
        this.purgeDays = Math.max(1, config.getInt("purge.days", 30));
        this.maxLookupResults = Math.max(1, config.getInt("limits.max-lookup-results", 100));
        this.maxRollbackResults = Math.max(1, config.getInt("limits.max-rollback-results", 20000));
        this.maxExportResults = Math.max(1, config.getInt("limits.max-export-results", 100000));
        this.blocksPerTick = Math.max(1, config.getInt("limits.blocks-per-tick", 200));
    }

    public static Settings load(FileConfiguration config) {
        return new Settings(config);
    }

    public String databaseFile() {
        return databaseFile;
    }

    public int flushIntervalTicks() {
        return flushIntervalTicks;
    }

    public boolean logBreak() {
        return logBreak;
    }

    public boolean logPlace() {
        return logPlace;
    }

    public boolean logExplosions() {
        return logExplosions;
    }

    public boolean logFire() {
        return logFire;
    }

    public boolean logPistons() {
        return logPistons;
    }

    public boolean logBuckets() {
        return logBuckets;
    }

    public boolean logContainers() {
        return logContainers;
    }

    public boolean logEntities() {
        return logEntities;
    }

    public boolean logNatural() {
        return logNatural;
    }

    public boolean logItems() {
        return logItems;
    }

    public boolean logInteractions() {
        return logInteractions;
    }

    public boolean logDeaths() {
        return logDeaths;
    }

    public boolean logCommands() {
        return logCommands;
    }

    /** True when the items inside a container are stored with it, so a rollback can put them back. */
    public boolean logContainerContents() {
        return logContainerContents;
    }

    public boolean isIgnoredWorld(String worldName) {
        return worldName != null && ignoredWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public boolean isIgnoredBlock(String materialName) {
        return materialName != null && ignoredBlocks.contains(materialName.toUpperCase(Locale.ROOT));
    }

    public boolean purgeEnabled() {
        return purgeEnabled;
    }

    public int purgeDays() {
        return purgeDays;
    }

    public int maxLookupResults() {
        return maxLookupResults;
    }

    public int maxRollbackResults() {
        return maxRollbackResults;
    }

    public int maxExportResults() {
        return maxExportResults;
    }

    public int blocksPerTick() {
        return blocksPerTick;
    }
}
