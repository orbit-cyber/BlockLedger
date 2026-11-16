package net.blockledger;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Every kind of change BlockLedger can record.
 */
public enum ActionType {
    BREAK("break", "broke"),
    PLACE("place", "placed"),
    EXPLOSION("explosion", "was blown up by"),
    FIRE("fire", "burned"),
    PISTON("piston", "was moved by a piston"),
    BUCKET("bucket", "used a bucket"),
    NATURAL("natural", "changed naturally"),
    CONTAINER("container", "used a container"),
    KILL("kill", "killed");

    private final String id;
    private final String verb;

    ActionType(String id, String verb) {
        this.id = id;
        this.verb = verb;
    }

    public String id() {
        return id;
    }

    public String verb() {
        return verb;
    }

    /** The type for a stored id, or null when the id is unknown. */
    public static ActionType fromId(String id) {
        if (id == null) {
            return null;
        }
        for (ActionType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }

    /** Parses a comma separated list such as "break,place". */
    public static Set<ActionType> parseList(String raw) {
        Set<ActionType> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            ActionType type = fromId(part.trim());
            if (type != null) {
                result.add(type);
            }
        }
        return result;
    }

    public static String ids() {
        StringBuilder builder = new StringBuilder();
        for (ActionType type : values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(type.id);
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return id;
    }

    public String lowerId() {
        return id.toLowerCase(Locale.ROOT);
    }
}
