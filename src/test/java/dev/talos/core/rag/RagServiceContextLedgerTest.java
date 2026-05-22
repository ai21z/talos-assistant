package dev.talos.core.rag;

import dev.talos.core.Config;
import dev.talos.core.context.ContextLedgerCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagServiceContextLedgerTest {

    @AfterEach
    void clear() {
        ContextLedgerCapture.clear();
    }

    @Test
    void privateModeRagDisabledRecordsUnsupportedBoundaryDecision(@TempDir Path workspace) {
        Config cfg = new Config();
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "rag", new LinkedHashMap<>(Map.of("enabled_in_private_mode", false)))));
        ContextLedgerCapture.begin("trc-rag-private", 4);

        RagService.Prepared prepared = new RagService(cfg).prepare(workspace, "find project codename", 3);

        assertTrue(prepared.hasError(), "private-mode RAG should be refused");
        var snapshot = ContextLedgerCapture.snapshot();
        assertEquals(1, snapshot.summary().byBoundary().get("RAG_INDEX"));
        assertEquals(1, snapshot.summary().byDecision().get("EXCLUDED_BY_PRIVACY_OR_TRUST_POLICY"));
        assertEquals(1, snapshot.summary().byReason().get("PRIVATE_MODE_RAG_DISABLED"));
    }

    @Test
    void ragServiceUsesCorePrivacyFactsForPrivateModeRagOwnership() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/core/rag/RagService.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(source.contains("import dev.talos.core.privacy.PrivacyConfigFacts;"), source);
        assertFalse(source.contains("dev.talos.runtime.policy.ProtectedReadScopePolicy"), source);
        assertFalse(baseline.contains(
                        "core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java"
                                + "|dev.talos.runtime.policy.ProtectedReadScopePolicy"),
                baseline);
    }

    @Test
    void ragServiceUsesSafetyPrimitivesForProtectedContentOwnership() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/core/rag/RagService.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(source.contains("import dev.talos.safety.ProtectedContentSanitizer;"), source);
        assertTrue(source.contains("import dev.talos.safety.ProtectedWorkspacePaths;"), source);
        assertFalse(source.contains("dev.talos.runtime.policy.ProtectedContentPolicy"), source);
        assertFalse(baseline.contains(
                        "core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java"
                                + "|dev.talos.runtime.policy.ProtectedContentPolicy"),
                baseline);
    }

    @Test
    void ragServiceUsesCoreContextLedgerOwnership() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/core/rag/RagService.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(source.contains("import dev.talos.core.context.ContextDecision;"), source);
        assertTrue(source.contains("import dev.talos.core.context.ContextItem;"), source);
        assertTrue(source.contains("import dev.talos.core.context.ContextItemSource;"), source);
        assertTrue(source.contains("import dev.talos.core.context.ContextLedgerCapture;"), source);
        assertTrue(source.contains("import dev.talos.core.context.ExecutionBoundary;"), source);
        assertFalse(source.contains("import dev.talos.runtime.context.ContextDecision;"), source);
        assertFalse(source.contains("import dev.talos.runtime.context.ContextItem;"), source);
        assertFalse(source.contains("import dev.talos.runtime.context.ContextItemSource;"), source);
        assertFalse(source.contains("import dev.talos.runtime.context.ContextLedgerCapture;"), source);
        assertFalse(source.contains("import dev.talos.runtime.context.ExecutionBoundary;"), source);
        assertFalse(baseline.contains("src/main/java/dev/talos/core/rag/RagService.java|"
                + "dev.talos.runtime.context."), baseline);
    }
}
