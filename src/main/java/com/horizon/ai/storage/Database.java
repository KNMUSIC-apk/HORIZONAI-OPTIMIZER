package com.horizon.ai.storage;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite với một luồng riêng. Mọi truy vấn chạy bất đồng bộ, không bao giờ chặn Main Thread.
 * Connection chỉ được dùng trong luồng DB (trừ lúc khởi tạo và đóng).
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public record IsolationRecord(String machineId, String world, int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ, String state, long createdAt,
                                  String approvedBy, String snapshot) {
    }

    private final Connection conn;
    private final ExecutorService exec;
    private final Logger log;

    public Database(File file, Logger log) throws SQLException {
        this.log = log;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Không tìm thấy driver SQLite", e);
        }
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HorizonAI-DB");
            t.setDaemon(true);
            return t;
        });
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=3000");
            st.execute("CREATE TABLE IF NOT EXISTS isolations (" +
                    "machine_id TEXT PRIMARY KEY, world TEXT NOT NULL," +
                    "min_x INTEGER, min_y INTEGER, min_z INTEGER, max_x INTEGER, max_y INTEGER, max_z INTEGER," +
                    "state TEXT NOT NULL, created_at INTEGER NOT NULL, approved_by TEXT, snapshot TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS audit_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT, action TEXT NOT NULL," +
                    "machine_id TEXT, detail TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS perf_history (" +
                    "ts INTEGER NOT NULL, tps REAL, mspt REAL, cpu REAL, ram_mb INTEGER, players INTEGER," +
                    "chunks INTEGER, entities INTEGER, redstone_events INTEGER)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_perf_ts ON perf_history(ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_log(ts)");
        }
    }

    public CompletableFuture<Void> run(String sql, Object... params) {
        return CompletableFuture.runAsync(() -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.WARNING, "[HorizonAI] Lỗi DB: " + sql, e);
            }
        }, exec);
    }

    public <T> CompletableFuture<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            List<T> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                }
            } catch (SQLException e) {
                log.log(Level.WARNING, "[HorizonAI] Lỗi DB khi truy vấn: " + sql, e);
            }
            return out;
        }, exec);
    }

    public void audit(String actor, String action, String machineId, String detail) {
        run("INSERT INTO audit_log(ts, actor, action, machine_id, detail) VALUES (?,?,?,?,?)",
                System.currentTimeMillis(), actor, action, machineId, detail);
    }

    public void saveIsolation(IsolationRecord r) {
        run("INSERT OR REPLACE INTO isolations(machine_id, world, min_x, min_y, min_z, max_x, max_y, max_z," +
                        "state, created_at, approved_by, snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                r.machineId(), r.world(), r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ(),
                r.state(), r.createdAt(), r.approvedBy(), r.snapshot());
    }

    public void deleteIsolation(String machineId) {
        run("DELETE FROM isolations WHERE machine_id = ?", machineId);
    }

    public CompletableFuture<List<IsolationRecord>> loadIsolations() {
        return queryList("SELECT machine_id, world, min_x, min_y, min_z, max_x, max_y, max_z, state, created_at," +
                        " approved_by, snapshot FROM isolations WHERE state = 'ISOLATED'",
                rs -> new IsolationRecord(rs.getString(1), rs.getString(2),
                        rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                        rs.getString(9), rs.getLong(10), rs.getString(11), rs.getString(12)));
    }

    public void savePerfSample(double tps, double mspt, double cpu, long ramMb, int players,
                               int chunks, int entities, int redstoneEvents) {
        run("INSERT INTO perf_history VALUES (?,?,?,?,?,?,?,?,?)",
                System.currentTimeMillis(), tps, mspt, cpu, ramMb, players, chunks, entities, redstoneEvents);
    }

    public void purgeOlderThan(int retentionDays) {
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        run("DELETE FROM perf_history WHERE ts < ?", cutoff);
        run("DELETE FROM audit_log WHERE ts < ?", cutoff);
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    @Override
    public void close() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            conn.close();
        } catch (SQLException e) {
            log.log(Level.WARNING, "[HorizonAI] Lỗi khi đóng DB", e);
        }
    }
}
