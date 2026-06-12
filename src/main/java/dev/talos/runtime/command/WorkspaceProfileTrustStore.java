package dev.talos.runtime.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.util.Hash;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Content-hash trust pins for workspace verification-profile declarations
 * (owner decision 2026-06-12: trust pin AND per-run approval, both).
 *
 * <p>A declaration is inert until the user explicitly reviews and pins it
 * ({@code /profiles trust} shows the full declaration plus its SHA-256).
 * The pin records the hash of the declaration's raw bytes — ANY byte change
 * (including line endings, by design) returns the workspace to an untrusted
 * state and requires re-consent. Pins live under
 * {@code ~/.talos/trust/workspace-profiles/<workspace-hash>.json}, outside
 * every workspace. A corrupted or unreadable pin fails closed to untrusted.
 */
public final class WorkspaceProfileTrustStore {

    /** Trust state of a workspace's declaration, in evaluation order. */
    public enum TrustState {
        /** No declaration file exists in the workspace. */
        NONE_DECLARED,
        /** The declaration failed validation — nothing can register or run. */
        INVALID,
        /** A valid declaration exists but has never been pinned. */
        UNTRUSTED_NEW,
        /** A valid declaration exists but differs from the pinned bytes. */
        UNTRUSTED_CHANGED,
        /** The declaration matches the user's pinned hash. */
        TRUSTED
    }

    /** One persisted consent record. */
    public record Pin(
            int schemaVersion,
            String workspaceId,
            String declarationSha256,
            String pinnedAt,
            int profileCount
    ) {}

    static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path trustDir;

    public WorkspaceProfileTrustStore() {
        this(defaultTrustDir());
    }

    /** Test seam: an explicit trust directory keeps pins out of the real home. */
    public WorkspaceProfileTrustStore(Path trustDir) {
        this.trustDir = Objects.requireNonNull(trustDir, "trustDir is required")
                .toAbsolutePath().normalize();
    }

    static Path defaultTrustDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("USERPROFILE");
        }
        Path base = home == null || home.isBlank()
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(home);
        return base.resolve(".talos").resolve("trust").resolve("workspace-profiles");
    }

    /** Same workspace keying as the checkpoint and session stores. */
    public static String workspaceId(Path workspace) {
        return Hash.sha1Hex(workspace.toAbsolutePath().normalize().toString());
    }

    /** Evaluate the trust state for a freshly loaded declaration. */
    public TrustState state(Path workspace, WorkspaceCommandProfilesLoader.Loaded loaded) {
        if (loaded == null || !loaded.profiles().declared()) {
            return TrustState.NONE_DECLARED;
        }
        if (!loaded.profiles().valid()) {
            return TrustState.INVALID;
        }
        Optional<Pin> pin = readPin(workspaceId(workspace));
        if (pin.isEmpty()) {
            return TrustState.UNTRUSTED_NEW;
        }
        return pin.get().declarationSha256().equals(loaded.declarationSha256())
                ? TrustState.TRUSTED
                : TrustState.UNTRUSTED_CHANGED;
    }

    /** Record explicit user consent for the declaration bytes. */
    public Pin pin(Path workspace, String declarationSha256, int profileCount, Instant pinnedAt) {
        String workspaceId = workspaceId(workspace);
        Pin pin = new Pin(SCHEMA_VERSION, workspaceId,
                Objects.toString(declarationSha256, ""),
                (pinnedAt == null ? Instant.EPOCH : pinnedAt).toString(),
                Math.max(0, profileCount));
        try {
            Files.createDirectories(trustDir);
            ObjectNode node = JSON.createObjectNode();
            node.put("schemaVersion", pin.schemaVersion());
            node.put("workspaceId", pin.workspaceId());
            node.put("declarationSha256", pin.declarationSha256());
            node.put("pinnedAt", pin.pinnedAt());
            node.put("profileCount", pin.profileCount());
            Files.writeString(pinFile(workspaceId),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to write workspace-profile trust pin: " + e.getMessage(), e);
        }
        return pin;
    }

    /** Remove a recorded pin (e.g. user revokes trust). Missing pin is a no-op. */
    public void unpin(Path workspace) {
        try {
            Files.deleteIfExists(pinFile(workspaceId(workspace)));
        } catch (Exception ignored) {
            // Best effort — an undeletable pin keeps the stricter state only
            // if the bytes still match; nothing fails open.
        }
    }

    /** A corrupted, mismatched, or unreadable pin reads as absent — fail closed. */
    public Optional<Pin> readPin(String workspaceId) {
        Path file = pinFile(workspaceId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            int schemaVersion = node.path("schemaVersion").asInt(-1);
            String pinnedWorkspace = node.path("workspaceId").asText("");
            String sha = node.path("declarationSha256").asText("");
            if (schemaVersion != SCHEMA_VERSION
                    || !pinnedWorkspace.equals(workspaceId)
                    || sha.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Pin(
                    schemaVersion,
                    pinnedWorkspace,
                    sha,
                    node.path("pinnedAt").asText(""),
                    node.path("profileCount").asInt(0)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Path pinFile(String workspaceId) {
        return trustDir.resolve(workspaceId + ".json");
    }
}
