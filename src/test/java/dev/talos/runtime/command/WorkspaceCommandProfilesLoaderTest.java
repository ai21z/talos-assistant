package dev.talos.runtime.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T789: the workspace profile declaration validates fail-closed — one bad
 * profile rejects the whole file with one human-readable reason, unknown
 * keys are rejected (typos cannot silently default), and nothing the
 * declaration says can weaken the non-declarable gates.
 */
class WorkspaceCommandProfilesLoaderTest {

    @TempDir Path tempDir;

    @Test
    void missingDeclarationIsNoneDeclared() {
        var loaded = WorkspaceCommandProfilesLoader.load(workspace());

        assertFalse(loaded.profiles().declared());
        assertTrue(loaded.profiles().profiles().isEmpty());
        assertEquals("", loaded.declarationSha256());
    }

    @Test
    void validDeclarationLoadsWsPrefixedProfilesWithHardenedDefaults() throws IOException {
        Path ws = workspace();
        Path wrapper = ws.resolve("gradlew.bat");
        Files.writeString(wrapper, "rem wrapper", StandardCharsets.UTF_8);
        declare(ws, """
                profiles:
                  - id: check
                    executable: ./gradlew.bat
                    args: ["--no-daemon", "check"]
                    timeout_ms: 300000
                    expected_writes: ["build/"]
                  - id: unit
                    executable: npm
                    args: ["test"]
                """);

        var loaded = WorkspaceCommandProfilesLoader.load(ws);

        assertTrue(loaded.profiles().valid(), loaded.profiles().rejectionReason());
        assertEquals(2, loaded.profiles().profiles().size());
        assertEquals(64, loaded.declarationSha256().length());

        CommandProfile check = loaded.profiles().profiles().get(0);
        assertEquals("ws:check", check.id());
        assertEquals("workspace check", check.displayName());
        assertEquals(wrapper.toAbsolutePath().normalize().toString(), check.executable(),
                "workspace-relative executables register as their resolved absolute path");
        assertEquals(java.util.List.of("--no-daemon", "check"), check.fixedArgs());
        assertEquals(300_000, check.defaultTimeoutMs());
        assertEquals(CommandRisk.BUILD_OR_TEST, check.risk());
        assertTrue(check.requiresApproval(), "approval is non-declarable and always on");
        assertFalse(check.networkAccess());
        assertFalse(check.interactive());
        assertFalse(check.requiresCheckpoint());

        CommandProfile unit = loaded.profiles().profiles().get(1);
        assertEquals("ws:unit", unit.id());
        assertEquals("npm", unit.executable(), "bare names stay PATH-resolved");
    }

    @Test
    void unknownKeysRejectTheWholeFile() throws IOException {
        Path ws = workspace();
        declare(ws, """
                profiles:
                  - id: check
                    executable: npm
                    requires_approval: false
                """);

        var loaded = WorkspaceCommandProfilesLoader.load(ws);

        assertFalse(loaded.profiles().valid());
        assertTrue(loaded.profiles().rejectionReason().contains("unknown key `requires_approval`"),
                loaded.profiles().rejectionReason());
        assertTrue(loaded.profiles().rejectionReason().contains("not declarable"),
                loaded.profiles().rejectionReason());
    }

    @Test
    void shellSyntaxInArgsRejects() throws IOException {
        Path ws = workspace();
        declare(ws, """
                profiles:
                  - id: check
                    executable: npm
                    args: ["test && curl evil"]
                """);

        var loaded = WorkspaceCommandProfilesLoader.load(ws);

        assertFalse(loaded.profiles().valid());
        assertTrue(loaded.profiles().rejectionReason().contains("shell syntax"),
                loaded.profiles().rejectionReason());
    }

    @Test
    void relativeExecutableMustExistInsideTheWorkspace() throws IOException {
        Path ws = workspace();
        declare(ws, """
                profiles:
                  - id: check
                    executable: ./missing-wrapper.sh
                """);

        var loaded = WorkspaceCommandProfilesLoader.load(ws);

        assertFalse(loaded.profiles().valid());
        assertTrue(loaded.profiles().rejectionReason().contains("executable not found"),
                loaded.profiles().rejectionReason());
    }

    @Test
    void relativeExecutableEscapingTheWorkspaceRejects() throws IOException {
        Path ws = workspace();
        Files.writeString(tempDir.resolve("outside.bat"), "rem outside", StandardCharsets.UTF_8);
        declare(ws, """
                profiles:
                  - id: check
                    executable: ../outside.bat
                """);

        var loaded = WorkspaceCommandProfilesLoader.load(ws);

        assertFalse(loaded.profiles().valid());
        assertTrue(loaded.profiles().rejectionReason().contains("escapes the workspace"),
                loaded.profiles().rejectionReason());
    }

    @Test
    void badIdDuplicateIdAndTooManyProfilesReject() throws IOException {
        Path ws = workspace();
        declare(ws, """
                profiles:
                  - id: "Bad Id!"
                    executable: npm
                """);
        assertTrue(WorkspaceCommandProfilesLoader.load(ws).profiles()
                .rejectionReason().contains("id must match"));

        declare(ws, """
                profiles:
                  - id: check
                    executable: npm
                  - id: check
                    executable: npm
                """);
        assertTrue(WorkspaceCommandProfilesLoader.load(ws).profiles()
                .rejectionReason().contains("duplicate profile id"));

        StringBuilder many = new StringBuilder("profiles:\n");
        for (int i = 0; i < 9; i++) {
            many.append("  - id: p").append(i).append("\n    executable: npm\n");
        }
        declare(ws, many.toString());
        assertTrue(WorkspaceCommandProfilesLoader.load(ws).profiles()
                .rejectionReason().contains("at most 8 profiles"));
    }

    @Test
    void timeoutIsClampedIntoTheRunCommandWindow() throws IOException {
        Path ws = workspace();
        declare(ws, """
                profiles:
                  - id: slow
                    executable: npm
                    timeout_ms: 99999999
                  - id: fast
                    executable: npm
                    timeout_ms: 1
                """);

        var loaded = WorkspaceCommandProfilesLoader.load(ws);

        assertTrue(loaded.profiles().valid(), loaded.profiles().rejectionReason());
        assertEquals(CommandToolPlanner.MAX_TIMEOUT_MS,
                loaded.profiles().profiles().get(0).defaultTimeoutMs());
        assertEquals(CommandToolPlanner.MIN_TIMEOUT_MS,
                loaded.profiles().profiles().get(1).defaultTimeoutMs());
    }

    @Test
    void expectedWritesEscapingTheWorkspaceRejects() throws IOException {
        Path ws = workspace();
        declare(ws, """
                profiles:
                  - id: check
                    executable: npm
                    expected_writes: ["../elsewhere/"]
                """);

        assertTrue(WorkspaceCommandProfilesLoader.load(ws).profiles()
                .rejectionReason().contains("escapes the workspace"));
    }

    @Test
    void unparseableYamlAndOversizeFilesRejectNeverThrow() throws IOException {
        Path ws = workspace();
        declare(ws, "profiles: [unclosed");
        assertTrue(WorkspaceCommandProfilesLoader.load(ws).profiles()
                .rejectionReason().contains("YAML parse failed"));

        declare(ws, "# big\n" + "x".repeat(70 * 1024));
        assertTrue(WorkspaceCommandProfilesLoader.load(ws).profiles()
                .rejectionReason().contains("exceeds"));
    }

    @Test
    void sameBytesHashIdenticallyDifferentBytesDoNot() throws IOException {
        Path ws = workspace();
        declare(ws, "profiles:\n  - id: check\n    executable: npm\n");
        String first = WorkspaceCommandProfilesLoader.load(ws).declarationSha256();
        String second = WorkspaceCommandProfilesLoader.load(ws).declarationSha256();
        declare(ws, "profiles:\n  - id: check\n    executable: npm \n");

        assertEquals(first, second);
        assertFalse(first.equals(WorkspaceCommandProfilesLoader.load(ws).declarationSha256()),
                "any byte change (even trailing whitespace) must change the hash");
    }

    private Path workspace() {
        Path ws = tempDir.resolve("ws-" + System.nanoTime());
        try {
            Files.createDirectories(ws.resolve(".talos"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return ws;
    }

    private static void declare(Path workspace, String yaml) throws IOException {
        Files.writeString(workspace.resolve(".talos").resolve("profiles.yaml"),
                yaml, StandardCharsets.UTF_8);
    }
}
