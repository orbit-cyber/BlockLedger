package net.blockledger;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

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
}
