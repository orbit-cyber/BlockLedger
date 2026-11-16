package net.blockledger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /blockledger - inspect, lookup, rollback, restore, purge, status, reload.
 */
public final class BlockLedgerCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final BlockLedgerPlugin plugin;

    public BlockLedgerCommand(BlockLedgerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockledger.command")) {
            sender.sendMessage(Text.error("You do not have permission to use this command."));
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        return dispatch(sender, args[0], args, label);
    }

    /**
     * Runs one subcommand. Also used by the top-level shortcuts (/restore, /rollback), which pass
     * their own name as {@code rawSub} and the arguments the player typed.
     */
    boolean dispatch(CommandSender sender, String rawSub, String[] args, String label) {
        String sub = rawSub.toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender, label);
            case "inspect", "i" -> inspect(sender);
            case "lookup", "l" -> lookup(sender, args, label);
            case "near", "n" -> near(sender, args);
            case "count" -> count(sender, args);
            case "top", "leaderboard" -> top(sender, args);
            case "stats" -> stats(sender, args);
            case "preview" -> preview(sender, args);
            case "export" -> export(sender, args);
            case "tp", "teleport" -> teleport(sender, args);
            case "rollback", "rb" -> change(sender, args, true, label);
            case "restore", "rs" -> change(sender, args, false, label);
            case "revert", "undo" -> undo(sender);
            case "purge" -> purge(sender, args);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> {
                sender.sendMessage(Text.error("Unknown subcommand \"" + rawSub + "\"."));
                help(sender, label);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void help(CommandSender sender, String label) {
        sender.sendMessage(Component.text("BlockLedger", NamedTextColor.AQUA));
        sender.sendMessage(Text.plain("/" + label + " inspect - toggle click-to-inspect", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " near [radius] [time] - recent activity around you",
                NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " lookup <filters> - list recorded changes", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " count <filters> - how many records match", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " top [time] [limit] - the busiest players", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " stats [player] [time] - what one player did",
                NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " preview <filters> - what a rollback would touch",
                NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " tp <x> <y> <z> [world] - teleport to a place", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " export <file> <filters> - write matching records to CSV",
                NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " rollback <filters> - undo the matching changes", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " restore <filters> - redo the matching changes", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " revert (or undo) - flip the last rollback or restore back",
                NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " purge <days> - delete records older than that", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " status - database statistics", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " reload - reload config.yml", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("Filters: u:<player> t:<time> r:<radius> a:<action> b:<block>"
                + " p:<page> x:<x> y:<y> z:<z> w:<world>", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Text.plain("Time is 30s, 15m, 6h, 7d. Actions: " + ActionType.ids(),
                NamedTextColor.DARK_GRAY));
        sender.sendMessage(Text.plain("Rollback and restore need at least one of u:, r:, a:, b: "
                + "(add \"global\" to run without them).", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Text.plain("/inspect, /restore and /rollback work on their own, without the "
                + label + " prefix.", NamedTextColor.DARK_GRAY));
    }

    private void inspect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.error("Only a player can toggle inspect mode."));
            return;
        }
        if (!player.hasPermission("blockledger.inspect")) {
            player.sendMessage(Text.error("You do not have permission to inspect."));
            return;
        }
        if (plugin.inspecting().remove(player.getUniqueId())) {
            player.sendMessage(Text.info("Inspect mode off."));
        } else {
            plugin.inspecting().add(player.getUniqueId());
            player.sendMessage(Text.success("Inspect mode on - left or right click a block to see its history."));
        }
    }

    private void lookup(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("blockledger.lookup")) {
            sender.sendMessage(Text.error("You do not have permission to look up records."));
            return;
        }
        Query query;
        try {
            query = parseFilters(args, 1);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Text.error(e.getMessage()));
            return;
        }
        if (query.radius != null && !resolveCenter(sender, query)) {
            return;
        }

        int limit = plugin.settings().maxLookupResults();
        query.limit = limit;
        query.ascending = false;
        final int requestedPage = query.page;

        sender.sendMessage(Text.info("Searching the ledger ..."));
        plugin.database().queryAsync(query).thenAccept(entries ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (entries.isEmpty()) {
                        sender.sendMessage(Text.warn("No records matched."));
                        return;
                    }
                    int perPage = 10;
                    int pages = (entries.size() + perPage - 1) / perPage;
                    int current = Math.min(requestedPage, pages);
                    int from = (current - 1) * perPage;
                    int to = Math.min(from + perPage, entries.size());
                    sender.sendMessage(Text.info("Results " + (from + 1) + "-" + to + " of " + entries.size()
                            + (entries.size() >= limit ? " (capped at " + limit + ")" : "")
                            + ", page " + current + "/" + pages + ":"));
                    for (int i = from; i < to; i++) {
                        sender.sendMessage(describe(entries.get(i)));
                    }
                })).exceptionally(error -> {
                    reportFailure(sender, error);
                    return null;
                });
    }

    private void change(CommandSender sender, String[] args, boolean rollback, String label) {
        String verb = rollback ? "roll back" : "restore";
        if (!sender.hasPermission("blockledger.rollback")) {
            sender.sendMessage(Text.error("You do not have permission to " + verb + " changes."));
            return;
        }
        if (plugin.isChangingBlocks()) {
            sender.sendMessage(Text.error("A rollback or restore is already running. Wait for it to finish."));
            return;
        }
        Query query;
        try {
            query = parseFilters(args, 1);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Text.error(e.getMessage()));
            return;
        }

        if (query.radius != null && !resolveCenter(sender, query)) {
            return;
        }

        if (!query.hasNarrowFilter() && !query.global) {
            sender.sendMessage(Text.error("That would " + verb + " the whole database. Add u:, r:, a: or b:,"
                    + " or write \"global\" if you really mean it."));
            return;
        }

        query.limit = plugin.settings().maxRollbackResults();
        // A rollback applies the newest change first, so the oldest recorded "before" state is the one
        // that survives; a restore applies the oldest first, so the newest recorded state survives.
        query.ascending = !rollback;
        final boolean isRollback = rollback;

        sender.sendMessage(Text.info("Searching the ledger ..."));
        plugin.database().queryAsync(query).thenAccept(entries ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (entries.isEmpty()) {
                        sender.sendMessage(Text.warn("No records matched - nothing to " + verb + "."));
                        return;
                    }
                    List<LedgerEntry> work = new ArrayList<>(entries);
                    sender.sendMessage(Text.info((isRollback ? "Rolling back " : "Restoring ")
                            + work.size() + " changes ..."));
                    plugin.setLastChange(work, isRollback, sender.getName());
                    new BlockChanger(plugin, work, isRollback, result -> {
                        String containers = result.containers() > 0
                                ? ", " + result.containers() + " containers refilled" : "";
                        sender.sendMessage(Text.success((isRollback ? "Rollback" : "Restore") + " finished: "
                                + result.changed() + " blocks changed" + containers + ", " + result.skipped()
                                + " already correct or unavailable, in " + result.millis() + " ms."
                                + " Use /" + label + " revert to flip it back."));
                        plugin.getLogger().info(sender.getName() + " ran a " + (isRollback ? "rollback" : "restore")
                                + " touching " + result.changed() + " blocks.");
                    }).start();
                })).exceptionally(error -> {
                    reportFailure(sender, error);
                    return null;
                });
    }

    /** /blockledger near - recent activity around the player. */
    private void near(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockledger.lookup")) {
            sender.sendMessage(Text.error("You do not have permission to look up records."));
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(Text.error("Only a player can use near. From the console, use lookup with "
                    + "x: y: z: and r:."));
            return;
        }
        int radius = 20;
        String time = "15m";
        if (args.length > 1) {
            Integer parsed = parseInt(args[1]);
            if (parsed == null || parsed < 1) {
                sender.sendMessage(Text.error("Usage: /blockledger near [radius] [time], e.g. near 30 2h"));
                return;
            }
            radius = parsed;
        }
        if (args.length > 2) {
            time = args[2];
        }
        if (parseTime(time) == null) {
            sender.sendMessage(Text.error("Time must look like 30s, 15m, 6h or 7d."));
            return;
        }
        // Reuse the lookup output: build the same filters the player would have typed.
        lookup(sender, new String[]{"lookup", "r:" + radius, "t:" + time}, "blockledger");
    }

    /** /blockledger count - how many records match, without listing them. */
    private void count(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockledger.lookup")) {
            sender.sendMessage(Text.error("You do not have permission to look up records."));
            return;
        }
        Query query;
        try {
            query = parseFilters(args, 1);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Text.error(e.getMessage()));
            return;
        }
        if (query.radius != null && !resolveCenter(sender, query)) {
            return;
        }
        sender.sendMessage(Text.info("Counting ..."));
        plugin.database().countAsync(query).thenAccept(total ->
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        sender.sendMessage(Text.success(Text.number(total) + " records match."))))
                .exceptionally(error -> {
                    reportFailure(sender, error);
                    return null;
                });
    }

    /** /blockledger top - the busiest players. */
    private void top(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockledger.lookup")) {
            sender.sendMessage(Text.error("You do not have permission to look up records."));
            return;
        }
        String time = args.length > 1 ? args[1] : "24h";
        Long since = parseTime(time);
        if (since == null) {
            sender.sendMessage(Text.error("Usage: /blockledger top [time] [limit], e.g. top 7d 10"));
            return;
        }
        int limit = 10;
        if (args.length > 2) {
            Integer parsed = parseInt(args[2]);
            if (parsed == null || parsed < 1) {
                sender.sendMessage(Text.error("Limit must be a whole number of 1 or more."));
                return;
            }
            limit = Math.min(parsed, 50);
        }
        final int shown = limit;
        sender.sendMessage(Text.info("Counting activity from the last " + time + " ..."));
        plugin.database().topActorsAsync(since, limit).thenAccept(actors ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (actors.isEmpty()) {
                        sender.sendMessage(Text.warn("No activity in that period."));
                        return;
                    }
                    sender.sendMessage(Text.info("Busiest in the last " + time + ":"));
                    int rank = 1;
                    for (Database.ActorCount entry : actors) {
                        sender.sendMessage(Text.plain(rank++ + ". " + entry.actor() + " - "
                                + Text.number(entry.total()) + " records", NamedTextColor.GRAY));
                    }
                    if (actors.size() >= shown) {
                        sender.sendMessage(Text.info("Showing the top " + shown + "."));
                    }
                })).exceptionally(error -> {
                    reportFailure(sender, error);
                    return null;
                });
    }

    /** /blockledger export - write matching records to a CSV file in the plugin folder. */
    private void export(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockledger.export")) {
            sender.sendMessage(Text.error("You do not have permission to export records."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.error("Usage: /blockledger export <file> <filters>"));
            return;
        }
        // Keep the name inside the plugin folder, whatever was typed.
        String name = args[1].replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank()) {
            name = "export";
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            name = name + ".csv";
        }
        Query query;
        try {
            query = parseFilters(args, 2);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Text.error(e.getMessage()));
            return;
        }
        if (query.radius != null && !resolveCenter(sender, query)) {
            return;
        }
        query.limit = plugin.settings().maxExportResults();
        java.io.File target = new java.io.File(plugin.getDataFolder(), name);
        sender.sendMessage(Text.info("Writing " + name + " ..."));
        plugin.database().exportCsvAsync(query, target).thenAccept(rows ->
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        sender.sendMessage(Text.success("Wrote " + Text.number(rows) + " records to "
                                + target.getName() + " in plugins/BlockLedger/."))))
                .exceptionally(error -> {
                    reportFailure(sender, error);
                    return null;
                });
    }

    /** /blockledger tp - teleport to a place, usually one seen in a lookup. */
    private void teleport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockledger.tp")) {
            sender.sendMessage(Text.error("You do not have permission to teleport."));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.error("Only a player can teleport."));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(Text.error("Usage: /blockledger tp <x> <y> <z> [world]"));
            return;
        }
        Integer x = parseInt(args[1]);
        Integer y = parseInt(args[2]);
        Integer z = parseInt(args[3]);
        if (x == null || y == null || z == null) {
            sender.sendMessage(Text.error("x, y and z must be whole numbers."));
            return;
        }
        World world = args.length > 4 ? Bukkit.getWorld(args[4]) : player.getWorld();
        if (world == null) {
            sender.sendMessage(Text.error("Unknown world \"" + args[4] + "\"."));
            return;
        }
        Location target = new Location(world, x + 0.5, y, z + 0.5);
        player.teleportAsync(target).thenAccept(moved ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (Boolean.TRUE.equals(moved)) {
                        player.sendMessage(Text.success("Teleported to " + x + ", " + y + ", " + z
                                + " in " + world.getName() + "."));
                    } else {
                        player.sendMessage(Text.error("The teleport did not happen - something cancelled it."));
                    }
                }));
    }

    /** /blockledger revert - flip the last rollback or restore back. */
    private void undo(CommandSender sender) {
        if (!sender.hasPermission("blockledger.rollback")) {
            sender.sendMessage(Text.error("You do not have permission to change blocks."));
            return;
        }
        if (plugin.isChangingBlocks()) {
            sender.sendMessage(Text.error("A rollback or restore is already running. Wait for it to finish."));
            return;
        }
        BlockLedgerPlugin.LastChange last = plugin.lastChange();
        if (last == null || last.entries().isEmpty()) {
            sender.sendMessage(Text.warn("There is no rollback or restore to undo."));
            return;
        }
        // Undoing a rollback is a restore and the other way round, applied in the opposite order.
        List<LedgerEntry> entries = new ArrayList<>(last.entries());
        Collections.reverse(entries);
        boolean rollback = !last.rollback();
        String what = last.rollback() ? "rollback" : "restore";

        sender.sendMessage(Text.info("Reverting the last " + what + " (" + entries.size() + " records) ..."));
        plugin.setLastChange(entries, rollback, sender.getName());
        new BlockChanger(plugin, entries, rollback, result -> {
            String containers = result.containers() > 0
                    ? ", " + result.containers() + " containers refilled" : "";
            sender.sendMessage(Text.success("Reverted the last " + what + ": " + result.changed()
                    + " blocks changed" + containers + ", " + result.skipped()
                    + " already correct or unavailable."));
            plugin.getLogger().info(sender.getName() + " reverted a " + what + " touching "
                    + result.changed() + " blocks.");
        }).start();
    }

    /** /blockledger preview - what a rollback with these filters would touch, changing nothing. */
    private void preview(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockledger.lookup")) {
            sender.sendMessage(Text.error("You do not have permission to look up records."));
            return;
        }
        Query query;
        try {
            query = parseFilters(args, 1);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Text.error(e.getMessage()));
            return;
        }
        if (query.radius != null && !resolveCenter(sender, query)) {
            return;
        }
        if (!query.hasNarrowFilter() && !query.global) {
            sender.sendMessage(Text.error("That is the whole database. Add u:, r:, a: or b:, or write "
                    + "\"global\" if you really mean it."));
            return;
        }
        query.limit = plugin.settings().maxRollbackResults();
        Query blocks = blockChangesOnly(query);

        sender.sendMessage(Text.info("Checking what a rollback would touch ..."));
        // With an action filter that cannot change a block there is nothing to count: answer 0 at once.
        java.util.concurrent.CompletableFuture<Long> blockCount = blocks == null
                ? java.util.concurrent.CompletableFuture.completedFuture(0L)
                : plugin.database().countAsync(blocks);
        plugin.database().countAsync(query).thenCompose(all ->
                        blockCount.thenApply(blockChanges -> new long[]{all, blockChanges}))
                .thenAccept(counts -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    long matched = counts[0];
                    long changed = counts[1];
                    if (matched == 0) {
                        sender.sendMessage(Text.warn("Nothing matches, so a rollback would do nothing."));
                        return;
                    }
                    sender.sendMessage(Text.info(Text.number(matched) + " records match."));
                    sender.sendMessage(Text.info(Text.number(changed) + " of them change a block; the rest "
                            + "are item, container, command and death records."));
                    long capped = plugin.settings().maxRollbackResults();
                    if (matched > capped) {
                        sender.sendMessage(Text.warn("Only the newest " + Text.number(capped)
                                + " would be applied in one rollback."));
                    }
                    sender.sendMessage(Text.info("Nothing was changed. Run /blockledger rollback with the "
                            + "same filters to apply it."));
                })).exceptionally(error -> {
                    reportFailure(sender, error);
                    return null;
                });
    }

    /**
     * The same filters, narrowed to the actions that actually change a block; null when the player's
     * own action filter rules all of those out.
     */
    private static Query blockChangesOnly(Query source) {
        Set<ActionType> blockActions = Set.of(ActionType.BREAK, ActionType.PLACE, ActionType.EXPLOSION,
                ActionType.FIRE, ActionType.PISTON, ActionType.BUCKET, ActionType.NATURAL);
        Set<ActionType> chosen = blockActions;
        if (source.actions != null && !source.actions.isEmpty()) {
            chosen = new java.util.LinkedHashSet<>(source.actions);
            chosen.retainAll(blockActions);
            if (chosen.isEmpty()) {
                return null;
            }
        }
        Query copy = new Query();
        copy.since = source.since;
        copy.until = source.until;
        copy.actor = source.actor;
        copy.noActor = source.noActor;
        copy.world = source.world;
        copy.x = source.x;
        copy.y = source.y;
        copy.z = source.z;
        copy.radius = source.radius;
        copy.blockContains = source.blockContains;
        copy.global = source.global;
        copy.actions = chosen;
        return copy;
    }

    /** /blockledger stats - what one player has been doing. */
    private void stats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockledger.lookup")) {
            sender.sendMessage(Text.error("You do not have permission to look up records."));
            return;
        }
        String actor = null;
        if (args.length > 1) {
            actor = args[1];
        } else if (sender instanceof Player player) {
            actor = player.getName();
        }
        if (actor == null || actor.isBlank()) {
            sender.sendMessage(Text.error("Usage: /blockledger stats <player> [time]"));
            return;
        }
        Long since = null;
        if (args.length > 2) {
            since = parseTime(args[2]);
            if (since == null) {
                sender.sendMessage(Text.error("Time must look like 30s, 15m, 6h or 7d."));
                return;
            }
        }
        final String who = actor;
        final Long from = since;
        final String period = args.length > 2 ? args[2] : "all time";

        sender.sendMessage(Text.info("Counting " + who + "'s activity (" + period + ") ..."));
        plugin.database().actionCountsAsync(who, from).thenAccept(counts ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (counts.isEmpty()) {
                        sender.sendMessage(Text.warn("No records for " + who + " in that period."));
                        return;
                    }
                    long total = 0L;
                    for (Database.ActionCount count : counts) {
                        total += count.total();
                    }
                    sender.sendMessage(Text.info(who + " - " + Text.number(total) + " records (" + period + "):"));
                    for (Database.ActionCount count : counts) {
                        sender.sendMessage(Text.plain("  " + count.action() + ": " + Text.number(count.total()),
                                NamedTextColor.GRAY));
                    }
                })).exceptionally(error -> {
                    reportFailure(sender, error);
                    return null;
                });
    }

    private void purge(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockledger.purge")) {
            sender.sendMessage(Text.error("You do not have permission to purge records."));
            return;
        }
        Integer days = args.length > 1 ? parseInt(args[1]) : null;
        if (days == null || days < 1) {
            sender.sendMessage(Text.error("Usage: /blockledger purge <days>"));
            return;
        }
        sender.sendMessage(Text.info("Purging records older than " + days + " days ..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int removed = plugin.database().purgeOlderThan(days);
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage(Text.success("Removed " + Text.number(removed) + " records.")));
        });
    }

    private void status(CommandSender sender) {
        if (!sender.hasPermission("blockledger.status")) {
            sender.sendMessage(Text.error("You do not have permission to see the status."));
            return;
        }
        sender.sendMessage(Text.info("Reading database statistics ..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.Stats stats = plugin.database().stats();
            int pending = plugin.database().pendingCount();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(Text.plain("Records: " + Text.number(stats.rows()), NamedTextColor.GRAY));
                sender.sendMessage(Text.plain("Database file: " + Text.bytes(stats.sizeBytes())
                        + " (" + plugin.settings().databaseFile() + ")", NamedTextColor.GRAY));
                sender.sendMessage(Text.plain("Oldest record: " + (stats.oldestTime() == 0
                        ? "none yet"
                        : STAMP.format(Instant.ofEpochMilli(stats.oldestTime()))), NamedTextColor.GRAY));
                sender.sendMessage(Text.plain("Queued for writing: " + pending, NamedTextColor.GRAY));
                sender.sendMessage(Text.plain("Purge: " + (plugin.settings().purgeEnabled()
                        ? "records older than " + plugin.settings().purgeDays() + " days"
                        : "disabled"), NamedTextColor.GRAY));
            });
        });
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("blockledger.reload")) {
            sender.sendMessage(Text.error("You do not have permission to reload the config."));
            return;
        }
        String problem = plugin.reload();
        if (problem == null) {
            sender.sendMessage(Text.success("config.yml reloaded."));
        } else {
            sender.sendMessage(Text.error(problem));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String key(String token) {
        int colon = token.indexOf(':');
        if (colon <= 0 || colon == token.length() - 1) {
            return null;
        }
        return token.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    private static String value(String token) {
        int colon = token.indexOf(':');
        return colon < 0 ? "" : token.substring(colon + 1);
    }

    /**
     * Parses the filter arguments from {@code from} onwards.
     *
     * @throws IllegalArgumentException with a message fit for the player when something is wrong
     */
    private Query parseFilters(String[] args, int from) {
        Query query = new Query();
        for (int i = from; i < args.length; i++) {
            String token = args[i];
            if (token.equalsIgnoreCase("global")) {
                query.global = true;
                continue;
            }
            String key = key(token);
            String value = value(token);
            if (key == null) {
                throw new IllegalArgumentException("Unknown filter \"" + token + "\".");
            }
            switch (key) {
                case "u" -> query.actor = value;
                case "t" -> {
                    Long since = parseTime(value);
                    if (since == null) {
                        throw new IllegalArgumentException("Time must look like 30s, 15m, 6h or 7d.");
                    }
                    query.since = since;
                }
                case "r" -> {
                    Integer radius = parseInt(value);
                    if (radius == null || radius < 0) {
                        throw new IllegalArgumentException("Radius must be a whole number of blocks.");
                    }
                    query.radius = radius;
                }
                case "a" -> {
                    Set<ActionType> actions = ActionType.parseList(value);
                    if (actions.isEmpty()) {
                        throw new IllegalArgumentException("Unknown action. Use one of: " + ActionType.ids());
                    }
                    query.actions = actions;
                }
                case "b" -> query.blockContains = value.toLowerCase(Locale.ROOT).replace(' ', '_');
                case "p" -> {
                    Integer parsed = parseInt(value);
                    if (parsed == null || parsed < 1) {
                        throw new IllegalArgumentException("Page must be a number of 1 or more.");
                    }
                    query.page = parsed;
                }
                case "x" -> query.x = requireInt(key, value);
                case "y" -> query.y = requireInt(key, value);
                case "z" -> query.z = requireInt(key, value);
                case "w" -> {
                    if (Bukkit.getWorld(value) == null) {
                        throw new IllegalArgumentException("Unknown world \"" + value + "\".");
                    }
                    query.world = value;
                }
                default -> throw new IllegalArgumentException("Unknown filter \"" + token + "\".");
            }
        }
        return query;
    }

    private static int requireInt(String key, String value) {
        Integer parsed = parseInt(value);
        if (parsed == null) {
            throw new IllegalArgumentException(key + ": must be a whole number.");
        }
        return parsed;
    }

    /** Fills in a missing centre from the player's position. */
    private boolean resolveCenter(CommandSender sender, Query query) {
        if (query.x != null && query.y != null && query.z != null) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.error("A radius needs x:, y: and z: when run from the console."));
            return false;
        }
        Location location = player.getLocation();
        if (query.x == null) {
            query.x = location.getBlockX();
        }
        if (query.y == null) {
            query.y = location.getBlockY();
        }
        if (query.z == null) {
            query.z = location.getBlockZ();
        }
        if (query.world == null) {
            query.world = player.getWorld().getName();
        }
        return true;
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "30s", "15m", "6h", "7d", "2w" or a plain number of minutes. Returns the epoch millis, or null. */
    static Long parseTime(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        long multiplier;
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
        } else {
            multiplier = 60_000L;
        }
        if (multiplier < 0) {
            return null;
        }
        try {
            long amount = Long.parseLong(number);
            if (amount < 0) {
                return null;
            }
            return System.currentTimeMillis() - (amount * multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One readable line for a record. */
    public static Component describe(LedgerEntry entry) {
        Component line = Component.text(Text.ago(entry.time()) + " ago  ", NamedTextColor.DARK_GRAY)
                .append(Component.text(entry.actor() == null ? "the world" : entry.actor(), NamedTextColor.WHITE))
                .append(Component.text(" ", NamedTextColor.GRAY));

        Component what;
        switch (entry.action()) {
            case BREAK -> what = Component.text("broke " + Text.blockName(entry.before()), NamedTextColor.RED);
            case PLACE -> what = Component.text("placed " + Text.blockName(entry.after())
                    + " over " + Text.blockName(entry.before()), NamedTextColor.GREEN);
            case EXPLOSION -> what = Component.text("blew up " + Text.blockName(entry.before()), NamedTextColor.GOLD);
            case FIRE -> what = Component.text(entry.after() == null
                    ? "burned " + Text.blockName(entry.before())
                    : "lit a fire on " + Text.blockName(entry.before()), NamedTextColor.GOLD);
            case PISTON -> what = Component.text(entry.after() == null
                    ? "pushed away " + Text.blockName(entry.before())
                    : "pushed in " + Text.blockName(entry.after()), NamedTextColor.YELLOW);
            case BUCKET -> what = Component.text(entry.after() == null
                    ? "took " + Text.blockName(entry.before())
                    : "poured " + Text.blockName(entry.after()), NamedTextColor.AQUA);
            case NATURAL -> what = Component.text("changed " + Text.blockName(entry.before())
                    + " to " + Text.blockName(entry.after()), NamedTextColor.DARK_AQUA);
            case CONTAINER -> what = Component.text("used a container", NamedTextColor.LIGHT_PURPLE);
            case KILL -> what = Component.text("killed " + orSomething(entry.extra(), "a mob"),
                    NamedTextColor.DARK_RED);
            case DROP -> what = Component.text("dropped " + orSomething(entry.extra(), "an item"),
                    NamedTextColor.YELLOW);
            case PICKUP -> what = Component.text("picked up " + orSomething(entry.extra(), "an item"),
                    NamedTextColor.GREEN);
            case CRAFT -> what = Component.text("crafted " + orSomething(entry.extra(), "an item"),
                    NamedTextColor.AQUA);
            case CONSUME -> what = Component.text("consumed " + orSomething(entry.extra(), "an item"),
                    NamedTextColor.AQUA);
            case USE -> what = Component.text(orSomething(entry.extra(), "used something"),
                    NamedTextColor.LIGHT_PURPLE);
            case SIGN -> what = Component.text("edited " + orSomething(entry.extra(), "a sign"),
                    NamedTextColor.BLUE);
            case ENTITY -> what = Component.text("placed " + orSomething(entry.extra(), "an entity"),
                    NamedTextColor.LIGHT_PURPLE);
            case COMMAND -> what = Component.text("ran " + orSomething(entry.extra(), "a command"),
                    NamedTextColor.WHITE);
            case DEATH -> what = Component.text(orSomething(entry.extra(), "died"), NamedTextColor.DARK_RED);
            default -> what = Component.text("changed something", NamedTextColor.GRAY);
        }

        line = line.append(what);
        if (entry.extra() != null && entry.action() == ActionType.CONTAINER) {
            line = line.append(Component.text(" (" + entry.extra() + ")", NamedTextColor.DARK_GRAY));
        }
        line = line.append(Component.text(" at " + entry.x() + ", " + entry.y() + ", " + entry.z()
                + " in " + entry.world(), NamedTextColor.DARK_GRAY));
        return line;
    }

    private static String orSomething(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    /** Tells the sender a query failed instead of leaving them with silence. */
    void reportFailure(CommandSender sender, Throwable error) {
        plugin.getLogger().log(java.util.logging.Level.WARNING, "A ledger query failed", error);
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        plugin.getServer().getScheduler().runTask(plugin, () ->
                sender.sendMessage(Text.error("The ledger could not be read: " + reason)));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("inspect", "near", "lookup", "count", "top", "stats", "preview", "tp",
                    "export", "rollback", "restore", "revert", "purge", "status", "reload", "help"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("purge")) {
                return filter(List.of("7", "14", "30", "90"), args[1]);
            }
            if (sub.equals("near")) {
                return filter(List.of("10", "20", "50", "100"), args[1]);
            }
            if (sub.equals("top")) {
                return filter(List.of("1h", "6h", "24h", "7d", "30d"), args[1]);
            }
            if (sub.equals("stats")) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    names.add(online.getName());
                }
                return filter(names, args[1]);
            }
            if (sub.equals("tp") || sub.equals("teleport")) {
                return Collections.emptyList();
            }
            if (sub.equals("export")) {
                // The second argument is the file name, not a filter.
                return Collections.emptyList();
            }
        }
        if (args.length == 3 && (sub.equals("top") || sub.equals("stats"))) {
            return filter(List.of("5", "10", "20", "50"), args[2]);
        }
        if (args.length >= 2) {
            boolean takesFilters = sub.equals("lookup") || sub.equals("l") || sub.equals("count")
                    || sub.equals("preview") || sub.equals("rollback") || sub.equals("rb")
                    || sub.equals("rs") || sub.equals("restore") || sub.equals("export");
            if (!takesFilters) {
                return Collections.emptyList();
            }
            String current = args[args.length - 1];
            List<String> options = new ArrayList<>(List.of("u:", "t:", "r:", "a:", "b:", "p:", "x:", "y:", "z:", "w:"));
            if (sub.equals("rollback") || sub.equals("rb") || sub.equals("restore") || sub.equals("rs")) {
                options.add("global");
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                options.add("u:" + player.getName());
            }
            for (World world : Bukkit.getWorlds()) {
                options.add("w:" + world.getName());
            }
            for (ActionType type : ActionType.values()) {
                options.add("a:" + type.id());
            }
            for (Material material : Material.values()) {
                if (material.isBlock()) {
                    options.add("b:" + material.name().toLowerCase(Locale.ROOT));
                }
            }
            return filter(options, current);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
