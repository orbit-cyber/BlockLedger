package net.blockledger;

/**
 * One recorded change. {@code before} is the block state that was there, {@code after} the state it
 * became; a null side means air. Entries that do not change a block (container use, entity kills)
 * leave both null and describe themselves in {@code extra}.
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
                          String extra) {

    public static LedgerEntry blockChange(ActionType action, String world, int x, int y, int z,
                                          String actor, String before, String after) {
        return new LedgerEntry(System.currentTimeMillis(), action, world, x, y, z, actor, before, after, null);
    }

    public static LedgerEntry event(ActionType action, String world, int x, int y, int z,
                                    String actor, String extra) {
        return new LedgerEntry(System.currentTimeMillis(), action, world, x, y, z, actor, null, null, extra);
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
