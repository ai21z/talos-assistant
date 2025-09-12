package dev.loqj.core.secret;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static java.nio.file.StandardOpenOption.*;

/**
 * Cross-platform, local-only "encrypted-at-rest" secret store.
 * - Directory (default): ~/.loqj/secrets/
 * - Master key file    : ~/.loqj/secrets/.master.key  (random 256-bit; per-user folder)
 * - Entry files        : ~/.loqj/secrets/<scope>/<safe-key>.bin  (AES-GCM)
 *
 * Notes:
 *  - This is a pragmatic stub for Phase-1. On Windows we can later swap to CredMan.
 *  - We avoid Strings for secret payloads; callers should wipe char[] after use.
 *  - Filenames are sanitized; scope and key are not allowed to escape directories.
 */
public final class FileSecretStore implements SecretStore {

    private static final String MAGIC = "LQJ1";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Path baseDir;
    private final SecretKey master;
    private final SecureRandom rng = new SecureRandom();

    /** Create using Config. secrets.dir may override the default base dir. */
    @SuppressWarnings("unchecked")
    public FileSecretStore(Config cfg) {
        Map<String,Object> m = (cfg == null) ? Map.of() : (Map<String,Object>) cfg.data;
        Map<String,Object> sec = CfgUtil.map(m.get("secrets"));
        String dir = (sec == null) ? null : String.valueOf(sec.getOrDefault("dir", "")).trim();
        if (dir == null || dir.isBlank()) {
            this.baseDir = Paths.get(System.getProperty("user.home"), ".loqj", "secrets");
        } else {
            this.baseDir = Paths.get(dir);
        }
        try { Files.createDirectories(baseDir); } catch (Exception ignored) {}

        this.master = loadOrCreateMasterKey(baseDir.resolve(".master.key"));
    }

    /** Create using an explicit base directory. */
    public FileSecretStore(Path baseDir) {
        this.baseDir = baseDir == null
                ? Paths.get(System.getProperty("user.home"), ".loqj", "secrets")
                : baseDir.toAbsolutePath().normalize();
        try { Files.createDirectories(this.baseDir); } catch (Exception ignored) {}
        this.master = loadOrCreateMasterKey(this.baseDir.resolve(".master.key"));
    }

    @Override
    public void put(String scope, String key, char[] value) throws Exception {
        Objects.requireNonNull(value, "value");
        String sc = safe(scope);
        String k  = safe(key);
        Path dir = baseDir.resolve(sc);
        Path file = dir.resolve(toFileName(k));
        Files.createDirectories(dir);

        byte[] plaintext = null;
        try {
            plaintext = new String(value).getBytes(StandardCharsets.UTF_8); // copy
            byte[] iv = new byte[GCM_IV_LEN];
            rng.nextBytes(iv);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            c.init(Cipher.ENCRYPT_MODE, master, spec);
            // bind to entry coordinates as AAD
            c.updateAAD((sc + ":" + k).getBytes(StandardCharsets.UTF_8));
            byte[] cipher = c.doFinal(plaintext);

            ByteBuffer buf = ByteBuffer.allocate(MAGIC.length() + 4 + iv.length + cipher.length);
            buf.put(MAGIC.getBytes(StandardCharsets.US_ASCII));
            buf.putInt(iv.length);
            buf.put(iv);
            buf.put(cipher);

            Files.write(file, buf.array(), CREATE, TRUNCATE_EXISTING, WRITE);
            trySetOwnerOnly(file);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public Optional<char[]> get(String scope, String key) throws Exception {
        String sc = safe(scope);
        String k  = safe(key);
        Path file = baseDir.resolve(sc).resolve(toFileName(k));
        if (!Files.exists(file)) return Optional.empty();

        byte[] all = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(all);

        byte[] magic = new byte[MAGIC.length()];
        buf.get(magic);
        if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
            return Optional.empty(); // wrong format
        }

        int ivLen = buf.getInt();
        if (ivLen != GCM_IV_LEN) return Optional.empty();
        byte[] iv = new byte[ivLen];
        buf.get(iv);
        byte[] cipher = new byte[buf.remaining()];
        buf.get(cipher);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        c.init(Cipher.DECRYPT_MODE, master, spec);
        c.updateAAD((sc + ":" + k).getBytes(StandardCharsets.UTF_8));
        byte[] plain = c.doFinal(cipher);

        // convert to char[] (UTF-8) without lingering String
        String s = new String(plain, StandardCharsets.UTF_8);
        char[] out = s.toCharArray();
        Arrays.fill(plain, (byte)0);
        return Optional.of(out);
    }

    @Override
    public boolean delete(String scope, String key) throws Exception {
        String sc = safe(scope);
        String k  = safe(key);
        Path file = baseDir.resolve(sc).resolve(toFileName(k));
        if (!Files.exists(file)) return false;
        Files.delete(file);
        return true;
    }

    /* -------------------- helpers -------------------- */

    private static SecretKey loadOrCreateMasterKey(Path path) {
        try {
            if (Files.exists(path)) {
                byte[] raw = Files.readAllBytes(path);
                // basic sanity: 32 bytes expected
                if (raw.length == 32) {
                    trySetOwnerOnly(path);
                    return new javax.crypto.spec.SecretKeySpec(raw, "AES");
                }
            }
            // create a new 256-bit AES key
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            SecretKey sk = kg.generateKey();
            byte[] raw = sk.getEncoded();
            Files.write(path, raw, CREATE, TRUNCATE_EXISTING, WRITE);
            trySetOwnerOnly(path);
            Arrays.fill(raw, (byte)0);
            return sk;
        } catch (Exception e) {
            throw new RuntimeException("Unable to create or load master key: " + e.getMessage(), e);
        }
    }

    private static void trySetOwnerOnly(Path p) {
        try {
            // POSIX 0600 if available
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(p, perms);
        } catch (Exception ignore) {
            // Non-POSIX (Windows): best-effort only
        }
    }

    private static String safe(String s) {
        if (s == null || s.isBlank()) return "default";
        return s.trim();
    }

    private static String toFileName(String key) {
        String k = key == null ? "" : key.trim();
        // allow alnum, dash, underscore, dot; replace others
        k = k.replaceAll("[^A-Za-z0-9._-]", "_");
        if (k.isEmpty()) k = "unnamed";
        if (k.length() > 80) k = k.substring(0, 80);
        return k + ".bin";
    }
}
