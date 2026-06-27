package dev.talos.engine.llamacpp;

import dev.talos.spi.EngineConfig;
import dev.talos.spi.types.ModelRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * T877: safe, no-subprocess scan of the local Hugging Face cache for downloaded
 * GGUF model files. Lets {@code /models} surface GGUFs present on disk that are not
 * the configured/running managed model, so a user can SEE what they have downloaded.
 *
 * <p>Switching to one of these still requires {@code talos setup models --profile}
 * plus a restart (the managed engine binds one model at launch); this class only
 * makes them discoverable, it does not select them.
 *
 * <p>Never throws: a missing, unreadable, or huge cache yields an empty list so
 * {@code /models} can never crash on a filesystem error. The walk is depth-bounded
 * to stay cheap even if the cache directory is misconfigured.
 */
public final class GgufCacheScanner {

    // HF layout is <cache>/models--REPO/snapshots/HASH/FILE.gguf (depth 4 from the cache root); 6 adds slack.
    private static final int MAX_DEPTH = 6;

    private GgufCacheScanner() {}

    /**
     * Downloaded GGUFs under {@code hfCacheDir} as llama_cpp {@link ModelRef}s
     * (name = the GGUF filename without its {@code .gguf} suffix), deduped by name
     * and sorted case-insensitively. Empty if the directory is null or absent.
     */
    public static List<ModelRef> scanDownloaded(Path hfCacheDir) {
        if (hfCacheDir == null || !Files.isDirectory(hfCacheDir)) return List.of();
        Map<String, ModelRef> byName = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(hfCacheDir, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(GgufCacheScanner::isGguf)
                    .forEach(p -> {
                        String name = stem(p.getFileName().toString());
                        if (!name.isBlank()) {
                            byName.putIfAbsent(name.toLowerCase(Locale.ROOT),
                                    ModelRef.of(LlamaCppEngine.BACKEND, name));
                        }
                    });
        } catch (Exception ignored) {
            return List.of(); // never crash /models on a walk/IO error
        }
        List<ModelRef> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(out);
    }

    /**
     * Downloaded GGUFs that are NOT the configured/running model, resolved from the
     * llama_cpp config: scans its {@code hf_cache_dir} (or the default
     * {@code ~/.talos/models/huggingface}) and drops any GGUF whose name matches the
     * configured model, model_path filename, or hf_file.
     */
    public static List<ModelRef> downloadedNotConfigured(EngineConfig cfg) {
        LlamaCppConfig lc = LlamaCppConfig.from(cfg);
        Set<String> configured = configuredNames(lc);
        List<ModelRef> result = new ArrayList<>();
        for (ModelRef m : scanDownloaded(resolveCacheDir(lc.hfCacheDir()))) {
            if (!configured.contains(m.name().toLowerCase(Locale.ROOT))) {
                result.add(m);
            }
        }
        return List.copyOf(result);
    }

    /** The HF cache directory: the configured value, or the default under the user home. */
    static Path resolveCacheDir(String configured) {
        if (configured != null && !configured.isBlank()) return Path.of(configured.trim());
        return Path.of(System.getProperty("user.home"), ".talos", "models", "huggingface");
    }

    private static Set<String> configuredNames(LlamaCppConfig lc) {
        Set<String> names = new HashSet<>();
        addLower(names, lc.catalogFallbackModel());
        addLower(names, lc.model());
        addLower(names, stem(lc.hfFile()));
        if (lc.modelPath() != null && !lc.modelPath().isBlank()) {
            try {
                Path f = Path.of(lc.modelPath()).getFileName();
                if (f != null) addLower(names, stem(f.toString()));
            } catch (Exception ignored) {
                // ignore an unparseable model_path; it just won't contribute an exclusion
            }
        }
        return names;
    }

    private static void addLower(Set<String> set, String value) {
        if (value != null && !value.isBlank()) set.add(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isGguf(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf");
    }

    private static String stem(String fileName) {
        if (fileName == null) return "";
        String f = fileName.trim();
        return f.toLowerCase(Locale.ROOT).endsWith(".gguf") ? f.substring(0, f.length() - ".gguf".length()) : f;
    }
}
