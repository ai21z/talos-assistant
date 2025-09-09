package dev.loqj.core.cache;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;

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
        try (PreparedStatement ps = cx.prepareStatement("INSERT OR REPLACE INTO embedding_cache(key,dim,vec,ts) VALUES(?,?,?,?)")) {
            ps.setString(1, key);
            ps.setInt(2, dim);
            ps.setBytes(3, floatsToBytes(vec));
            ps.setLong(4, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public float[] getEmbedding(String key) {
        try (PreparedStatement ps = cx.prepareStatement("SELECT dim, vec FROM embedding_cache WHERE key=?")) {
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
        try (PreparedStatement ps = cx.prepareStatement("INSERT OR REPLACE INTO answer_cache(key,answer,ts) VALUES(?,?,?)")) {
            ps.setString(1, key);
            ps.setString(2, answer);
            ps.setLong(3, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public String getAnswer(String key) {
        try (PreparedStatement ps = cx.prepareStatement("SELECT answer FROM answer_cache WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getString(1);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public String getOrCreateSession(String workspaceAbs) {
        try (PreparedStatement ps = cx.prepareStatement("SELECT id FROM sessions WHERE workspace=?")) {
            ps.setString(1, workspaceAbs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        String id = java.util.UUID.randomUUID().toString();
        try (PreparedStatement ps = cx.prepareStatement("INSERT INTO sessions(id,workspace,created_ts) VALUES(?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, workspaceAbs);
            ps.setLong(3, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return id;
    }

    public Memory loadMemory(String sessionId) {
        try (PreparedStatement ps = cx.prepareStatement("SELECT sketch, entities FROM memory WHERE session_id=?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Memory(rs.getString(1), rs.getString(2));
                return new Memory("", "[]");
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void saveMemory(String sessionId, Memory m) {
        try (PreparedStatement ps = cx.prepareStatement("INSERT OR REPLACE INTO memory(session_id,sketch,entities) VALUES(?,?,?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, m.sketch());
            ps.setString(3, m.entitiesJson());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
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
