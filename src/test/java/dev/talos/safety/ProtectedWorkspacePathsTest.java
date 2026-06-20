package dev.talos.safety;

import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.policy.ResourceDecision;
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
    void policyVersionIsV5_sinceT836WindowsAliasCanonicalization() {
        // Typed literal on purpose (never constant-vs-constant): bumping the
        // version is a deliberate act that forces stale RAG privacy
        // partitions to rebuild, and this pin makes the bump reviewable.
        assertEquals("protected-content-policy-v5", ProtectedWorkspacePaths.POLICY_VERSION);
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
