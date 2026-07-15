package dev.talos.cli.ui;

import dev.talos.engine.llamacpp.LlamaCppModelCatalog;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelIdentityPresentationTest {

    @Test
    void qualifiedGraniteUsesTheCanonicalBannerAndStatusDisposition() {
        for (String alias : java.util.List.of("granite-4.1-3b", "granite-4.1-8b")) {
            var profile = LlamaCppModelCatalog.byAlias(alias).orElseThrow();
            ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                    .backend("llama_cpp")
                    .configuredModel(profile.alias())
                    .declaredProfile(profile.alias())
                    .resolvedProfile(profile.alias())
                    .artifactState("EXACT")
                    .servedIdentityState("MATCHED")
                    .resolution("QUALIFIED")
                    .effectiveToolMode("NATIVE")
                    .effectiveRequestLayout(profile.runtime().requestLayout().name())
                    .effectiveContextTokens(profile.runtime().contextTokens())
                    .disposition(profile.disposition().label())
                    .propsState("AVAILABLE")
                    .chatAvailable(true)
                    .build();

            assertEquals(alias, ModelIdentityPresentation.bannerModelLabel(identity, "fallback"));
            assertTrue(ModelIdentityPresentation.statusRows(identity).stream()
                    .anyMatch(row -> row.label().equals("Disposition")
                            && row.value().equals("experimental selectable")));
            assertTrue(ModelIdentityPresentation.statusRows(identity).stream()
                    .anyMatch(row -> row.label().equals("Request layout")
                            && row.value().equals("preserve-as-written")));
        }
    }

    @Test
    void qualifiedQwen3FourBUsesTheCanonicalBannerAndStatusDisposition() {
        var profile = LlamaCppModelCatalog.byAlias("qwen3-4b").orElseThrow();
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

        assertEquals("qwen3-4b", ModelIdentityPresentation.bannerModelLabel(identity, "fallback"));
        assertTrue(ModelIdentityPresentation.statusRows(identity).stream()
                .anyMatch(row -> row.label().equals("Disposition")
                        && row.value().equals("experimental selectable")));
    }

    @Test
    void qualifiedQwen35UsesTheCanonicalBannerAndAdaptedLayout() {
        var profile = LlamaCppModelCatalog.byAlias("qwen3.5-9b").orElseThrow();
        ModelIdentitySnapshot identity = ModelIdentitySnapshot.builder()
                .backend("llama_cpp")
                .configuredModel(profile.alias())
                .declaredProfile(profile.alias())
                .resolvedProfile(profile.alias())
                .artifactState("EXACT")
                .servedIdentityState("MATCHED")
                .resolution("QUALIFIED")
                .effectiveToolMode("NATIVE")
                .effectiveRequestLayout(profile.runtime().requestLayout().name())
                .effectiveContextTokens(profile.runtime().contextTokens())
                .disposition(profile.disposition().label())
                .propsState("AVAILABLE")
                .chatAvailable(true)
                .build();

        assertEquals("qwen3.5-9b", ModelIdentityPresentation.bannerModelLabel(identity, "fallback"));
        assertTrue(ModelIdentityPresentation.statusRows(identity).stream()
                .anyMatch(row -> row.label().equals("Disposition")
                        && row.value().equals("experimental selectable")));
        assertTrue(ModelIdentityPresentation.statusRows(identity).stream()
                .anyMatch(row -> row.label().equals("Request layout")
                        && row.value().equals("single-leading-system")));
    }
}
