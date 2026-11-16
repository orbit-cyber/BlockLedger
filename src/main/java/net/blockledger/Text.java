package net.blockledger;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Small formatting helpers shared by the listeners and the command. */
public final class Text {
    private Text() {
    }

    public static final Component PREFIX = Component.text("[")
            .color(NamedTextColor.DARK_GRAY)
            .append(Component.text("BlockLedger", NamedTextColor.AQUA, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .decoration(TextDecoration.BOLD, false);

    public static Component info(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.GRAY));
    }

    public static Component success(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.GREEN));
    }

    public static Component warn(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.YELLOW));
    }

    public static Component error(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.RED));
    }

    public static Component plain(String message, NamedTextColor color) {
        return Component.text(message, color);
    }

    /** "12s", "5m", "3h", "2d" - the age of a record in the log. */
    public static String ago(long time) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - time) / 1000L);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }

    /** Turns "minecraft:oak_stairs" into "oak stairs" for readable output. */
    public static String blockName(String state) {
        if (state == null) {
            return "air";
        }
        String name = state;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        int bracket = name.indexOf('[');
        if (bracket >= 0) {
            name = name.substring(0, bracket);
        }
        return name.replace('_', ' ');
    }

    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format("%.1f MiB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static String number(long value) {
        return String.format("%,d", value);
    }
}
