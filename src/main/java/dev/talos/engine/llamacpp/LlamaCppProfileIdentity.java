package dev.talos.engine.llamacpp;

import dev.talos.spi.EngineConfig;
import dev.talos.spi.types.ModelIdentitySnapshot;

import java.nio.file.Path;

/** Read-only entry point for bounded llama.cpp identity diagnostics outside the engine package. */
public final class LlamaCppProfileIdentity {
    private LlamaCppProfileIdentity() {}

    /** Resolves cached local facts without contacting or starting the server. */
    public static ModelIdentitySnapshot inspectLocal(EngineConfig config, Path talosHome) {
        return inspect(config, talosHome, false);
    }

    /** Adds a bounded loopback GET /props observation without starting the server. */
    public static ModelIdentitySnapshot inspectAvailable(EngineConfig config, Path talosHome) {
        return inspect(config, talosHome, true);
    }

    private static ModelIdentitySnapshot inspect(EngineConfig engineConfig, Path talosHome, boolean queryServer) {
        LlamaCppConfig config;
        try {
            config = LlamaCppConfig.from(engineConfig);
            LlamaCppResolvedProfileService service = new LlamaCppResolvedProfileService(config, talosHome);
            return (queryServer ? service.inspectAvailable() : service.inspectLocal()).identity();
        } catch (RuntimeException error) {
            String configured = "";
            int context = 0;
            try {
                config = LlamaCppConfig.from(engineConfig);
                configured = config.model();
                context = config.context();
            } catch (RuntimeException ignored) {
                // The bounded fallback below intentionally carries no exception text.
            }
            return ModelIdentitySnapshot.builder()
                    .backend(LlamaCppEngine.BACKEND)
                    .configuredModel(configured)
                    .resolution("UNVERIFIED")
                    .effectiveToolMode("CHAT_ONLY")
                    .effectiveContextTokens(context)
                    .propsState("UNAVAILABLE")
                    .reasonCodes(java.util.List.of("IDENTITY_INSPECTION_FAILED"))
                    .build();
        }
    }
}
