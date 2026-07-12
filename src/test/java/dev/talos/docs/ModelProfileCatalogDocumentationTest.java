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
}
