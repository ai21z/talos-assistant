package dev.talos.cli.doctor;

import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.engine.llamacpp.LlamaCppProfileIdentity;

import dev.talos.spi.types.ModelIdentitySnapshot;

/** Verifies the configured backend resolves to a provider and a model is set. */
public final class EngineProfileProbe implements DoctorProbe {
    private final boolean runtimeVerificationFollows;

    public EngineProfileProbe() {
        this(false);
    }

    EngineProfileProbe(boolean runtimeVerificationFollows) {
        this.runtimeVerificationFollows = runtimeVerificationFollows;
    }

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
                    "check llm.default_backend, llm.model, TALOS_BACKEND and TALOS_LLM_BACKEND, "
                            + "or run talos setup models");
        }
        if (model == null || model.isBlank()) {
            return ProbeResult.warn(id(),
                    "backend '" + backend + "' resolves but no model is configured");
        }
        ModelIdentitySnapshot identity = LlamaCppProfileIdentity.inspectLocal(ctx.cfg(), ctx.talosHome());
        ModelIdentityDoctorAssessment assessment = ModelIdentityDoctorAssessment.assess(identity);
        String detail = "backend " + backend + ", model " + model + "; " + assessment.detail();
        if (assessment.status() == ProbeResult.Status.FAIL) {
            return ProbeResult.fail(id(), detail,
                    "run 'talos setup models' or correct the configured model and engine paths");
        }
        if (assessment.status() == ProbeResult.Status.WARN && runtimeVerificationFollows) {
            return ProbeResult.pass(id(), "backend " + backend + ", model " + model
                    + "; runtime identity verification follows in the server probe");
        }
        return assessment.status() == ProbeResult.Status.PASS
                ? ProbeResult.pass(id(), detail)
                : ProbeResult.warn(id(), detail);
    }
}
