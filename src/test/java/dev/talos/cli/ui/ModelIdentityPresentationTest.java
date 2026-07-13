package dev.talos.cli.ui;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelIdentityPresentationTest {

    @Test
    void qualifiedGraniteUsesTheCanonicalBannerAndStatusDisposition() {
        var profile = LlamaCppModelCatalog.byAlias("granite-4.1-3b").orElseThrow();
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel(profile.alias())
                .declaredProfile(profile.alias())
                .resolvedProfile(profile.alias())
                .artifactState("EXACT")
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveContextTokens(profile.runtime().contextTokens())
                .disposition(profile.disposition().label())
                .propsState("AVAILABLE")
                .chatAvailable(true)
                .build();

        assertEquals("granite-4.1-3b",
                ModelIdentityPresentation.bannerModelLabel(identity, "fallback"));
        assertTrue(ModelIdentityPresentation.statusRows(identity).stream()
                .anyMatch(row -> row.label().equals("Disposition")
                        && row.value().equals("experimental selectable")));
    }
}
