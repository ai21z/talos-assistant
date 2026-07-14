package dev.talos.docs;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void qwen3FourBReferencePinsControlledExperimentalEvidenceAndUnsupportedLanes() throws Exception {
        var qwen = LlamaCppModelCatalog.byAlias("qwen3-4b").orElseThrow();
        String docs = Files.readString(Path.of("docs/reference/model-profiles.md"));

        assertTrue(docs.contains(qwen.revision()), docs);
        assertTrue(docs.contains(qwen.artifactSha256()), docs);
        assertTrue(docs.contains(qwen.evidencePacket()), docs);
        assertTrue(docs.contains("controlled local conversion"), docs);
        assertTrue(docs.contains("20 of 24 scenario definitions and 58 of 72 trials"), docs);
        assertTrue(docs.contains("47 of 54 required-tool decisions"), docs);
        assertTrue(docs.contains("Exact local GGUF path required"), docs);
        assertTrue(docs.contains("not available as a wizard download"), docs);
        assertTrue(docs.contains("does not establish CPU-only, Linux, WSL, Vulkan, or weak-PC support"), docs);
    }

    @Test
    void graniteEightBReferencePinsExperimentalEvidenceAndAvoidsUnsupportedClaims() throws Exception {
        var granite = LlamaCppModelCatalog.byAlias("granite-4.1-8b").orElseThrow();
        String docs = Files.readString(Path.of("docs/reference/model-profiles.md"));
        String section = section(docs, "### granite-4.1-8b");

        assertTrue(section.contains(granite.repository()), section);
        assertTrue(section.contains(granite.revision()), section);
        assertTrue(section.contains(granite.artifactFile()), section);
        assertTrue(section.contains(granite.artifactSha256()), section);
        assertTrue(section.contains(granite.evidencePacket()), section);
        assertTrue(section.contains("19 of 24 scenario definitions and 57 of 72 trials"), section);
        assertTrue(section.contains("110 of 129 requirements"), section);
        assertTrue(section.contains("51 of 54 required-tool decisions"), section);
        assertTrue(section.contains("Exact local GGUF path required"), section);
        assertTrue(section.contains("not available as a wizard download"), section);
        assertTrue(section.contains("does not establish CPU-only, Linux, WSL, Vulkan, or weak-PC support"),
                section);
        assertFalse(section.contains("accepted beta stability"), section);
        assertFalse(section.contains("default profile"), section);
        assertFalse(section.contains("low VRAM"), section);
        assertFalse(section.matches("(?s).*\\b6 GiB\\b.*"), section);
        assertFalse(section.contains("8 GiB GPU"), section);
    }

    private static String section(String docs, String heading) {
        int start = docs.indexOf(heading);
        assertTrue(start >= 0, heading);
        int next = docs.indexOf("\n### ", start + heading.length());
        return next < 0 ? docs.substring(start) : docs.substring(start, next);
    }
}
