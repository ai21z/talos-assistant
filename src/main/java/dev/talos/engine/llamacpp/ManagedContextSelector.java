package dev.talos.engine.llamacpp;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Selects a managed llama.cpp context window from bounded, explicit hardware facts. */
public final class ManagedContextSelector {
    public static final int DEFAULT_CONTEXT = 8_192;
    public static final int HIGH_CONTEXT = 16_384;
    public static final long QWEN_14B_KV_MB_AT_8192 = 1_536;
    public static final long QWEN_14B_KV_BYTES_PER_TOKEN = 192L * 1024L;

    private static final long CUDA_16K_MIN_VRAM_MB = 16_000;
    private static final long CPU_16K_MIN_SYSTEM_RAM_MB = 64_000;

    private ManagedContextSelector() {
    }

    public enum Lane {
        CPU,
        CUDA,
        UNKNOWN
    }

    public record Request(String profile, Lane lane, long vramTotalMb, long systemMemoryMb) {
        public Request {
            profile = Objects.toString(profile, "").trim();
            lane = lane == null ? Lane.UNKNOWN : lane;
        }
    }

    public record Decision(int context, String reason) {
        public Decision {
            context = context >= HIGH_CONTEXT ? HIGH_CONTEXT : DEFAULT_CONTEXT;
            reason = Objects.toString(reason, "").trim();
            if (reason.isBlank()) {
                reason = "8192 selected: no managed context headroom reason recorded";
            }
        }
    }

    public static Decision select(Request request) {
        Request safe = request == null ? new Request("", Lane.UNKNOWN, -1, -1) : request;
        if (!highContextProfile(safe.profile())) {
            return new Decision(DEFAULT_CONTEXT,
                    "8192 selected: unverified profile; 16384 context fit is not assumed");
        }
        return switch (safe.lane()) {
            case CUDA -> selectCuda(safe);
            case CPU -> selectCpu(safe);
            case UNKNOWN -> new Decision(DEFAULT_CONTEXT,
                    "8192 selected: server lane unknown; 16384 context fit is not assumed");
        };
    }

    // "cuda" must start a path token ("win-x64-cuda-13.3", "cuda13"), so a
    // username or directory merely containing the letters ("barracuda")
    // never grades a CPU binary on the CUDA VRAM floor.
    private static final java.util.regex.Pattern CUDA_PATH_TOKEN =
            java.util.regex.Pattern.compile("(?<![a-z0-9])cuda");

    public static Lane laneFromServerPath(Path serverPath) {
        String normalized = Objects.toString(serverPath, "")
                .toLowerCase(Locale.ROOT)
                .replace('\\', '/');
        if (CUDA_PATH_TOKEN.matcher(normalized).find()) return Lane.CUDA;
        if (normalized.isBlank()) return Lane.UNKNOWN;
        return Lane.CPU;
    }

    private static Decision selectCuda(Request request) {
        if (request.vramTotalMb() >= CUDA_16K_MIN_VRAM_MB) {
            return new Decision(HIGH_CONTEXT,
                    "16384 selected: estimated Qwen 14B KV cache doubles from "
                            + QWEN_14B_KV_MB_AT_8192
                            + " MiB at 8192 to about 3072 MiB on CUDA lane; "
                            + "VRAM total " + request.vramTotalMb() + " MiB meets 16 GB lane floor");
        }
        if (request.vramTotalMb() > 0) {
            return new Decision(DEFAULT_CONTEXT,
                    "8192 selected: VRAM total " + request.vramTotalMb()
                            + " MiB is below the 16 GB lane floor for estimated 16384 context");
        }
        return new Decision(DEFAULT_CONTEXT,
                "8192 selected: CUDA lane detected but VRAM was not measured; 16384 context fit is not assumed");
    }

    private static Decision selectCpu(Request request) {
        if (request.systemMemoryMb() >= CPU_16K_MIN_SYSTEM_RAM_MB) {
            return new Decision(HIGH_CONTEXT,
                    "16384 selected: estimated Qwen 14B KV cache doubles from "
                            + QWEN_14B_KV_MB_AT_8192
                            + " MiB at 8192 to about 3072 MiB; system RAM "
                            + request.systemMemoryMb() + " MiB meets CPU headroom floor");
        }
        if (request.systemMemoryMb() > 0) {
            return new Decision(DEFAULT_CONTEXT,
                    "8192 selected: system RAM " + request.systemMemoryMb()
                            + " MiB is below the CPU headroom floor for estimated 16384 context");
        }
        return new Decision(DEFAULT_CONTEXT,
                "8192 selected: system RAM was not measured; 16384 context fit is not assumed");
    }

    private static boolean highContextProfile(String profile) {
        String normalized = Objects.toString(profile, "").trim().toLowerCase(Locale.ROOT);
        return "qwen2.5-coder-14b".equals(normalized);
    }
}
