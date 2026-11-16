package net.blockledger;

import org.bukkit.Bukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * A small, stable surface for another plugin to read and drive the ledger.
 *
 * <p>It exists because the other way in is a command typed in game, and a web staff panel cannot
 * type one. StaffPanel uses it to show the records, count them, and run a rollback or a purge from
 * a browser.
 *
 * <p>Three rules shape it, and they are worth keeping if this is ever changed:
 *
 * <ul>
 *   <li><b>Plain types only.</b> Results are maps and lists of strings, numbers and booleans. The
 *       caller lives in another plugin's class loader, so handing it this plugin's own classes
 *       would fail the moment the two jars disagree about a field. {@code java.util} types are
 *       shared, so they always work.
 *   <li><b>The server decides.</b> A query reads the database off the server thread, a block
 *       change runs on it, and the caller is a web thread that may be anywhere. Each call is
 *       marshalled to the thread that owns the work and waited for, with a five second cap.
 *   <li><b>Nothing throws.</b> A failure comes back as a sentence a staff member can read; a stack
 *       trace in a web request is useless.
 * </ul>
 */
public final class LedgerApi {

    /** Set on enable, cleared on disable. */
    private static volatile LedgerApi instance;

    /** How long a caller waits for the server or the database before giving up. */
    private static final long WAIT_SECONDS = 5L;

    private final BlockLedgerPlugin plugin;

    LedgerApi(BlockLedgerPlugin plugin) {
        this.plugin = plugin;
    }

    /** The live API, or null when BlockLedger is not running. */
    public static LedgerApi get() {
        return instance;
    }

    static void install(LedgerApi api) {
        instance = api;
    }

    static void uninstall() {
        instance = null;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Record count, database size, the oldest record and the write queue, as plain values:
     * {@code rows}, {@code sizeBytes}, {@code oldestTime}, {@code pending}, {@code databaseFile},
     * {@code busy}.
     */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Database.Stats stats = onAsync(() -> plugin.database().stats(), new Database.Stats(0L, 0L, 0L));
        out.put("rows", stats.rows());
        out.put("sizeBytes", stats.sizeBytes());
        out.put("oldestTime", stats.oldestTime());
        out.put("pending", plugin.database() == null ? 0 : plugin.database().pendingCount());
        out.put("databaseFile", plugin.settings() == null ? "" : plugin.settings().databaseFile());
        // A rollback in progress is worth knowing about before another is started.
        out.put("busy", plugin.isChangingBlocks());
        return out;
    }

    /**
     * The records matching a filter, newest first.
     *
     * @param filters {@code actor}, {@code since} (30s, 15m, 6h, 7d), {@code action} (comma
     *                separated ids), {@code block}, {@code world}, {@code limit}, {@code page}
     * @return one map per record: {@code time}, {@code action}, {@code actor}, {@code world},
     *         {@code x}, {@code y}, {@code z}, {@code before}, {@code after}, {@code extra}
     */
    public List<Map<String, Object>> lookup(Map<String, Object> filters) {
        Query query = query(filters);
        List<LedgerEntry> entries = onAsync(() -> plugin.database().queryAsync(query)
                .get(WAIT_SECONDS, TimeUnit.SECONDS), new ArrayList<>());
        List<Map<String, Object>> out = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            out.add(plain(entry));
        }
        return out;
    }

    /** How many records match a filter, without loading them. */
    public long count(Map<String, Object> filters) {
        Query query = query(filters);
        Long total = onAsync(() -> plugin.database().countAsync(query)
                .get(WAIT_SECONDS, TimeUnit.SECONDS), 0L);
        return total == null ? 0L : total;
    }

    /** The busiest players since a time such as "1h", as {@code actor} and {@code total}. */
    public List<Map<String, Object>> top(String since, int limit) {
        Long cutoff = parseSince(since);
        if (cutoff == null) {
            cutoff = System.currentTimeMillis() - 3_600_000L;
        }
        Long from = cutoff;
        List<Database.ActorCount> actors = onAsync(() -> plugin.database()
                .topActorsAsync(from, Math.max(1, Math.min(50, limit)))
                .get(WAIT_SECONDS, TimeUnit.SECONDS), new ArrayList<>());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Database.ActorCount actor : actors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("actor", actor.actor());
            row.put("total", actor.total());
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ changing

    /**
     * Puts the matching blocks back to how they were before.
     *
     * @return null when the job started, or a sentence saying why it did not
     */
    public String rollback(Map<String, Object> filters) {
        return change(filters, true);
    }

    /** Re-applies the matching changes. Same contract as {@link #rollback}. */
    public String restore(Map<String, Object> filters) {
        return change(filters, false);
    }

    /**
     * The same guard the command uses, for the same reason: a rollback with no narrow filter would
     * touch the whole map, so it is refused unless the caller says {@code global}.
     */
    private String change(Map<String, Object> filters, boolean rollback) {
        if (plugin.database() == null) {
            return "The ledger database is not open, so there is nothing to change.";
        }
        if (plugin.isChangingBlocks()) {
            return "A rollback or restore is already running. Wait for it to finish.";
        }
        Query query = query(filters);
        if (!query.hasNarrowFilter() && !query.global) {
            return "Give at least one filter - a player, an action, a block or a radius - or say "
                    + "that you mean the whole ledger. A rollback with no filter would touch every "
                    + "block it has ever recorded.";
        }
        if (query.limit > plugin.settings().maxRollbackResults()) {
            query.limit = plugin.settings().maxRollbackResults();
        }
        // Newest first for a rollback, oldest first for a restore, exactly as the command does it.
        query.ascending = !rollback;
        List<LedgerEntry> entries = onAsync(() -> plugin.database().queryAsync(query)
                .get(WAIT_SECONDS, TimeUnit.SECONDS), new ArrayList<>());
        if (entries.isEmpty()) {
            return "No records matched, so nothing was changed.";
        }
        String started = onMain(() -> {
            List<LedgerEntry> work = new ArrayList<>(entries);
            plugin.setLastChange(work, rollback, "the staff panel");
            new BlockChanger(plugin, work, rollback, result -> plugin.getLogger().info(
                    "The staff panel ran a " + (rollback ? "rollback" : "restore") + " touching "
                            + result.changed() + " blocks.")).start();
            return null;
        }, "The server did not answer.");
        if (started != null) {
            return started;
        }
        plugin.getLogger().info("A panel rollback started over " + entries.size() + " records.");
        return null;
    }

    /** Flips the last rollback or restore back. */
    public String revert() {
        if (plugin.isChangingBlocks()) {
            return "A rollback or restore is already running. Wait for it to finish.";
        }
        BlockLedgerPlugin.LastChange last = plugin.lastChange();
        if (last == null || last.entries().isEmpty()) {
            return "There is no rollback or restore to undo.";
        }
        List<LedgerEntry> entries = new ArrayList<>(last.entries());
        java.util.Collections.reverse(entries);
        boolean rollback = !last.rollback();
        String result = onMain(() -> {
            plugin.setLastChange(entries, rollback, "the staff panel");
            new BlockChanger(plugin, entries, rollback, done -> plugin.getLogger().info(
                    "The staff panel reverted a " + (last.rollback() ? "rollback" : "restore")
                            + " touching " + done.changed() + " blocks.")).start();
            return null;
        }, "The server did not answer.");
        return result;
    }

    /** Deletes records older than the given age. Returns null, or a sentence when it failed. */
    public String purge(int days) {
        if (days < 1) {
            return "Say how many days to keep - 1 or more.";
        }
        Integer removed = onAsync(() -> plugin.database().purgeOlderThan(days), null);
        if (removed == null) {
            return "The database did not answer, so nothing was deleted.";
        }
        plugin.getLogger().info("The staff panel purged " + removed + " records older than "
                + days + " days.");
        return null;
    }

    /** How many records the last purge removed, or -1 when it failed. */
    public int purgeCount(int days) {
        Integer removed = onAsync(() -> plugin.database().purgeOlderThan(days), -1);
        return removed == null ? -1 : removed;
    }

    /** Re-reads config.yml. The database stays open, so nothing recorded is lost. */
    public String reload() {
        return onMain(() -> {
            plugin.reload();
            return null;
        }, "The server did not answer.");
    }

    // ------------------------------------------------------------------ settings

    /**
     * What this server records and how, as plain values: every toggle under {@code log.}, the
     * purge settings, the limits, the ignored worlds and blocks, and the storage settings.
     */
    public Map<String, Object> settings() {
        Settings settings = plugin.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        if (settings == null) {
            return out;
        }
        out.put("databaseFile", settings.databaseFile());
        out.put("flushIntervalTicks", settings.flushIntervalTicks());
        out.put("log.block-break", settings.logBreak());
        out.put("log.block-place", settings.logPlace());
        out.put("log.explosions", settings.logExplosions());
        out.put("log.fire", settings.logFire());
        out.put("log.pistons", settings.logPistons());
        out.put("log.buckets", settings.logBuckets());
        out.put("log.containers", settings.logContainers());
        out.put("log.entities", settings.logEntities());
        out.put("log.items", settings.logItems());
        out.put("log.interactions", settings.logInteractions());
        out.put("log.deaths", settings.logDeaths());
        out.put("log.commands", settings.logCommands());
        out.put("log.natural-changes", settings.logNatural());
        out.put("purge.enabled", settings.purgeEnabled());
        out.put("purge.days", settings.purgeDays());
        out.put("limits.max-lookup-results", settings.maxLookupResults());
        out.put("limits.max-rollback-results", settings.maxRollbackResults());
        out.put("limits.blocks-per-tick", settings.blocksPerTick());
        out.put("ignore.worlds", String.join(", ", ignoredWorlds()));
        out.put("ignore.blocks", String.join(", ", ignoredBlocks()));
        return out;
    }

    /**
     * Writes settings back to config.yml and reloads.
     *
     * <p>Only the keys it is given are touched, so a panel that knows about three of them cannot
     * blank the rest. A value of the wrong type is skipped rather than written as a string.
     *
     * @return null when it worked, or a sentence saying what was refused
     */
    public String saveSettings(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return "No settings were sent.";
        }
        Set<String> allowed = Set.of("log.block-break", "log.block-place", "log.explosions", "log.fire",
                "log.pistons", "log.buckets", "log.containers", "log.entities", "log.items",
                "log.interactions", "log.deaths", "log.commands", "log.natural-changes",
                "purge.enabled", "purge.days", "limits.max-lookup-results", "limits.max-rollback-results",
                "limits.blocks-per-tick", "storage.flush-interval-ticks");
        Set<String> lists = Set.of("ignore.worlds", "ignore.blocks");
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (lists.contains(key)) {
                List<String> parts = new ArrayList<>();
                for (String part : String.valueOf(entry.getValue() == null ? "" : entry.getValue())
                        .split(",")) {
                    String clean = part.trim();
                    if (!clean.isEmpty()) {
                        parts.add(clean);
                    }
                }
                plugin.getConfig().set(key, parts);
                continue;
            }
            if (!allowed.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Boolean flag) {
                plugin.getConfig().set(key, flag);
            } else if (value instanceof Number number) {
                // The limits are counts of things, and a negative one would break the query it caps.
                int clean = Math.max(0, number.intValue());
                plugin.getConfig().set(key, clean);
            } else if (value instanceof String text) {
                plugin.getConfig().set(key, Boolean.parseBoolean(text.trim()));
            }
        }
        return onMain(() -> {
            plugin.saveConfig();
            plugin.reload();
            return null;
        }, "The server did not answer, so the settings were written but not applied - restart to "
                + "pick them up.");
    }

    /** The world names changes are never recorded in. */
    public List<String> ignoredWorlds() {
        return plugin.getConfig().getStringList("ignore.worlds");
    }

    /** The block types that are never recorded. */
    public List<String> ignoredBlocks() {
        return plugin.getConfig().getStringList("ignore.blocks");
    }

    // ------------------------------------------------------------------ filters

    /**
     * Turns the panel's filters into a query.
     *
     * <p>An unknown or malformed filter is ignored rather than throwing: the panel sends what a
     * staff member typed, and a typo there should narrow the search less, not break the page.
     */
    private Query query(Map<String, Object> filters) {
        Query query = new Query();
        if (filters == null) {
            return query;
        }
        Object actor = filters.get("actor");
        if (actor != null && !String.valueOf(actor).isBlank()) {
            query.actor = String.valueOf(actor).trim();
        }
        Object since = filters.get("since");
        if (since != null && !String.valueOf(since).isBlank()) {
            Long cutoff = parseSince(String.valueOf(since));
            if (cutoff != null) {
                query.since = cutoff;
            }
        }
        Object until = filters.get("until");
        if (until != null && !String.valueOf(until).isBlank()) {
            Long cutoff = parseSince(String.valueOf(until));
            if (cutoff != null) {
                query.until = cutoff;
            }
        }
        Object action = filters.get("action");
        if (action != null && !String.valueOf(action).isBlank()) {
            Set<ActionType> actions = ActionType.parseList(String.valueOf(action));
            if (!actions.isEmpty()) {
                query.actions = actions;
            }
        }
        Object block = filters.get("block");
        if (block != null && !String.valueOf(block).isBlank()) {
            query.blockContains = String.valueOf(block).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        }
        Object world = filters.get("world");
        if (world != null && !String.valueOf(world).isBlank()) {
            query.world = String.valueOf(world).trim();
        }
        query.limit = positive(filters.get("limit"), query.limit);
        query.page = positive(filters.get("page"), query.page);
        query.global = Boolean.TRUE.equals(filters.get("global"))
                || "true".equalsIgnoreCase(String.valueOf(filters.get("global")));
        return query;
    }

    private static int positive(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value).trim()));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** "30s", "15m", "6h", "7d", "2w" ago, as epoch millis; null when it is not a time. */
    static Long parseSince(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        long multiplier = 60_000L;
        String number = text;
        char unit = text.charAt(text.length() - 1);
        if (Character.isLetter(unit)) {
            number = text.substring(0, text.length() - 1);
            multiplier = switch (unit) {
                case 's' -> 1000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> -1L;
            };
        }
        if (multiplier < 0) {
            return null;
        }
        try {
            long amount = Long.parseLong(number);
            return amount < 0 ? null : System.currentTimeMillis() - amount * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One record as plain values, so nothing of this plugin's own crosses the boundary. */
    private static Map<String, Object> plain(LedgerEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", entry.time());
        row.put("action", entry.action() == null ? "" : entry.action().id());
        row.put("actor", entry.actor());
        row.put("world", entry.world());
        row.put("x", entry.x());
        row.put("y", entry.y());
        row.put("z", entry.z());
        row.put("before", entry.before());
        row.put("after", entry.after());
        row.put("extra", entry.extra());
        return row;
    }

    // ------------------------------------------------------------------ threading

    /** Runs work on the server thread and waits for it, as every block change must. */
    private <T> T onMain(Callable<T> work, T fallback) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return work.call();
            } catch (Exception e) {
                plugin.getLogger().warning("BlockLedger API call failed: " + e);
                return fallback;
            }
        }
        FutureTask<T> task = new FutureTask<>(work);
        Bukkit.getScheduler().runTask(plugin, task);
        try {
            return task.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("BlockLedger API call did not finish: " + e);
            return fallback;
        }
    }

    /**
     * Runs work off the server thread and waits for it.
     *
     * <p>The database is the slow part - a count over a year of records is a scan - so it must not
     * run on the tick. The caller is a web thread that can afford to wait, and the cap means a
     * stalled query answers as "nothing" instead of holding the request open for ever.
     */
    private <T> T onAsync(Callable<T> work, T fallback) {
        if (plugin.database() == null) {
            return fallback;
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    future.complete(work.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (IllegalStateException e) {
            // The server is shutting down and cannot schedule anything.
            return fallback;
        }
        try {
            T result = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return result == null ? fallback : result;
        } catch (Exception e) {
            plugin.getLogger().warning("A ledger query did not finish: " + e);
            return fallback;
        }
    }

    /** The database file, for a panel that wants to show where it lives. */
    public File file() {
        return plugin.database() == null ? null : plugin.database().file();
    }
}
