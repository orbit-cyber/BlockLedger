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
            case "rollback", "rb", "undo" -> change(sender, args, true, label);
            case "restore", "rs", "redo" -> change(sender, args, false, label);
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
        sender.sendMessage(Text.plain("/" + label + " lookup <filters> - list recorded changes", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " rollback <filters> - undo the matching changes", NamedTextColor.GRAY));
        sender.sendMessage(Text.plain("/" + label + " restore <filters> - redo the matching changes", NamedTextColor.GRAY));
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
        Query query = new Query();
        int page = 1;
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            String key = key(token);
            String value = value(token);
            if (key == null) {
                if (token.equalsIgnoreCase("global")) {
                    continue;
                }
                sender.sendMessage(Text.error("Unknown filter \"" + token + "\"."));
                return;
            }
            switch (key) {
                case "u" -> query.actor = value;
                case "t" -> {
                    Long since = parseTime(value);
                    if (since == null) {
                        sender.sendMessage(Text.error("Time must look like 30s, 15m, 6h or 7d."));
                        return;
                    }
                    query.since = since;
                }
                case "r" -> {
                    Integer radius = parseInt(value);
                    if (radius == null) {
                        sender.sendMessage(Text.error("Radius must be a number."));
                        return;
                    }
                    query.radius = radius;
                }
                case "a" -> {
                    Set<ActionType> actions = ActionType.parseList(value);
                    if (actions.isEmpty()) {
                        sender.sendMessage(Text.error("Unknown action. Use one of: " + ActionType.ids()));
                        return;
                    }
                    query.actions = actions;
                }
                case "b" -> query.blockContains = value.toLowerCase(Locale.ROOT).replace(' ', '_');
                case "p" -> {
                    Integer parsed = parseInt(value);
                    if (parsed == null || parsed < 1) {
                        sender.sendMessage(Text.error("Page must be a number of 1 or more."));
                        return;
                    }
                    page = parsed;
                }
                case "x", "y", "z", "w" -> {
                    if (!applyPosition(sender, query, key, value)) {
                        return;
                    }
                }
                default -> {
                    sender.sendMessage(Text.error("Unknown filter \"" + token + "\"."));
                    return;
                }
            }
        }

        if (query.radius != null) {
            if (!resolveCenter(sender, query)) {
                return;
            }
        }

        int limit = plugin.settings().maxLookupResults();
        query.limit = limit;
        query.ascending = false;
        final int requestedPage = page;

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
                }));
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
        Query query = new Query();
        boolean global = false;
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (token.equalsIgnoreCase("global")) {
                global = true;
                continue;
            }
            String key = key(token);
            String value = value(token);
            if (key == null) {
                sender.sendMessage(Text.error("Unknown filter \"" + token + "\"."));
                return;
            }
            switch (key) {
                case "u" -> query.actor = value;
                case "t" -> {
                    Long since = parseTime(value);
                    if (since == null) {
                        sender.sendMessage(Text.error("Time must look like 30s, 15m, 6h or 7d."));
                        return;
                    }
                    query.since = since;
                }
                case "r" -> {
                    Integer radius = parseInt(value);
                    if (radius == null) {
                        sender.sendMessage(Text.error("Radius must be a number."));
                        return;
                    }
                    query.radius = radius;
                }
                case "a" -> {
                    Set<ActionType> actions = ActionType.parseList(value);
                    if (actions.isEmpty()) {
                        sender.sendMessage(Text.error("Unknown action. Use one of: " + ActionType.ids()));
                        return;
                    }
                    query.actions = actions;
                }
                case "b" -> query.blockContains = value.toLowerCase(Locale.ROOT).replace(' ', '_');
                case "x", "y", "z", "w" -> {
                    if (!applyPosition(sender, query, key, value)) {
                        return;
                    }
                }
                default -> {
                    sender.sendMessage(Text.error("Unknown filter \"" + token + "\"."));
                    return;
                }
            }
        }

        if (query.radius != null) {
            if (!resolveCenter(sender, query)) {
                return;
            }
        }

        if (!query.hasNarrowFilter() && !global) {
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
                    new BlockChanger(plugin, work, isRollback, result -> {
                        sender.sendMessage(Text.success((isRollback ? "Rollback" : "Restore") + " finished: "
                                + result.changed() + " blocks changed, " + result.skipped()
                                + " already correct or unavailable, in " + result.millis() + " ms."));
                        plugin.getLogger().info(sender.getName() + " ran a " + (isRollback ? "rollback" : "restore")
                                + " touching " + result.changed() + " blocks.");
                    }).start();
                }));
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
        plugin.reload();
        sender.sendMessage(Text.success("config.yml reloaded."));
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

    private boolean applyPosition(CommandSender sender, Query query, String key, String value) {
        if (key.equals("w")) {
            if (Bukkit.getWorld(value) == null) {
                sender.sendMessage(Text.error("Unknown world \"" + value + "\"."));
                return false;
            }
            query.world = value;
            return true;
        }
        Integer parsed = parseInt(value);
        if (parsed == null) {
            sender.sendMessage(Text.error(key + ": must be a whole number."));
            return false;
        }
        switch (key) {
            case "x" -> query.x = parsed;
            case "y" -> query.y = parsed;
            default -> query.z = parsed;
        }
        return true;
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

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("inspect", "lookup", "rollback", "restore", "purge", "status", "reload", "help"),
                    args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("purge")) {
            return filter(List.of("7", "14", "30", "90"), args[1]);
        }
        if (args.length >= 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (!sub.equals("lookup") && !sub.equals("rollback") && !sub.equals("restore")
                    && !sub.equals("l") && !sub.equals("rb") && !sub.equals("rs")) {
                return Collections.emptyList();
            }
            String current = args[args.length - 1];
            List<String> options = new ArrayList<>(List.of("u:", "t:", "r:", "a:", "b:", "p:", "x:", "y:", "z:", "w:"));
            if (sub.startsWith("r")) {
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
