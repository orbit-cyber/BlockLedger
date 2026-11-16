package net.blockledger;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * Turns block data into a short, stable string and back.
 *
 * <p>The string keeps the material and only the properties that differ from the default state, e.g.
 * {@code minecraft:oak_stairs[facing=east,half=bottom]}. Anything unreadable falls back to the plain
 * material, which still restores the right block.
 */
public final class BlockStates {
    private BlockStates() {
    }

    public static String serialize(Block block) {
        return serialize(block.getBlockData());
    }

    public static String serialize(BlockData data) {
        return data.getAsString(true);
    }

    public static BlockData parse(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return Material.AIR.createBlockData();
        }
        try {
            return org.bukkit.Bukkit.createBlockData(serialized);
        } catch (IllegalArgumentException e) {
            // A block that no longer exists in this version: fall back to the plain material.
            String material = serialized;
            int bracket = material.indexOf('[');
            if (bracket >= 0) {
                material = material.substring(0, bracket);
            }
            Material type = Material.matchMaterial(material);
            return (type == null || !type.isBlock() ? Material.AIR : type).createBlockData();
        }
    }

    /**
     * A container's items as one base64 string, or null when there is nothing worth storing.
     *
     * <p>Paper's own NBT item serialisation is used, so enchants, names and custom data all survive
     * the round trip without this plugin needing to understand any of it.
     */
    public static String serializeItems(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return null;
        }
        boolean anything = false;
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                anything = true;
                break;
            }
        }
        if (!anything) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
        } catch (RuntimeException e) {
            // An item this server cannot serialise: better to log the block without its contents.
            return null;
        }
    }

    /** The items back, or null when the string is missing or unreadable. */
    public static ItemStack[] parseItems(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
