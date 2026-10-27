package dev.talos.engine.llamacpp.profile;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.engine.llamacpp.LlamaCppModelCatalog;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Persists metadata-bound verification records so multi-gigabyte GGUFs are not hashed per prompt. */
public final class LlamaCppArtifactVerificationStore {
    static final String VERIFIER_VERSION = "llama-cpp-model-artifact-v1";
    private static final int MAX_RECORD_BYTES = 16 * 1024;

    @FunctionalInterface
    interface FileHasher {
        String sha256(Path path) throws Exception;
    }

    private record Record(
            String verifierVersion,
            String alias,
            String canonicalPath,
            long size,
            long modifiedMillis,
            String fileIdentity,
            String expectedSha256,
            String actualSha256,
            long verifiedAtEpochMillis) {
    }

    private final FileHasher hasher;
    private final ObjectMapper json;

    public LlamaCppArtifactVerificationStore() {
        this(LlamaCppArtifactVerificationStore::sha256File);
    }

    LlamaCppArtifactVerificationStore(FileHasher hasher) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.json = new ObjectMapper();
    }

    public LlamaCppProfileResolver.ArtifactIdentity inspect(
            Path modelPath,
            String alias,
            LlamaCppModelCatalog.ModelArtifact expected,
            Path talosHome) {
        FileFacts facts = facts(modelPath);
        if (facts == null) {
            return identity(LlamaCppProfileResolver.ArtifactState.MISSING, modelPath, expected, -1, "", "");
        }
        if (expected == null) {
            return identity(LlamaCppProfileResolver.ArtifactState.CUSTOM, facts.path(), null,
                    facts.size(), "", "");
        }
        if (facts.size() != expected.sizeBytes()) {
            return identity(LlamaCppProfileResolver.ArtifactState.SIZE_MISMATCH, facts.path(), expected,
                    facts.size(), "", "");
        }
        Path recordPath = recordPath(talosHome, facts.path(), expected.sha256());
        CachedRecord cached = readRecord(recordPath);
        if (cached == null || !matches(cached.record(), facts, alias, expected)) {
            return identity(LlamaCppProfileResolver.ArtifactState.UNCHECKED, facts.path(), expected,
                    facts.size(), "", "");
        }
        return identity(LlamaCppProfileResolver.ArtifactState.EXACT, facts.path(), expected,
                facts.size(), cached.record().actualSha256(), cached.identity());
    }

    public LlamaCppProfileResolver.ArtifactIdentity verify(
            Path modelPath,
            String alias,
            LlamaCppModelCatalog.ModelArtifact expected,
            Path talosHome) throws Exception {
        LlamaCppProfileResolver.ArtifactIdentity cached = inspect(modelPath, alias, expected, talosHome);
        if (cached.state() == LlamaCppProfileResolver.ArtifactState.EXACT
                || cached.state() == LlamaCppProfileResolver.ArtifactState.MISSING
                || cached.state() == LlamaCppProfileResolver.ArtifactState.SIZE_MISMATCH
                || cached.state() == LlamaCppProfileResolver.ArtifactState.CUSTOM) {
            return cached;
        }

        FileFacts facts = facts(modelPath);
        if (facts == null || expected == null) return cached;
        String actual = cleanHash(hasher.sha256(facts.path()));
        return recordVerified(modelPath, alias, expected, talosHome, actual);
    }

    /** Persists metadata for bytes whose SHA-256 was already established by the downloader. */
    public LlamaCppProfileResolver.ArtifactIdentity recordVerified(
            Path modelPath,
            String alias,
            LlamaCppModelCatalog.ModelArtifact expected,
            Path talosHome,
            String verifiedSha256) throws Exception {
        FileFacts facts = facts(modelPath);
        if (facts == null) {
            return identity(LlamaCppProfileResolver.ArtifactState.MISSING, modelPath, expected, -1, "", "");
        }
        if (expected == null) {
            return identity(LlamaCppProfileResolver.ArtifactState.CUSTOM, facts.path(), null,
                    facts.size(), "", "");
        }
        if (facts.size() != expected.sizeBytes()) {
            return identity(LlamaCppProfileResolver.ArtifactState.SIZE_MISMATCH, facts.path(), expected,
                    facts.size(), "", "");
        }
        String actual = cleanHash(verifiedSha256);
        Path recordPath = recordPath(talosHome, facts.path(), expected.sha256());
        if (!actual.equals(cleanHash(expected.sha256()))) {
            Files.deleteIfExists(recordPath);
            return identity(LlamaCppProfileResolver.ArtifactState.SHA256_MISMATCH, facts.path(), expected,
                    facts.size(), actual, "");
        }

        Record record = new Record(
                VERIFIER_VERSION,
                clean(alias),
                facts.path().toString(),
                facts.size(),
                facts.modifiedMillis(),
                facts.fileIdentity(),
                cleanHash(expected.sha256()),
                actual,
                System.currentTimeMillis());
        byte[] bytes = json.writeValueAsBytes(record);
        writeAtomically(recordPath, bytes);
        return identity(LlamaCppProfileResolver.ArtifactState.EXACT, facts.path(), expected,
                facts.size(), actual, sha256(bytes));
    }

    private CachedRecord readRecord(Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_RECORD_BYTES) return null;
            byte[] bytes;
            try (InputStream in = Files.newInputStream(path)) {
                bytes = in.readNBytes(MAX_RECORD_BYTES + 1);
            }
            if (bytes.length > MAX_RECORD_BYTES) return null;
            JsonParser parser = json.getFactory().createParser(bytes);
            JsonNode root = json.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) return null;
            Record record = json.treeToValue(root, Record.class);
            return new CachedRecord(record, sha256(bytes));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean matches(
            Record record,
            FileFacts facts,
            String alias,
            LlamaCppModelCatalog.ModelArtifact expected) {
        if (record == null) return false;
        return VERIFIER_VERSION.equals(record.verifierVersion())
                && clean(alias).equals(record.alias())
                && facts.path().toString().equals(record.canonicalPath())
                && facts.size() == record.size()
                && facts.modifiedMillis() == record.modifiedMillis()
                && facts.fileIdentity().equals(record.fileIdentity())
                && cleanHash(expected.sha256()).equals(record.expectedSha256())
                && record.expectedSha256().equals(record.actualSha256());
    }

    private static LlamaCppProfileResolver.ArtifactIdentity identity(
            LlamaCppProfileResolver.ArtifactState state,
            Path path,
            LlamaCppModelCatalog.ModelArtifact expected,
            long actualSize,
            String actualSha,
            String verificationIdentity) {
        return new LlamaCppProfileResolver.ArtifactIdentity(
                state,
                canonicalText(path),
                expected == null ? -1 : expected.sizeBytes(),
                actualSize,
                expected == null ? "" : expected.sha256(),
                actualSha,
                verificationIdentity);
    }

    private static FileFacts facts(Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            Path canonical = path.toRealPath();
            BasicFileAttributes attrs = Files.readAttributes(canonical, BasicFileAttributes.class);
            Object key = attrs.fileKey();
            return new FileFacts(
                    canonical,
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    key == null ? "" : key.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path recordPath(Path talosHome, Path canonicalPath, String expectedSha) {
        Path home = talosHome == null
                ? defaultTalosHome()
                : talosHome.toAbsolutePath().normalize();
        String key = sha256((canonicalPath.toString() + "\n" + cleanHash(expectedSha))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return home.resolve("identity").resolve("model-artifacts").resolve(key + ".json");
    }

    private static void writeAtomically(Path target, byte[] bytes) throws Exception {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sha256File(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String canonicalText(Path path) {
        if (path == null) return "";
        try {
            return path.toRealPath().toString();
        } catch (Exception ignored) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static Path defaultTalosHome() {
        return Path.of(System.getProperty("user.home", "."), ".talos").toAbsolutePath().normalize();
    }

    private static String cleanHash(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return Objects.toString(value, "").trim();
    }

    private record FileFacts(Path path, long size, long modifiedMillis, String fileIdentity) {
    }

    private record CachedRecord(Record record, String identity) {
    }
}
