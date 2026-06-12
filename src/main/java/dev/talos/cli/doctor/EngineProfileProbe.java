package dev.talos.cli.doctor;

import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.engine.EngineRegistry;

/** Verifies the configured backend resolves to a provider and a model is set. */
public final class EngineProfileProbe implements DoctorProbe {

    @Override
    public String id() {
        return "engine-profile";
    }

    @Override
    public ProbeResult run(DoctorContext ctx) {
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(ctx.cfg());
        String backend = runtime.backend();
        String model = runtime.model();
        try (EngineRegistry registry = new EngineRegistry(ctx.cfg())) {
            registry.select(backend, model);
        } catch (Exception e) {
            return ProbeResult.fail(id(),
                    "backend '" + backend + "' does not resolve: " + e.getMessage(),
                    "check llm.default_backend in ~/.talos/config.yaml");
        }
        if (model == null || model.isBlank()) {
            return ProbeResult.warn(id(),
                    "backend '" + backend + "' resolves but no model is configured");
        }
        return ProbeResult.pass(id(), "backend " + backend + ", model " + model);
    }
}
