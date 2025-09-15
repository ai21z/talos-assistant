package dev.loqj.core.cache;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;

public class CacheDb implements AutoCloseable {
    private final Connection cx;

    public static Path defaultPath() {
        String home = System.getProperty("user.home");
        return Path.of(home, ".loqj", "cache.db");
    }

    public CacheDb() { this(defaultPath()); }

    public CacheDb(Path dbPath) {
        try {
            java.nio.file.Files.createDirectories(dbPath.getParent());
            this.cx = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            this.cx.setAutoCommit(true);
            // Improve concurrency/durability balance + avoid lock churn
            try (Statement st = cx.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA synchronous=NORMAL;");
                st.execute("PRAGMA busy_timeout=5000;");
            }
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void init() throws SQLException {
        try (Statement st = cx.createStatement()) {
            st.execute("""
              CREATE TABLE IF NOT EXISTS embedding_cache(
                key TEXT PRIMARY KEY,
                dim INTEGER NOT NULL,
                vec BLOB NOT NULL,
                ts  INTEGER NOT NULL
              );
            """);
            st.execute("""
              CREATE TABLE IF NOT EXISTS answer_cache(
                key TEXT PRIMARY KEY,
                answer TEXT NOT NULL,
                ts INTEGER NOT NULL
              );
            """);
            st.execute("""
              CREATE TABLE IF NOT EXISTS sessions(
                id TEXT PRIMARY KEY,
                workspace TEXT NOT NULL,
                created_ts INTEGER NOT NULL
              );
            """);
            st.execute("""
              CREATE TABLE IF NOT EXISTS memory(
                session_id TEXT PRIMARY KEY,
                sketch TEXT NOT NULL,
                entities TEXT NOT NULL
              );
            """);
        }
    }

    public void putEmbedding(String key, int dim, float[] vec) {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT OR REPLACE INTO embedding_cache(key,dim,vec,ts) VALUES(?,?,?,?)")) {
            ps.setString(1, key);
            ps.setInt(2, dim);
            ps.setBytes(3, floatsToBytes(vec));
            ps.setLong(4, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public float[] getEmbedding(String key) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT dim, vec FROM embedding_cache WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int dim = rs.getInt(1);
                byte[] b = rs.getBytes(2);
                return bytesToFloats(b, dim);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void putAnswer(String key, String answer) {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT OR REPLACE INTO answer_cache(key,answer,ts) VALUES(?,?,?)")) {
            ps.setString(1, key);
            ps.setString(2, answer);
            ps.setLong(3, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public String getAnswer(String key) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT answer FROM answer_cache WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public String getOrCreateSession(String workspaceAbs) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT id FROM sessions WHERE workspace=?")) {
            ps.setString(1, workspaceAbs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        String id = java.util.UUID.randomUUID().toString();
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT INTO sessions(id,workspace,created_ts) VALUES(?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, workspaceAbs);
            ps.setLong(3, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return id;
    }

    public Memory loadMemory(String sessionId) {
        try (PreparedStatement ps = cx.prepareStatement(
                "SELECT sketch, entities FROM memory WHERE session_id=?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Memory(rs.getString(1), rs.getString(2));
                return new Memory("", "[]");
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void saveMemory(String sessionId, Memory m) {
        try (PreparedStatement ps = cx.prepareStatement(
                "INSERT OR REPLACE INTO memory(session_id,sketch,entities) VALUES(?,?,?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, m.sketch());
            ps.setString(3, m.entitiesJson());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /**
     * Prune old embeddings older than the specified number of days.
     * Returns the number of rows deleted.
     */
    public int pruneOldEmbeddings(int days) {
        long cutoffMs = Instant.now().minusSeconds(days * 24L * 3600L).toEpochMilli();
        try (PreparedStatement ps = cx.prepareStatement(
                "DELETE FROM embedding_cache WHERE ts < ?")) {
            ps.setLong(1, cutoffMs);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                // Optimize database after cleanup
                try (Statement st = cx.createStatement()) {
                    st.execute("VACUUM");
                }
            }
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to prune old embeddings", e);
        }
    }

    /**
     * Prune old answers older than the specified number of days.
     * Returns the number of rows deleted.
     */
    public int pruneOldAnswers(int days) {
        long cutoffMs = Instant.now().minusSeconds(days * 24L * 3600L).toEpochMilli();
        try (PreparedStatement ps = cx.prepareStatement(
                "DELETE FROM answer_cache WHERE ts < ?")) {
            ps.setLong(1, cutoffMs);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                // Optimize database after cleanup
                try (Statement st = cx.createStatement()) {
                    st.execute("VACUUM");
                }
            }
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to prune old answers", e);
        }
    }

    /**
     * Get cache statistics for monitoring.
     */
    public CacheStats getStats() {
        try {
            int embeddingCount = 0, answerCount = 0;
            long embeddingSize = 0, answerSize = 0;

            try (Statement st = cx.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(LENGTH(vec)) FROM embedding_cache")) {
                if (rs.next()) {
                    embeddingCount = rs.getInt(1);
                    embeddingSize = rs.getLong(2);
                }
            }

            try (Statement st = cx.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(LENGTH(answer)) FROM answer_cache")) {
                if (rs.next()) {
                    answerCount = rs.getInt(1);
                    answerSize = rs.getLong(2);
                }
            }

            return new CacheStats(embeddingCount, embeddingSize, answerCount, answerSize);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get cache stats", e);
        }
    }

    public static record CacheStats(int embeddingCount, long embeddingSize, int answerCount, long answerSize) {
        public String summary() {
            return String.format("Embeddings: %d entries (%.1f KB), Answers: %d entries (%.1f KB)",
                embeddingCount, embeddingSize / 1024.0, answerCount, answerSize / 1024.0);
        }
    }

    private static byte[] floatsToBytes(float[] v) {
        byte[] b = new byte[v.length * 4];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float f : v) bb.putFloat(f);
        return b;
    }
    private static float[] bytesToFloats(byte[] b, int dim) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int n = b.length / 4;
        float[] v = new float[n];
        for (int i=0;i<n;i++) v[i] = bb.getFloat();
        return v;
    }

    public record Memory(String sketch, String entitiesJson) {}
    @Override public void close() { try { cx.close(); } catch (Exception ignore) {} }
}
