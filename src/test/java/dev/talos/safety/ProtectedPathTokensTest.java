package dev.talos.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T759 characterization matrix for the canonical protected-path classifier.
 *
 * <p>This commit pins CURRENT (substring-matching) behavior, including the
 * known false positives the 2026-06-10 evaluation flagged (tokenizer.java
 * et al.). The consolidation commit flips the marked expectations to the
 * equals-or-suffix word-run rule - the test diff IS the behavioral-delta
 * documentation.
 */
class ProtectedPathTokensTest {

    // ── T788: the workspace .talos directory is a CONTROL path ─────────
    // (T787 pinned the pre-existing gap: these all classified "".) The
    // wave-4 verification-profile declaration lives at
    // <workspace>/.talos/profiles.yaml and template commands under
    // .talos/commands/ - content that influences what Talos executes, so
    // the model cannot write it with an ordinary write approval anymore.

    @Test
    void workspaceTalosDirIsAControlPath_sinceT788() {
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind(".talos"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind(".talos/profiles.yaml"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind(".talos/commands/review.md"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind("sub/.talos/profiles.yaml"));
        // Names merely containing the word talos stay unprotected.
        assertEquals("", ProtectedPathTokens.protectedKind("talos"));
        assertEquals("", ProtectedPathTokens.protectedKind("docs/talos-notes.md"));
        assertEquals("", ProtectedPathTokens.protectedKind("my.talos.txt"));
    }

    // ── exact-rule families: identical before and after T759 ───────────

    @Test
    void exactSegmentAndExtensionRulesAreProtected() {
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".env"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".env.local"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("config/prod.env"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("secrets/api.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("tokens/jwt.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("credentials/db.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("protected/private-notes.md"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".ssh/config"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".aws/config"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".azure/profile"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".kube/config"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".docker/config.json"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".npmrc"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".netrc"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("id_rsa"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("id_ed25519_sk"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("keys/server.pem"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("keys/deploy.ppk"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("certs/tls.key"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("store.p12"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("bundle.pfx"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".config/gcloud/properties"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind(".git/config"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind(".gnupg/pubring.kbx"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind(".github/workflows/ci.yml"));
    }

    @Test
    void windowsAliasSegmentsAreCanonicalizedBeforeProtectedMatching() {
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".env."));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".env "));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".env..."));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("id_rsa."));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("id_rsa "));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("id_rsa. "));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".ssh./config"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind(".ssh /config"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("secrets./api.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("secrets /api.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("keys/server.pem."));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind(".git./config"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind(".github./workflows/ci.yml"));
    }

    @Test
    void windowsReservedDeviceNamesAreControlPaths() {
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind("con"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind("nul.txt"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind("aux."));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind("com1.log"));
        assertEquals("CONTROL", ProtectedPathTokens.protectedKind("lpt9 "));
        assertEquals("", ProtectedPathTokens.protectedKind("company-notes.md"));
        assertEquals("", ProtectedPathTokens.protectedKind("complete.txt"));
    }

    @Test
    void secretBearingNamesAreProtected() {
        // Names whose word runs END with a vocabulary stem stay protected
        // before and after T759 (the equals-or-suffix rule keeps them).
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("api_token.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("auth-tokens.json"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("secret_config.yaml"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("mysecrets.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("supersecret.conf"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("accesstoken.java"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("user-credentials.db"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("passwords.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("password123.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("private_key.txt"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("my-private-key.bak"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("docs/my_tokens.txt"));
    }

    @Test
    void ordinaryNamesAreNotProtected() {
        assertEquals("", ProtectedPathTokens.protectedKind("environment.md"));
        assertEquals("", ProtectedPathTokens.protectedKind("readme.md"));
        assertEquals("", ProtectedPathTokens.protectedKind("src/app.java"));
        assertEquals("", ProtectedPathTokens.protectedKind("styles.css"));
        assertEquals("", ProtectedPathTokens.protectedKind("monkey.txt"));
        assertEquals("", ProtectedPathTokens.protectedKind("keyboard-shortcuts.md"));
    }

    // ── derivational-suffix names: the T759 deltas ──────────────────────
    // Substring matching classified ALL of these as SECRET (the evaluation's
    // false positives). The equals-or-suffix word-run rule frees them: the
    // stem is a prefix of the run, not its suffix.

    @Test
    void derivationalSuffixNamesAreNoLongerProtected() {
        assertEquals("", ProtectedPathTokens.protectedKind("tokenizer.java"));
        assertEquals("", ProtectedPathTokens.protectedKind("src/tokenizer.py"));
        assertEquals("", ProtectedPathTokens.protectedKind("tokenize.rs"));
        assertEquals("", ProtectedPathTokens.protectedKind("untokenized_data.csv"));
        assertEquals("", ProtectedPathTokens.protectedKind("tokenization-notes.md"));
        assertEquals("", ProtectedPathTokens.protectedKind("secretary-notes.md"));
        assertEquals("", ProtectedPathTokens.protectedKind("secretariat.txt"));
        assertEquals("", ProtectedPathTokens.protectedKind("passwordless-ssh.md"));
        assertEquals("", ProtectedPathTokens.protectedKind("credentialing.md"));
    }

    // ── fail-closed readback sensitivity (replaces 4 planner copies) ────

    @Test
    void readbackSensitivityFailsClosedOnBlankAndCoversPlannerVocabulary() {
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(null));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath("  "));
        // The four former planner copies' vocabulary, all covered:
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(".env"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(".env.local"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(".git/config"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(".ssh/known_hosts"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(".gnupg/trustdb.gpg"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(".kube/config"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(".docker/config.json"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(".npmrc"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath(".netrc"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath("backup/id_rsa"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath("backup/id_ed25519_sk"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath("keys/deploy.ppk"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath("credentials/aws.txt"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath("club-secret.md"));
        // Fail-closed expansion vs the old copies (intended): the full
        // canonical vocabulary now applies to readback sensitivity too.
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath("passwords.txt"));
        assertTrue(ProtectedPathTokens.isSensitiveReadbackPath("server.pem"));
        // Ordinary repair targets stay inlineable.
        assertFalse(ProtectedPathTokens.isSensitiveReadbackPath("index.html"));
        assertFalse(ProtectedPathTokens.isSensitiveReadbackPath("src/tokenizer.py"));
    }

    @Test
    void knownRemainingLimitationLexerTokenSourceFiles() {
        // "Token.java" has the literal word run - it stays protected before
        // and after T759. A source-extension exemption is a separate policy
        // decision, out of scope (documented in the ticket).
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("token.java"));
        assertEquals("SECRET", ProtectedPathTokens.protectedKind("jsontoken.java"));
    }

    // ── sink-redaction consumer (SafeLogFormatter) follows the classifier ─

    @Test
    void logRedactionFollowsTheCanonicalClassifier() {
        // api_token.txt: still redacted; tokenizer.java: no longer redacted
        // to <protected-path> (T759 delta visible at the log sink).
        assertEquals("reading <protected-path> now",
                SafeLogFormatter.value("reading api_token.txt now"));
        assertEquals("reading tokenizer.java now",
                SafeLogFormatter.value("reading tokenizer.java now"));
    }

    // ── looksProtectedPathToken normalization ──────────────────────────

    @Test
    void looksProtectedPathTokenNormalizesQuotesSlashesAndCase() {
        assertTrue(ProtectedPathTokens.looksProtectedPathToken("\".ENV\""));
        assertTrue(ProtectedPathTokens.looksProtectedPathToken(".\\secrets\\api.txt"));
        assertTrue(ProtectedPathTokens.looksProtectedPathToken("./id_rsa"));
        assertTrue(ProtectedPathTokens.looksProtectedPathToken("./id_rsa."));
        assertTrue(ProtectedPathTokens.looksProtectedPathToken(".\\.ssh.\\config"));
        assertFalse(ProtectedPathTokens.looksProtectedPathToken("README.md"));
        assertFalse(ProtectedPathTokens.looksProtectedPathToken("  "));
        assertFalse(ProtectedPathTokens.looksProtectedPathToken(null));
    }
}
