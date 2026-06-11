package dev.talos.runtime.policy;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedPathPolicyTest {

    @TempDir
    Path workspace;

    @Test
    void classifiesSecretLikePathsWithWindowsSafeNormalization() {
        assertProtected(".env", "SECRET");
        assertProtected(".env.local", "SECRET");
        assertProtected("config/app.env", "SECRET");
        assertProtected("app/.env.production", "SECRET");
        assertProtected("config/secrets/api.txt", "SECRET");
        assertProtected("protected/private-notes.md", "SECRET");
        assertProtected("src/project-token.txt", "SECRET");
        assertProtected("src/passwords.txt", "SECRET");
        assertProtected("src/serviceCredential.json", "SECRET");
        assertProtected("keys/private.pem", "SECRET");
        assertProtected(".ssh/id_ed25519", "SECRET");
        assertProtected(".AWS/credentials", "SECRET");
        assertProtected(".config/gcloud/application_default_credentials.json", "SECRET");
        assertProtected("Secrets\\TOKEN.txt", "SECRET");
    }

    @Test
    void classifiesControlPlanePaths() {
        assertProtected(".git/config", "CONTROL");
        assertProtected(".github/workflows/ci.yml", "CONTROL");
        assertProtected(".gnupg/trustdb.gpg", "CONTROL");
    }

    @Test
    void doesNotOverTriggerNormalEnvironmentFiles() {
        ResourceDecision decision = ProtectedPathPolicy.classify(workspace, "docs/environment.md");

        assertTrue(decision.insideWorkspace());
        assertEquals("docs/environment.md", decision.relativePath());
        assertFalse(decision.protectedPath());
    }

    @Test
    void doesNotOverTriggerDerivationalSuffixSourceFiles() {
        // T759: equals-or-suffix word-run matching — the stem must end the
        // run. "tokenizer"/"secretary"/"passwordless" no longer classify;
        // "api_token"/"mysecrets" still do (suffix keeps them fail-closed).
        assertFalse(ProtectedPathPolicy.classify(workspace, "src/tokenizer.java").protectedPath());
        assertFalse(ProtectedPathPolicy.classify(workspace, "docs/secretary-notes.md").protectedPath());
        assertFalse(ProtectedPathPolicy.classify(workspace, "docs/passwordless-ssh.md").protectedPath());
        assertProtected("config/api_token.txt", "SECRET");
        assertProtected("notes/mysecrets.txt", "SECRET");
    }

    @Test
    void rejectsEscapingPathsBeforeRulesCanAllowThem() {
        ResourceDecision decision = ProtectedPathPolicy.classify(workspace, "../outside/.env");

        assertFalse(decision.insideWorkspace());
        assertTrue(decision.workspaceEscape());
        assertFalse(decision.protectedPath(), "workspace escape is its own hard denial reason");
    }

    @Test
    void classifiesTrimmedProtectedPathWhenRawWhitespacePathDoesNotExist() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SECRET=redacted\n");

        ResourceDecision decision = ProtectedPathPolicy.classify(workspace, " .env");

        assertTrue(decision.insideWorkspace());
        assertEquals(".env", decision.relativePath());
        assertTrue(decision.protectedPath());
        assertEquals("SECRET", decision.protectedKind());
    }

    private void assertProtected(String path, String expectedKind) {
        ResourceDecision decision = ProtectedPathPolicy.classify(workspace,
                new ToolCall("talos.write_file", Map.of("path", path, "content", "x")));

        assertTrue(decision.insideWorkspace(), path);
        assertTrue(decision.protectedPath(), path);
        assertEquals(expectedKind, decision.protectedKind(), path);
    }
}
