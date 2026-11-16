package net.blockledger;

/**
 * One recorded change. {@code before} is the block state that was there, {@code after} the state it
 * became; a null side means air. Entries that do not change a block (container use, entity kills)
 * leave both null and describe themselves in {@code extra}.
 *
 * <p>{@code contents} is the base64 item data of a container, when contents logging is on: the chest
 * as it was for a break, or as it is after a click. It is null for everything else.
 */
public record LedgerEntry(long time,
                          ActionType action,
                          String world,
                          int x,
                          int y,
                          int z,
                          String actor,
                          String before,
                          String after,
                          String extra,
                          String contents) {

    public static LedgerEntry blockChange(ActionType action, String world, int x, int y, int z,
                                          String actor, String before, String after) {
        return new LedgerEntry(System.currentTimeMillis(), action, world, x, y, z, actor,
                before, after, null, null);
    }

    public static LedgerEntry event(ActionType action, String world, int x, int y, int z,
                                    String actor, String extra) {
        return new LedgerEntry(System.currentTimeMillis(), action, world, x, y, z, actor,
                null, null, extra, null);
    }

    /** The same entry with the container's item data attached. */
    public LedgerEntry withContents(String items) {
        if (items == null) {
            return this;
        }
        return new LedgerEntry(time, action, world, x, y, z, actor, before, after, extra, items);
    }

    public boolean changesBlock() {
        return before != null || after != null;
    }

    /** The state to put the block back into: the old one when rolling back, the new one when restoring. */
    public String targetFor(boolean rollback) {
        String state = rollback ? before : after;
        return state == null ? "minecraft:air" : state;
    }
}
