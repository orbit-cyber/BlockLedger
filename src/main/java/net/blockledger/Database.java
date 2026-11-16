package net.blockledger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * SQLite storage for the ledger. All statements run on a single connection guarded by a lock, so
 * writes from the main thread and reads from async tasks never interleave badly. The main thread
 * only ever adds entries to a queue; the actual INSERTs happen on an async flush.
 */
public final class Database {
    private final Plugin plugin;
    private final File file;
    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentLinkedQueue<LedgerEntry> pending = new ConcurrentLinkedQueue<>();

    private Connection connection;

    public Database(Plugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    public boolean open() {
        try {
            // Paper downloads the driver from Maven Central (see libraries in plugin.yml). Loading it
            // through the plugin class loader registers it with DriverManager.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("The SQLite driver could not be loaded. Check that the server "
                    + "could download org.xerial:sqlite-jdbc:3.49.1.0 from Maven Central.");
            return false;
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().severe("Could not create the folder " + parent.getAbsolutePath());
            return false;
        }

        lock.lock();
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                // WAL keeps reads fast while the queue is flushed.
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS entries (
                            id       INTEGER PRIMARY KEY AUTOINCREMENT,
                            time     INTEGER NOT NULL,
                            action   TEXT    NOT NULL,
                            world    TEXT    NOT NULL,
                            x        INTEGER NOT NULL,
                            y        INTEGER NOT NULL,
                            z        INTEGER NOT NULL,
                            actor    TEXT,
                            before   TEXT,
                            after    TEXT,
                            extra    TEXT,
                            contents TEXT
                        )""");
                // Databases made before contents logging existed have no such column yet.
                if (!hasColumn(statement, "entries", "contents")) {
                    statement.executeUpdate("ALTER TABLE entries ADD COLUMN contents TEXT");
                }
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_pos ON entries(world, x, z, time)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_time ON entries(time)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_actor ON entries(actor, time)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_action ON entries(action, time)");
            }
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not open the database " + file.getName(), e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** True when the table already has this column, used to migrate an older database in place. */
    private static boolean hasColumn(Statement statement, String table, String column) throws SQLException {
        try (ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equalsIgnoreCase(rows.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public void close() {
        flush();
        lock.lock();
        try {
            if (connection != null) {
                connection.close();
                connection = null;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not close the database cleanly", e);
        } finally {
            lock.unlock();
        }
    }

    /** Called from the main thread: cheap, never touches the database. */
    public void queue(LedgerEntry entry) {
        pending.add(entry);
    }

    public int pendingCount() {
        return pending.size();
    }

    /** Writes everything queued so far. Runs on an async thread when scheduled, but is safe anywhere. */
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<LedgerEntry> batch = new ArrayList<>(Math.min(pending.size(), 2000));
        LedgerEntry entry;
        while ((entry = pending.poll()) != null) {
            batch.add(entry);
            if (batch.size() >= 2000) {
                insert(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            insert(batch);
        }
    }

    private void insert(List<LedgerEntry> batch) {
        lock.lock();
        try {
            if (connection == null) {
                return;
            }
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO entries(time, action, world, x, y, z, actor, before, after, extra, contents) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (LedgerEntry entry : batch) {
                    statement.setLong(1, entry.time());
                    statement.setString(2, entry.action().id());
                    statement.setString(3, entry.world());
                    statement.setInt(4, entry.x());
                    statement.setInt(5, entry.y());
                    statement.setInt(6, entry.z());
                    statement.setString(7, entry.actor());
                    statement.setString(8, entry.before());
                    statement.setString(9, entry.after());
                    statement.setString(10, entry.extra());
                    statement.setString(11, entry.contents());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not write " + batch.size() + " entries", e);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Nothing useful to do here; the next flush tries again.
            }
        } finally {
            try {
                if (connection != null) {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException ignored) {
                // Same as above.
            }
            lock.unlock();
        }
    }

    /** Runs a query off the main thread, after writing anything still queued so it is seen. */
    public CompletableFuture<List<LedgerEntry>> queryAsync(Query query) {
        CompletableFuture<List<LedgerEntry>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // A block broken a moment ago may still be queued; flush first so lookups and inspect see
            // it straight away instead of after the next scheduled flush. A flush failure must not
            // stop the query - the records already on disk are still worth showing.
            try {
                flush();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not flush the write queue before a query", t);
            }
            try {
                future.complete(query(query));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private List<LedgerEntry> query(Query query) {
        List<LedgerEntry> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT time, action, world, x, y, z, actor, before, after, extra, contents "
                        + "FROM entries WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        appendFilters(sql, parameters, query);
        // The order matters to rollback and restore: the last entry applied for a position wins, so the
        // caller asks for the direction it needs.
        sql.append(" ORDER BY time ").append(query.ascending ? "ASC" : "DESC");
        sql.append(", id ").append(query.ascending ? "ASC" : "DESC");
        sql.append(" LIMIT ?");
        parameters.add(Math.max(1, query.limit));

        lock.lock();
        try {
            if (connection == null) {
                return results;
            }
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bind(statement, parameters);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ActionType action = ActionType.fromId(rows.getString(2));
                        if (action == null) {
                            continue;
                        }
                        results.add(new LedgerEntry(
                                rows.getLong(1),
                                action,
                                rows.getString(3),
                                rows.getInt(4),
                                rows.getInt(5),
                                rows.getInt(6),
                                rows.getString(7),
                                rows.getString(8),
                                rows.getString(9),
                                rows.getString(10),
                                rows.getString(11)));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not run a database query", e);
        } finally {
            lock.unlock();
        }
        return results;
    }

    /** The shared WHERE clause for every query, so count, export and lookups always agree. */
    private void appendFilters(StringBuilder sql, List<Object> parameters, Query query) {
        if (query.since != null) {
            sql.append(" AND time >= ?");
            parameters.add(query.since);
        }
        if (query.until != null) {
            sql.append(" AND time <= ?");
            parameters.add(query.until);
        }
        if (query.actor != null) {
            sql.append(" AND actor = ?");
            parameters.add(query.actor);
        }
        if (query.noActor) {
            sql.append(" AND actor IS NULL");
        }
        if (query.actions != null && !query.actions.isEmpty()) {
            sql.append(" AND action IN (");
            boolean first = true;
            for (ActionType type : query.actions) {
                if (!first) {
                    sql.append(',');
                }
                sql.append('?');
                parameters.add(type.id());
                first = false;
            }
            sql.append(')');
        }
        if (query.world != null) {
            sql.append(" AND world = ?");
            parameters.add(query.world);
        }
        if (query.x != null) {
            sql.append(" AND x = ?");
            parameters.add(query.x);
        }
        if (query.y != null) {
            sql.append(" AND y = ?");
            parameters.add(query.y);
        }
        if (query.z != null) {
            sql.append(" AND z = ?");
            parameters.add(query.z);
        }
        if (query.radius != null && query.x != null && query.y != null && query.z != null) {
            int radius = query.radius;
            sql.append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            parameters.add(query.x - radius);
            parameters.add(query.x + radius);
            parameters.add(query.y - radius);
            parameters.add(query.y + radius);
            parameters.add(query.z - radius);
            parameters.add(query.z + radius);
        }
        if (query.blockContains != null) {
            String like = "%" + query.blockContains.toLowerCase() + "%";
            sql.append(" AND (lower(before) LIKE ? OR lower(after) LIKE ?)");
            parameters.add(like);
            parameters.add(like);
        }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    /** How many records match, without loading them. Runs on an async thread. */
    public CompletableFuture<Long> countAsync(Query query) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                flush();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not flush before a count", t);
            }
            long total = 0L;
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM entries WHERE 1=1");
            List<Object> parameters = new ArrayList<>();
            appendFilters(sql, parameters, query);
            lock.lock();
            try {
                if (connection != null) {
                    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                        bind(statement, parameters);
                        try (ResultSet row = statement.executeQuery()) {
                            if (row.next()) {
                                total = row.getLong(1);
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
                return;
            } finally {
                lock.unlock();
            }
            future.complete(total);
        });
        return future;
    }

    /** The busiest actors since a timestamp, most records first. */
    public CompletableFuture<List<ActorCount>> topActorsAsync(long since, int limit) {
        CompletableFuture<List<ActorCount>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                flush();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not flush before a summary", t);
            }
            List<ActorCount> actors = new ArrayList<>();
            lock.lock();
            try {
                if (connection != null) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT actor, COUNT(*) AS total FROM entries WHERE actor IS NOT NULL AND time >= ? "
                                    + "GROUP BY actor ORDER BY total DESC LIMIT ?")) {
                        statement.setLong(1, since);
                        statement.setInt(2, Math.max(1, limit));
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                actors.add(new ActorCount(rows.getString(1), rows.getLong(2)));
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
                return;
            } finally {
                lock.unlock();
            }
            future.complete(actors);
        });
        return future;
    }

    /** One player's records per action, most first, for /blockledger stats. */
    public CompletableFuture<List<ActionCount>> actionCountsAsync(String actor, Long since) {
        CompletableFuture<List<ActionCount>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                flush();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not flush before a summary", t);
            }
            List<ActionCount> counts = new ArrayList<>();
            StringBuilder sql = new StringBuilder(
                    "SELECT action, COUNT(*) AS total FROM entries WHERE actor = ?");
            List<Object> parameters = new ArrayList<>();
            parameters.add(actor);
            if (since != null) {
                sql.append(" AND time >= ?");
                parameters.add(since);
            }
            sql.append(" GROUP BY action ORDER BY total DESC");

            lock.lock();
            try {
                if (connection != null) {
                    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                        bind(statement, parameters);
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                counts.add(new ActionCount(rows.getString(1), rows.getLong(2)));
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
                return;
            } finally {
                lock.unlock();
            }
            future.complete(counts);
        });
        return future;
    }

    /** Writes the matching records to a CSV file. Returns how many rows were written. */
    public CompletableFuture<Integer> exportCsvAsync(Query query, File target) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                flush();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not flush before an export", t);
            }
            StringBuilder sql = new StringBuilder(
                    "SELECT time, action, world, x, y, z, actor, before, after, extra, contents "
                            + "FROM entries WHERE 1=1");
            List<Object> parameters = new ArrayList<>();
            appendFilters(sql, parameters, query);
            sql.append(" ORDER BY time ASC, id ASC LIMIT ?");
            parameters.add(Math.max(1, query.limit));

            int written = 0;
            lock.lock();
            try (java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(
                    target.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write("time,action,world,x,y,z,actor,before,after,extra,contents");
                writer.newLine();
                if (connection != null) {
                    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                        bind(statement, parameters);
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                writer.write(csvLine(
                                        java.time.Instant.ofEpochMilli(rows.getLong(1)).toString(),
                                        rows.getString(2),
                                        rows.getString(3),
                                        String.valueOf(rows.getInt(4)),
                                        String.valueOf(rows.getInt(5)),
                                        String.valueOf(rows.getInt(6)),
                                        rows.getString(7),
                                        rows.getString(8),
                                        rows.getString(9),
                                        rows.getString(10),
                                        rows.getString(11)));
                                writer.newLine();
                                written++;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
                return;
            } finally {
                lock.unlock();
            }
            future.complete(written);
        });
        return future;
    }

    /** One CSV line, with every field quoted and inner quotes doubled. */
    private static String csvLine(String... fields) {
        StringBuilder line = new StringBuilder();
        for (String field : fields) {
            if (line.length() > 0) {
                line.append(',');
            }
            line.append('"').append(field == null ? "" : field.replace("\"", "\"\"")).append('"');
        }
        return line.toString();
    }

    /** Deletes records older than the given age. Returns how many rows went away. */
    public int purgeOlderThan(int days) {
        long cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
        lock.lock();
        try {
            if (connection == null) {
                return 0;
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM entries WHERE time < ?")) {
                statement.setLong(1, cutoff);
                return statement.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not purge old records", e);
            return 0;
        } finally {
            lock.unlock();
        }
    }

    /** Row count, oldest timestamp and file size, for /blockledger status. */
    public Stats stats() {
        lock.lock();
        try {
            if (connection == null) {
                return new Stats(0, 0L, 0L);
            }
            long rows = 0L;
            long oldest = 0L;
            try (Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*), MIN(time) FROM entries")) {
                    if (result.next()) {
                        rows = result.getLong(1);
                        oldest = result.getLong(2);
                    }
                }
            }
            long size = file.exists() ? file.length() : 0L;
            return new Stats(rows, oldest, size);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read database statistics", e);
            return new Stats(0, 0L, 0L);
        } finally {
            lock.unlock();
        }
    }

    public File file() {
        return file;
    }

    public record Stats(long rows, long oldestTime, long sizeBytes) {
    }

    /** One line of /blockledger top. */
    public record ActorCount(String actor, long total) {
    }

    /** One line of /blockledger stats. */
    public record ActionCount(String action, long total) {
    }
}
