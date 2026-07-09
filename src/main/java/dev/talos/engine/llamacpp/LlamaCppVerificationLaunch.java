package dev.talos.engine.llamacpp;

import dev.talos.core.CfgUtil;
import dev.talos.spi.EngineConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Marks an in-memory engine config as a verification launch. Verification
 * runs ({@code talos doctor --start}, and {@code talos tune} through it)
 * start the managed llama.cpp server with debug log verbosity so offload
 * and rate evidence lands in the server log. Ordinary sessions must NOT
 * run at debug verbosity: llama.cpp debug logs write prompt and workspace
 * content into the plaintext log file. The marker lives only in the
 * in-memory config of the verification run and is never written to the
 * user's config file.
 */
public final class LlamaCppVerificationLaunch {

    /** Config key under {@code engines.llama_cpp}; programmatic only. */
    static final String KEY = "verification_logging";

    private LlamaCppVerificationLaunch() {}

    public static void mark(EngineConfig cfg) {
        if (cfg == null || cfg.data() == null) return;
        Map<String, Object> engines = new LinkedHashMap<>(CfgUtil.map(cfg.data().get("engines")));
        Map<String, Object> block = new LinkedHashMap<>(CfgUtil.map(engines.get("llama_cpp")));
        block.put(KEY, true);
        engines.put("llama_cpp", block);
        cfg.data().put("engines", engines);
    }
}
