package net.blockledger;

import java.util.Set;

/**
 * The filter used by lookups, rollbacks and restores.
 */
public final class Query {
    public Long since;
    public Long until;
    public String actor;
    /** When true, only entries without an actor (natural causes) are matched. */
    public boolean noActor;
    public Set<ActionType> actions;
    public String world;
    public Integer x;
    public Integer y;
    public Integer z;
    public Integer radius;
    public String blockContains;
    public int limit = 100;
    public boolean ascending = true;
    /** Page for /blockledger lookup. */
    public int page = 1;
    /** Set when the player wrote "global", allowing a rollback without a narrow filter. */
    public boolean global;

    /** True when at least one narrow filter is set, so a rollback can never hit the whole database. */
    public boolean hasNarrowFilter() {
        return actor != null || noActor || radius != null || blockContains != null
                || (actions != null && !actions.isEmpty());
    }
}
