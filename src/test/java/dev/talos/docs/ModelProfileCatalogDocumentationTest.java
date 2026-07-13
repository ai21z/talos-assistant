package dev.talos.docs;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProfileCatalogDocumentationTest {

    @Test
    void publicModelProfileReferenceMatchesEverySelectableCatalogRecord() throws Exception {
        String docs = Files.readString(Path.of("docs/reference/model-profiles.md"));

        for (var profile : LlamaCppModelCatalog.selectableProfiles()) {
            assertTrue(docs.contains(profile.alias()), profile.alias());
            assertTrue(docs.contains(profile.repository()), profile.alias());
            assertTrue(docs.contains(profile.artifactFile()), profile.alias());
            assertTrue(docs.contains(profile.disposition().label()), profile.alias());
            assertTrue(docs.contains(profile.evidencePacket()), profile.alias());
        }
    }

    @Test
    void graniteReferencePinsExperimentalEvidenceAndUnsupportedLanes() throws Exception {
        var granite = LlamaCppModelCatalog.byAlias("granite-4.1-3b").orElseThrow();
        String docs = Files.readString(Path.of("docs/reference/model-profiles.md"));

        assertTrue(docs.contains(granite.revision()), docs);
        assertTrue(docs.contains(granite.artifactSha256()), docs);
        assertTrue(docs.contains(granite.evidencePacket()), docs);
        assertTrue(docs.contains("16 of 24 scenario definitions and 47 of 72 trials"), docs);
        assertTrue(docs.contains("Exact local GGUF path required"), docs);
        assertTrue(docs.contains("does not establish CPU-only, Linux, WSL, Vulkan, or weak-PC support"), docs);
    }
}
