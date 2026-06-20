package dev.talos.safety;

import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.policy.ResourceDecision;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedWorkspacePathsTest {

    @TempDir
    Path workspace;

    @Test
    void policyVersionIsV7_sinceT840UnresolvedShortNameSegmentsFailClosed() {
        // Typed literal on purpose (never constant-vs-constant): bumping the
        // version is a deliberate act that forces stale RAG privacy
        // partitions to rebuild, and this pin makes the bump reviewable.
        assertEquals("protected-content-policy-v7", ProtectedWorkspacePaths.POLICY_VERSION);
    }

    @Test
    void workspaceTalosDeclarationsClassifyProtected() {
        ProtectedWorkspacePaths.Decision decision =
                ProtectedWorkspacePaths.classify(workspace, ".talos/profiles.yaml");

        assertTrue(decision.protectedPath());
        assertEquals("CONTROL", decision.protectedKind());
    }

    @Test
    void direct_classifier_matches_runtime_path_policy_for_workspace_paths() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SECRET=redacted\n");

        for (String rawPath : List.of(
                ".env",
                " .env",
                "docs/environment.md",
                "../outside/.env",
                ".git/config",
                "protected/private-notes.md")) {
            ProtectedWorkspacePaths.Decision direct = ProtectedWorkspacePaths.classify(workspace, rawPath);
            ResourceDecision runtime = ProtectedPathPolicy.classify(workspace, rawPath);

            assertEquals(runtime.rawPath(), direct.rawPath(), rawPath);
            assertEquals(runtime.relativePath(), direct.relativePath(), rawPath);
            assertEquals(runtime.hasPath(), direct.hasPath(), rawPath);
            assertEquals(runtime.insideWorkspace(), direct.insideWorkspace(), rawPath);
            assertEquals(runtime.workspaceEscape(), direct.workspaceEscape(), rawPath);
            assertEquals(runtime.protectedPath(), direct.protectedPath(), rawPath);
            assertEquals(runtime.protectedKind(), direct.protectedKind(), rawPath);
        }
    }

    @Test
    void windowsAliasPathsClassifyAsProtectedThroughRuntimePolicy() throws Exception {
        Files.writeString(workspace.resolve("id_rsa"), "PRIVATE KEY\n");
        Files.createDirectories(workspace.resolve(".ssh"));
        Files.writeString(workspace.resolve(".ssh").resolve("config"), "Host example\n");
        Files.createDirectories(workspace.resolve("secrets"));
        Files.writeString(workspace.resolve("secrets").resolve("api.txt"), "token\n");
        Files.createDirectories(workspace.resolve("keys"));
        Files.writeString(workspace.resolve("keys").resolve("server.pem"), "PRIVATE KEY\n");

        for (String rawPath : List.of(
                "id_rsa.",
                "id_rsa ",
                "id_rsa. ",
                ".ssh./config",
                ".ssh /config",
                "secrets./api.txt",
                "secrets /api.txt",
                "keys/server.pem.",
                "con",
                "nul.txt")) {
            ProtectedWorkspacePaths.Decision direct = ProtectedWorkspacePaths.classify(workspace, rawPath);
            ResourceDecision runtime = ProtectedPathPolicy.classify(workspace, rawPath);

            assertTrue(direct.protectedPath(), rawPath);
            assertEquals(runtime.protectedPath(), direct.protectedPath(), rawPath);
            assertEquals(runtime.protectedKind(), direct.protectedKind(), rawPath);
        }
    }

    @Test
    void windowsShortNameAliasesClassifyByRealProtectedTarget() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "NTFS 8.3 aliases are Windows-specific");

        Files.createDirectories(workspace.resolve(".ssh"));
        Files.writeString(workspace.resolve(".ssh").resolve("mykey"), "PRIVATE KEY\n");
        Files.createDirectories(workspace.resolve(".aws"));
        Files.writeString(workspace.resolve(".aws").resolve("config"), "aws_secret_access_key = redacted\n");
        Files.createDirectories(workspace.resolve(".azure"));
        Files.writeString(workspace.resolve(".azure").resolve("profile.json"), "{}\n");

        var aliases = List.of(
                "SSH~1/mykey",
                "AWS~1/config",
                "AZURE~1/profile.json");
        Assumptions.assumeTrue(aliases.stream().anyMatch(alias -> Files.exists(workspace.resolve(alias))),
                "NTFS 8.3 aliases are not available on this filesystem");

        for (String rawPath : aliases) {
            if (!Files.exists(workspace.resolve(rawPath))) {
                continue;
            }
            ProtectedWorkspacePaths.Decision direct = ProtectedWorkspacePaths.classify(workspace, rawPath);
            ResourceDecision runtime = ProtectedPathPolicy.classify(workspace, rawPath);

            assertTrue(direct.protectedPath(), rawPath);
            assertEquals(runtime.protectedPath(), direct.protectedPath(), rawPath);
            assertEquals(runtime.protectedKind(), direct.protectedKind(), rawPath);
        }

        ProtectedWorkspacePaths.Decision newFile = ProtectedWorkspacePaths.classify(workspace, "SSH~1/new-key.txt");
        ResourceDecision runtime = ProtectedPathPolicy.classify(workspace, "SSH~1/new-key.txt");

        assertTrue(newFile.protectedPath(), "new file under SSH~1");
        assertEquals(runtime.protectedPath(), newFile.protectedPath(), "new file under SSH~1");
        assertEquals(runtime.protectedKind(), newFile.protectedKind(), "new file under SSH~1");
    }

    @Test
    void unresolvedWindowsShortNameSegmentsFailClosedInsideWorkspace() {
        ProtectedWorkspacePaths.Decision direct =
                ProtectedWorkspacePaths.classify(workspace, "SSH~1/new-key.txt");
        ResourceDecision runtime = ProtectedPathPolicy.classify(workspace, "SSH~1/new-key.txt");

        assertTrue(direct.hasPath());
        assertTrue(direct.insideWorkspace());
        assertFalse(direct.workspaceEscape());
        assertTrue(direct.protectedPath(), "unresolved short-name segments fail closed");
        assertEquals("CONTROL", direct.protectedKind());
        assertEquals("SSH~1/new-key.txt", direct.relativePath());
        assertEquals(runtime.rawPath(), direct.rawPath());
        assertEquals(runtime.relativePath(), direct.relativePath());
        assertEquals(runtime.hasPath(), direct.hasPath());
        assertEquals(runtime.insideWorkspace(), direct.insideWorkspace());
        assertEquals(runtime.workspaceEscape(), direct.workspaceEscape());
        assertEquals(runtime.protectedPath(), direct.protectedPath());
        assertEquals(runtime.protectedKind(), direct.protectedKind());
        assertTrue(ProtectedWorkspacePaths.isProtectedPath(
                workspace,
                workspace.resolve("SSH~1").resolve("new-key.txt")));
    }

    @Test
    void unresolvedShortNameGuardCoversDosShortNameSpecialCharacters() {
        for (String rawPath : List.of(
                "ID_ED2~1/mykey",
                "MY-KEY~1/config",
                "$CACHE~1/value.txt",
                "USER@1~1/value.txt")) {
            ProtectedWorkspacePaths.Decision direct =
                    ProtectedWorkspacePaths.classify(workspace, rawPath);
            ResourceDecision runtime = ProtectedPathPolicy.classify(workspace, rawPath);

            assertTrue(direct.hasPath(), rawPath);
            assertTrue(direct.insideWorkspace(), rawPath);
            assertFalse(direct.workspaceEscape(), rawPath);
            assertTrue(direct.protectedPath(), rawPath);
            assertEquals("CONTROL", direct.protectedKind(), rawPath);
            assertEquals(runtime.protectedPath(), direct.protectedPath(), rawPath);
            assertEquals(runtime.protectedKind(), direct.protectedKind(), rawPath);
            assertTrue(ProtectedWorkspacePaths.isProtectedPath(
                    workspace,
                    workspace.resolve(rawPath)));
        }
    }

    @Test
    void unresolvedShortNameGuardDoesNotAffectOrdinarySafePaths() {
        ProtectedWorkspacePaths.Decision normal =
                ProtectedWorkspacePaths.classify(workspace, "docs/notes.md");
        ProtectedWorkspacePaths.Decision tildeName =
                ProtectedWorkspacePaths.classify(workspace, "docs/notes~draft.md");
        ProtectedWorkspacePaths.Decision shaName =
                ProtectedWorkspacePaths.classify(workspace,
                        "docs/0123456789abcdef0123456789abcdef01234567.txt");

        assertFalse(normal.protectedPath());
        assertFalse(tildeName.protectedPath());
        assertFalse(shaName.protectedPath());
        assertFalse(ProtectedWorkspacePaths.isProtectedPath(
                workspace,
                workspace.resolve("docs").resolve("notes~draft.md")));
    }

    @Test
    void concrete_path_helper_identifies_only_protected_paths_inside_workspace() throws Exception {
        Path env = workspace.resolve(".env");
        Path notes = workspace.resolve("docs/notes.md");
        Files.createDirectories(notes.getParent());
        Files.writeString(env, "SECRET=redacted\n");
        Files.writeString(notes, "normal notes\n");

        assertTrue(ProtectedWorkspacePaths.isProtectedPath(workspace, env));
        assertFalse(ProtectedWorkspacePaths.isProtectedPath(workspace, notes));
        assertFalse(ProtectedWorkspacePaths.isProtectedPath(workspace, workspace.resolveSibling(".env")));
    }
}
