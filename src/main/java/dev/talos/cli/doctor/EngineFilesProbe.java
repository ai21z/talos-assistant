package dev.talos.cli.doctor;

import dev.talos.core.EngineRuntimeConfig;
import dev.talos.engine.llamacpp.LlamaCppPreflight;

/**
 * Verifies the managed llama.cpp files statically - server binary present,
 * model file present, GGUF architecture supported - without starting
 * anything. Delegates to the same validation the engine runs before launch.
 */
public final class EngineFilesProbe implements DoctorProbe {

    @Override
    public String id() {
        return "engine-files";
    }

    @Override
    public ProbeResult run(DoctorContext ctx) {
        String backend = EngineRuntimeConfig.from(ctx.cfg()).backend();
        if (!"llama_cpp".equals(backend)) {
            return ProbeResult.skip(id(),
                    "backend '" + backend + "' has no managed files to verify");
        }
        LlamaCppPreflight.Report report = LlamaCppPreflight.check(ctx.cfg());
        if (!report.managed()) {
            return ProbeResult.skip(id(),
                    "connect-only mode (no managed server binary or model file)");
        }
        if (!report.filesOk()) {
            return ProbeResult.fail(id(), report.validationFailure(),
                    "run 'talos setup models' or fix engines.llama_cpp paths in ~/.talos/config.yaml");
        }
        return ProbeResult.pass(id(), "server binary and model file present");
    }
}
