package dev.talos.engine.llamacpp;

import dev.talos.engine.llamacpp.profile.LlamaCppArtifactVerificationStore;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import dev.talos.spi.EngineConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Session-local model selection. Discovery reads bounded cache locations and never downloads. */
public final class ManagedModelLibrary {
    public record Entry(String alias, String fileName, String availability, String disposition) {}

    private static final Set<String> MODEL_VALUE_FLAGS = Set.of(
            "-c", "--ctx-size", "--ctx_size", "--chat-template", "--chat-template-file",
            "--chat-template-kwargs", "--reasoning", "--reasoning-format", "--reasoning-budget",
            "--rope-scaling", "--rope-scale", "--rope-freq-base", "--rope-freq-scale");
    private static final Set<String> MODEL_SWITCH_FLAGS = Set.of("--jinja", "--no-jinja");
    private static final Set<String> HARDWARE_VALUE_FLAGS = Set.of(
            "-ngl", "--gpu-layers", "--n-gpu-layers", "-t", "--threads", "-tb", "--threads-batch",
            "-b", "--batch-size", "-ub", "--ubatch-size", "-mg", "--main-gpu", "-ts", "--tensor-split",
            "-sm", "--split-mode", "--device", "-dev", "--numa", "--cpu-mask", "--cpu-range",
            "--cpu-strict", "--prio", "--poll", "--parallel", "-np", "--predict", "-n", "--n-predict",
            "--flash-attn", "-fa", "--cache-type-k", "-ctk", "--cache-type-v", "-ctv",
            "--temp", "--top-k", "--top-p", "--min-p", "--seed", "--repeat-penalty",
            "--repeat-last-n", "--presence-penalty", "--frequency-penalty", "--samplers",
            "-lv", "--verbosity", "--log-verbosity");
    private static final Set<String> HARDWARE_SWITCH_FLAGS = Set.of(
            "--mlock", "--mmap", "--no-mmap", "--no-kv-offload", "--no-op-offload",
            "--cont-batching", "--no-cont-batching", "--no-webui", "--log-disable", "--no-slots");
    private static final int MAX_SNAPSHOTS = 256;

    private final Map<String, Object> base;
    private final LlamaCppConfig original;
    private final String originalAlias;
    private final Map<String, Path> registered = new LinkedHashMap<>();
    private final List<Path> cacheRoots;
    private final Consumer<EngineConfig> prelaunch;

    public ManagedModelLibrary(EngineConfig base) {
        this(base, null, ManagedModelLibrary::requirePrelaunch);
    }

    // Explicit roots and validation seam keep fixtures isolated from the user's models.
    ManagedModelLibrary(EngineConfig config, List<Path> roots, Consumer<EngineConfig> prelaunch) {
        this.base = copyMap(Objects.requireNonNull(config, "base").data());
        this.original = LlamaCppConfig.from(() -> this.base);
        // Construction is also used while other providers are active. Legacy names are
        // validated only when a managed-library operation is requested.
        this.originalAlias = original.model().isBlank() ? "current-model" : original.model().toLowerCase(Locale.ROOT);
        this.cacheRoots = roots == null ? cacheRoots(original.hfCacheDir()) : List.copyOf(roots);
        this.prelaunch = Objects.requireNonNull(prelaunch, "prelaunch");
    }

    public boolean managed() {
        return original.managed();
    }

    public synchronized List<Entry> entries() {
        if (!managed()) return List.of();
        requireManaged();
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (var profile : LlamaCppModelCatalog.selectableProfiles()) {
            Path path = resolve(profile.alias());
            entries.put(profile.alias(), new Entry(profile.alias(), profile.artifactFile(),
                    availability(path), profile.disposition().label()));
        }
        if (!original.modelPath().isBlank() || !original.model().isBlank()) {
            Path path = resolve(originalAlias);
            entries.putIfAbsent(originalAlias, new Entry(originalAlias, fileName(path), availability(path),
                    "current session configuration"));
        }
        registered.forEach((name, path) -> entries.putIfAbsent(name,
                new Entry(name, fileName(path), availability(path), "custom unverified")));
        return List.copyOf(entries.values());
    }

    public synchronized void register(String name, Path modelPath) {
        requireManaged();
        String key = alias(name);
        if (modelPath == null || !modelPath.isAbsolute()) {
            throw new IllegalArgumentException("Registration requires an absolute local GGUF path");
        }
        Path path = requireGguf(modelPath);
        var profile = LlamaCppModelCatalog.byAlias(key);
        if (profile.isPresent() && !profile.get().selectable()) {
            throw new IllegalArgumentException("Profile is not selectable: " + key);
        }
        Path prior = registered.get(key);
        if (prior == null && key.equals(originalAlias)) prior = path(original.modelPath());
        if (prior != null && !sameFile(prior, path)) {
            throw new IllegalArgumentException("Alias is already bound to a different model: " + key);
        }
        if (profile.isPresent()) requireExactArtifact(path, key, profile.get().artifact());
        registered.putIfAbsent(key, path);
    }

    /** Builds and validates the target before the caller stops the currently running engine. */
    public synchronized EngineConfig configuration(String name) {
        requireManaged();
        String key = alias(name);
        var profile = LlamaCppModelCatalog.byAlias(key);
        if (profile.isPresent() && !profile.get().selectable()) {
            throw new IllegalArgumentException("Profile is not selectable: " + key);
        }
        Path target = resolve(key);
        if (target == null) throw new IllegalArgumentException("No local model available for alias: " + key);
        target = requireGguf(target);
        Map<String, Object> data = copyMap(base);
        Map<String, Object> engines = mutableBlock(data, "engines");
        Map<String, Object> block = mutableBlock(engines, "llama_cpp");
        // Validate even when restoring the original settings, before any process transition.
        List<String> safeArgs = hardwareArguments(original.serverArgs());
        block.put("mode", "managed");
        block.put("model_path", target.toString());
        block.put("model", key);
        block.put("hf_repo", "");
        block.put("hf_file", "");
        if (!key.equals(originalAlias)) {
            block.put("context", profile.map(p -> p.runtime().contextTokens()).orElse(LlamaCppConfig.DEFAULT_CONTEXT));
            block.put("jinja", true);
            block.put("chat_template", "");
            block.put("chat_template_file", "");
            List<String> arguments = new ArrayList<>(safeArgs);
            profile.ifPresent(p -> arguments.addAll(p.runtime().managedServerArgs()));
            block.put("server_args", arguments);
        }
        Map<String, Object> snapshot = immutableMap(data);
        EngineConfig result = () -> snapshot;
        prelaunch.accept(result);
        return result;
    }

    private void requireManaged() {
        if (!managed()) throw new IllegalStateException("Model switching requires a managed llama.cpp engine");
        alias(original.model().isBlank() ? originalAlias : original.model());
    }

    private Path resolve(String key) {
        Path imported = registered.get(key);
        if (imported != null) return imported;
        if (key.equals(originalAlias) && !original.modelPath().isBlank()) return path(original.modelPath());
        return LlamaCppModelCatalog.byAlias(key).filter(LlamaCppModelCatalog.Profile::selectable)
                .map(this::cached).orElse(null);
    }

    private Path cached(LlamaCppModelCatalog.Profile profile) {
        for (Path root : cacheRoots) {
            Path manifestFile = root.resolve(profile.alias()).resolve(profile.artifactFile());
            if (Files.isRegularFile(manifestFile)) return manifestFile;
            for (Path base : List.of(root, root.resolve("hub"))) {
                Path flat = base.resolve(profile.artifactFile());
                if (Files.isRegularFile(flat)) return flat;
                Path snapshots = base.resolve("models--" + profile.repository().replace("/", "--"))
                        .resolve("snapshots");
                Path pinned = snapshots.resolve(profile.revision()).resolve(profile.artifactFile());
                if (Files.isRegularFile(pinned)) return pinned;
                if (!Files.isDirectory(snapshots)) continue;
                try (var children = Files.list(snapshots)) {
                    Path found = children.limit(MAX_SNAPSHOTS).sorted()
                            .map(snapshot -> snapshot.resolve(profile.artifactFile()))
                            .filter(Files::isRegularFile).findFirst().orElse(null);
                    if (found != null) return found;
                } catch (IOException | RuntimeException ignored) {
                    // Unreadable caches are unavailable. Explicit registration remains possible.
                }
            }
        }
        return null;
    }

    private static List<Path> cacheRoots(String configured) {
        Set<Path> roots = new LinkedHashSet<>();
        addRoot(roots, configured);
        Path home = Path.of(System.getProperty("user.home", "."));
        addRoot(roots, home.resolve(".talos/models/gguf").toString());
        addRoot(roots, home.resolve(".talos/models/huggingface").toString());
        addRoot(roots, System.getenv("HF_HUB_CACHE"));
        addRoot(roots, System.getenv("HUGGINGFACE_HUB_CACHE"));
        addRoot(roots, System.getenv("HF_HOME"));
        addRoot(roots, home.resolve(".cache/huggingface/hub").toString());
        addRoot(roots, home.resolve(".cache/huggingface").toString());
        return List.copyOf(roots);
    }

    private static void addRoot(Set<Path> roots, String value) {
        Path path = path(value);
        if (path != null && path.getParent() != null) roots.add(path);
    }

    private static List<String> hardwareArguments(List<String> args) {
        List<String> preserved = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String raw = args.get(i);
            int equals = raw.indexOf('=');
            String flag = equals < 0 ? raw : raw.substring(0, equals);
            boolean hardwareValue = HARDWARE_VALUE_FLAGS.contains(flag);
            boolean modelValue = MODEL_VALUE_FLAGS.contains(flag);
            if (hardwareValue || modelValue) {
                String value;
                if (equals >= 0) value = raw.substring(equals + 1);
                else if (++i < args.size()) value = args.get(i);
                else throw new IllegalArgumentException("Missing server argument value: " + flag);
                if (value.isBlank() || value.startsWith("--") || (value.startsWith("-") && !value.matches("-\\d+(\\.\\d+)?"))) {
                    throw new IllegalArgumentException("Invalid server argument value: " + flag);
                }
                if (hardwareValue) {
                    preserved.add(flag);
                    preserved.add(value);
                }
            } else if (HARDWARE_SWITCH_FLAGS.contains(flag) && equals < 0) {
                preserved.add(flag);
            } else if (!MODEL_SWITCH_FLAGS.contains(flag) || equals >= 0) {
                throw new IllegalArgumentException("Server argument cannot safely carry across model selection: " + flag
                        + ". Remove launch-source, alias, endpoint or unknown overrides before switching.");
            }
        }
        return List.copyOf(preserved);
    }

    private static void requirePrelaunch(EngineConfig cfg) {
        LlamaCppConfig config = LlamaCppConfig.from(cfg);
        Path server = path(config.serverPath());
        if (server == null || !Files.isRegularFile(server) || !Files.isReadable(server)) {
            throw new IllegalArgumentException("A readable local llama.cpp server binary is required before switching");
        }
        var service = new LlamaCppResolvedProfileService(config, null,
                (path, alias, expected, home, verify) -> readOnlyArtifact(path, alias, expected),
                uri -> { throw new IllegalStateException("Prelaunch must not contact a server"); },
                (launch, declared, props, verify) -> {
                    Path executable = path(launch.serverPath());
                    String lane = LlamaCppResolvedProfileService.engineLane(executable);
                    boolean compatible = declared.isEmpty()
                            || declared.get().runtime().compatibleEngineLanes().contains(lane);
                    return new LlamaCppProfileResolver.EngineFacts(lane, "",
                            compatible ? LlamaCppProfileResolver.MatchState.UNVERIFIED
                                    : LlamaCppProfileResolver.MatchState.MISMATCHED);
                });
        var snapshot = service.requirePrelaunch();
        if (LlamaCppModelCatalog.byAlias(config.model()).isPresent()
                && snapshot.profile().artifactState() != LlamaCppProfileResolver.ArtifactState.EXACT) {
            throw new IllegalStateException("Local model artifact could not be verified for " + config.model());
        }
    }

    private static void requireExactArtifact(Path path, String alias, LlamaCppModelCatalog.ModelArtifact expected) {
        if (readOnlyArtifact(path, alias, expected).state() != LlamaCppProfileResolver.ArtifactState.EXACT) {
            throw new IllegalArgumentException("Local model does not match the pinned artifact for " + alias);
        }
    }

    private static LlamaCppProfileResolver.ArtifactIdentity readOnlyArtifact(
            Path path, String alias, LlamaCppModelCatalog.ModelArtifact expected) {
        var cached = new LlamaCppArtifactVerificationStore().inspect(path, alias, expected, null);
        if (cached.state() != LlamaCppProfileResolver.ArtifactState.UNCHECKED) return cached;
        try (var in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            for (int count; (count = in.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count);
            String hash = HexFormat.of().formatHex(digest.digest());
            return new LlamaCppProfileResolver.ArtifactIdentity(
                    hash.equals(expected.sha256()) ? LlamaCppProfileResolver.ArtifactState.EXACT
                            : LlamaCppProfileResolver.ArtifactState.SHA256_MISMATCH,
                    path.toRealPath().toString(), expected.sizeBytes(), Files.size(path), expected.sha256(), hash, "");
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot verify local model: " + path, error);
        }
    }

    private static Path requireGguf(Path path) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)
                || !fileName(path).toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            throw new IllegalArgumentException("A readable local .gguf file is required");
        }
        try (var in = Files.newInputStream(path)) {
            byte[] magic = in.readNBytes(4);
            if (!java.util.Arrays.equals(magic, new byte[] {'G', 'G', 'U', 'F'})) {
                throw new IllegalArgumentException("File has no GGUF header: " + fileName(path));
            }
            return path.toRealPath();
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read local GGUF: " + fileName(path), error);
        }
    }

    private static String alias(String value) {
        String input = Objects.requireNonNull(value, "alias");
        if (!input.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,53}") || input.contains("..")) {
            throw new IllegalArgumentException("Use a prefix-free model alias of at most 54 letters, digits, dots, underscores or hyphens, starting with a letter or digit and without consecutive dots");
        }
        return input.toLowerCase(Locale.ROOT);
    }

    private static String availability(Path path) {
        return path != null && Files.isRegularFile(path) && Files.isReadable(path)
                ? "local candidate (not verified)" : "unavailable locally";
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }

    private static Path path(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Path.of(value).toAbsolutePath().normalize(); }
        catch (RuntimeException ignored) { return null; }
    }

    private static boolean sameFile(Path first, Path second) {
        try { return Files.isSameFile(first, second); }
        catch (IOException ignored) { return first.toAbsolutePath().normalize().equals(second); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableBlock(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value instanceof Map<?, ?>) return (Map<String, Object>) value;
        Map<String, Object> block = new LinkedHashMap<>();
        parent.put(key, block);
        return block;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) source.forEach((key, value) -> copy.put(key, copyValue(value)));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            source.forEach((key, item) -> copy.put(key, copyValue(item)));
            return copy;
        }
        if (value instanceof List<?> source) {
            List<Object> copy = new ArrayList<>();
            source.forEach(item -> copy.add(copyValue(item)));
            return copy;
        }
        return value;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            source.forEach((key, item) -> copy.put(key, immutableValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> source) {
            List<Object> copy = new ArrayList<>();
            source.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
