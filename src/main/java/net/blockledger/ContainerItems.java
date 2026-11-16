package net.blockledger;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what a container click actually moved.
 *
 * <p>The click event fires before the change is applied, so the logger reads the inventory before and
 * one tick after, and this compares the two. That turns "Steve clicked slot 12" into "Steve took 32 x
 * diamond", which is what a chest logger is for.
 */
public final class ContainerItems {
    private ContainerItems() {
    }

    /** Most lines one click may produce, so a shift-click of a full inventory stays one readable line. */
    private static final int MAX_LINES = 6;

    /** A deep enough copy to compare against later; the arrays the server hands out are live. */
    public static ItemStack[] copy(ItemStack[] items) {
        if (items == null) {
            return new ItemStack[0];
        }
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            copy[i] = item == null ? null : item.clone();
        }
        return copy;
    }

    /**
     * The item movement between two snapshots, as short phrases: {@code took 32 x diamond},
     * {@code put 5 x iron ingot}. Empty when nothing moved.
     */
    public static List<String> delta(ItemStack[] before, ItemStack[] after) {
        // "took|DIAMOND" -> 32, kept in the order the slots were read so output is stable.
        Map<String, Integer> moves = new LinkedHashMap<>();
        int size = Math.max(before.length, after.length);
        for (int i = 0; i < size; i++) {
            ItemStack old = i < before.length ? before[i] : null;
            ItemStack now = i < after.length ? after[i] : null;
            boolean wasEmpty = isEmpty(old);
            boolean isEmpty = isEmpty(now);
            if (wasEmpty && isEmpty) {
                continue;
            }
            if (wasEmpty) {
                moves.merge("put|" + now.getType().name(), now.getAmount(), Integer::sum);
                continue;
            }
            if (isEmpty) {
                moves.merge("took|" + old.getType().name(), old.getAmount(), Integer::sum);
                continue;
            }
            if (old.getType() == now.getType()) {
                int difference = now.getAmount() - old.getAmount();
                if (difference > 0) {
                    moves.merge("put|" + now.getType().name(), difference, Integer::sum);
                } else if (difference < 0) {
                    moves.merge("took|" + old.getType().name(), -difference, Integer::sum);
                }
                continue;
            }
            // One item swapped for another: both halves are worth recording.
            moves.merge("took|" + old.getType().name(), old.getAmount(), Integer::sum);
            moves.merge("put|" + now.getType().name(), now.getAmount(), Integer::sum);
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> move : moves.entrySet()) {
            if (lines.size() >= MAX_LINES) {
                lines.add("and " + (moves.size() - MAX_LINES) + " more kinds of item");
                break;
            }
            String[] parts = move.getKey().split("\\|", 2);
            lines.add(parts[0] + " " + move.getValue() + " x " + name(parts[1]));
        }
        return lines;
    }

    /** "minecraft:diamond_block" as "diamond block". */
    public static String name(String materialName) {
        Material material = Material.matchMaterial(materialName);
        String name = material == null ? materialName : material.name();
        return name.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /** "diamond x32" - one stack as a phrase. */
    public static String describe(ItemStack item) {
        if (isEmpty(item)) {
            return "nothing";
        }
        return name(item.getType().name()) + " x" + item.getAmount();
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
