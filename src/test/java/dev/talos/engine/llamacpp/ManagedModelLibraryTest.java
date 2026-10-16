package dev.talos.engine.llamacpp;

import dev.talos.spi.EngineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedModelLibraryTest {
    @TempDir Path temp;

    @Test
    void listsEverySelectableProfileAndOriginalWithoutPretendingItIsVerified() throws Exception {
        Path original = gguf("personal.gguf");
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        Path cache = Files.createDirectory(temp.resolve("cache"));
        Files.write(cache.resolve(profile.artifactFile()), new byte[] {'G', 'G', 'U', 'F'});
        var library = library(config(original, List.of()), List.of(cache));

        var entries = library.entries();

        assertEquals(LlamaCppModelCatalog.selectableProfiles().size() + 1, entries.size());
        assertEquals("local candidate (not verified)", entry(entries, "personal").availability());
        assertEquals("local candidate (not verified)", entry(entries, "qwen3-8b").availability());
        assertEquals("unavailable locally", entry(entries, "gpt-oss-20b").availability());
        assertThrows(IllegalArgumentException.class, () -> library.configuration("gpt-oss-20b"));
    }

    @Test
    void registeredLocalCustomModelUsesConservativeDefaultsAndRestoresOriginalSettings() throws Exception {
        EngineConfig base = config(gguf("personal.gguf"),
                List.of("--gpu-layers", "12", "--reasoning", "on", "--ctx-size=16384"));
        block(base).put("context", 16384);
        block(base).put("chat_template", "personal template");
        block(base).put("chat_template_file", "personal-template.jinja");
        block(base).put("jinja", false);
        var library = library(base, List.of());
        Path imported = gguf("new.gguf");
        library.register("NEW", imported);

        EngineConfig selected = library.configuration("new");

        assertEquals("new", block(selected).get("model"));
        assertEquals(imported.toRealPath().toString(), block(selected).get("model_path"));
        assertEquals("", block(selected).get("hf_repo"));
        assertEquals("", block(selected).get("hf_file"));
        assertEquals(LlamaCppConfig.DEFAULT_CONTEXT, block(selected).get("context"));
        assertEquals("", block(selected).get("chat_template"));
        assertEquals("", block(selected).get("chat_template_file"));
        assertEquals(true, block(selected).get("jinja"));
        assertEquals(List.of("--gpu-layers", "12"), block(selected).get("server_args"));
        assertEquals(base.data().get("tools"), selected.data().get("tools"));
        assertEquals(base.data().get("privacy"), selected.data().get("privacy"));
        assertEquals(base.data().get("sampling"), selected.data().get("sampling"));
        assertEquals(base.data().get("embedding"), selected.data().get("embedding"));
        assertEquals("127.0.0.1", block(selected).get("host"));
        assertEquals(8087, block(selected).get("port"));
        assertEquals("configured-server", block(selected).get("server_path"));
        assertEquals(base.data(), library.configuration("PERSONAL").data());
    }

    @Test
    void knownProfileUsesItsOwnRuntimeArgumentsButDoesNotOverrideGlobalToolPolicy() throws Exception {
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        Path cache = Files.createDirectory(temp.resolve("cache"));
        Files.write(cache.resolve(profile.artifactFile()), new byte[] {'G', 'G', 'U', 'F'});
        EngineConfig base = config(gguf("personal.gguf"), List.of("-ngl", "15", "--reasoning", "on"));
        var library = library(base, List.of(cache));

        EngineConfig selected = library.configuration(profile.alias());

        assertEquals(profile.runtime().contextTokens(), block(selected).get("context"));
        assertEquals(List.of("-ngl", "15", "--reasoning", "off"), block(selected).get("server_args"));
        assertEquals(Map.of("native_calling", false), selected.data().get("tools"));
    }

    @Test
    void snapshotsAreDetachedFromBothInputAndPreviousSelections() throws Exception {
        EngineConfig base = config(gguf("personal.gguf"), new ArrayList<>(List.of("-ngl", "12")));
        var library = library(base, List.of());
        Path imported = gguf("new.gguf");
        library.register("new", imported);
        block(base).put("server_path", "mutated input");
        EngineConfig first = library.configuration("new");
        assertThrows(UnsupportedOperationException.class, () -> block(first).put("server_path", "mutated output"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) first.data().get("privacy");
        assertThrows(UnsupportedOperationException.class, () -> nested.put("mode", "mutated"));
        assertThrows(UnsupportedOperationException.class, () -> first.data().put("engines", Map.of()));
        @SuppressWarnings("unchecked")
        List<String> outputArgs = (List<String>) block(first).get("server_args");
        assertThrows(UnsupportedOperationException.class, () -> outputArgs.add("--model=unexpected.gguf"));

        EngineConfig second = library.configuration("new");

        assertEquals("configured-server", block(second).get("server_path"));
        assertEquals(Map.of("mode", "local-only"), second.data().get("privacy"));
    }

    @Test
    void registrationRequiresGgufHeaderAndValidAliasAndNeverOverwrites() throws Exception {
        var library = library(config(gguf("personal.gguf"), List.of()), List.of());
        Path first = gguf("first.gguf");
        Path second = gguf("second.gguf");
        Path bogus = Files.writeString(temp.resolve("bogus.gguf"), "not a model");
        assertThrows(IllegalArgumentException.class, () -> library.register("bad name", first));
        assertThrows(IllegalArgumentException.class, () -> library.register("llama_cpp/test", first));
        assertThrows(IllegalArgumentException.class, () -> library.register("bad", bogus));
        assertThrows(IllegalArgumentException.class, () -> library.register("bad", temp));
        assertThrows(IllegalArgumentException.class, () -> library.register("relative", Path.of("first.gguf")));
        library.register("FIRST", first);
        library.register("first", first);
        assertThrows(IllegalArgumentException.class, () -> library.register("first", second));
        assertThrows(IllegalArgumentException.class, () -> library.register("personal", first));
        assertEquals(first.toRealPath().toString(), block(library.configuration("FIRST")).get("model_path"));
    }

    @Test
    void registrationAndSelectionShareTheQualifiedModelNameBoundary() throws Exception {
        var library = library(config(gguf("personal.gguf"), List.of()), List.of());
        Path model = gguf("new.gguf");
        String accepted = "a".repeat(54);
        String tooLong = "a".repeat(55);
        library.register(accepted, model);
        assertEquals(accepted, block(library.configuration(accepted)).get("model"));
        assertThrows(IllegalArgumentException.class, () -> library.register(tooLong, model));
        assertThrows(IllegalArgumentException.class, () -> library.configuration(tooLong));
        assertThrows(IllegalArgumentException.class, () -> library.register("a..b", model));
        assertThrows(IllegalArgumentException.class, () -> library.configuration("a..b"));
        assertThrows(IllegalArgumentException.class, () -> library.register("\u212A", model));
        assertFalse(library.entries().stream().anyMatch(entry -> entry.alias().equals(tooLong)));
    }

    @Test
    void matchingFilenameCannotImpersonatePinnedBuiltinArtifact() throws Exception {
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        Path forged = gguf(profile.artifactFile());
        var base = config(gguf("personal.gguf"), List.of());
        block(base).put("server_path", Files.writeString(temp.resolve("server"), "fixture").toString());
        var library = new ManagedModelLibrary(base, List.of(temp),
                cfg -> new ManagedModelLibrary(cfg).configuration("qwen3-8b"));
        assertThrows(IllegalArgumentException.class, () -> library.register("qwen3-8b", forged));
        assertThrows(IllegalStateException.class, () -> library.configuration("qwen3-8b"));
    }

    @Test
    void rejectsSourceAliasEndpointAndUnknownOverridesBeforeValidation() throws Exception {
        Path original = gguf("personal.gguf");
        Path next = gguf("next.gguf");
        for (List<String> args : List.of(List.of("--model", "elsewhere.gguf"), List.of("-m=elsewhere.gguf"),
                List.of("--hf-repo", "repo/name"), List.of("--alias=wrong"), List.of("--port", "9999"),
                List.of("--host=0.0.0.0"), List.of("--unknown-flag"), List.of("-ngl", "--model"))) {
            AtomicInteger validations = new AtomicInteger();
            var library = new ManagedModelLibrary(config(original, args), List.of(), cfg -> validations.incrementAndGet());
            library.register("next", next);
            assertThrows(IllegalArgumentException.class, () -> library.configuration("next"), args.toString());
            assertEquals(0, validations.get());
        }
    }

    @Test
    void validationFailureIsPropagatedAndRegistrationRemainsAvailable() throws Exception {
        var failure = new IllegalStateException("local mismatch");
        var library = new ManagedModelLibrary(config(gguf("personal.gguf"), List.of()), List.of(), cfg -> { throw failure; });
        library.register("next", gguf("next.gguf"));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> library.configuration("next")));
        assertEquals("local candidate (not verified)", entry(library.entries(), "next").availability());
    }

    @Test
    void realCustomPrelaunchPreservesSamplingAndLoggingWithoutWritingArtifacts() throws Exception {
        var base = config(gguf("personal.gguf"), List.of("--temp", "0.17", "--log-disable", "--no-webui"));
        block(base).put("server_path", Files.writeString(temp.resolve("server"), "fixture").toString());
        var library = new ManagedModelLibrary(base);
        library.register("next", gguf("next.gguf"));
        List<Path> before;
        try (var files = Files.walk(temp)) { before = files.sorted().toList(); }

        var selected = library.configuration("next");

        assertEquals(List.of("--temp", "0.17", "--log-disable", "--no-webui"), block(selected).get("server_args"));
        try (var files = Files.walk(temp)) { assertEquals(before, files.sorted().toList()); }
    }

    @Test
    void invalidLegacyAliasDoesNotBreakConstructionButManagedSelectionIsRefused() throws Exception {
        var base = config(gguf("personal.gguf"), List.of());
        block(base).put("model", "legacy/model name");
        var managed = assertDoesNotThrow(() -> new ManagedModelLibrary(base));
        assertThrows(IllegalArgumentException.class, managed::entries);
        assertThrows(IllegalArgumentException.class, () -> managed.configuration("next"));
        block(base).put("mode", "connect_only");
        var external = assertDoesNotThrow(() -> new ManagedModelLibrary(base));
        assertFalse(external.managed());
        assertEquals(List.of(), external.entries());
        assertDoesNotThrow(() -> new ManagedModelLibrary(() -> Map.of("engines", Map.of("ollama", Map.of("model", "org/model:tag")))));
    }

    @Test
    void selectionMakesManagedModeExplicitAndRealPrelaunchRejectsMissingBinary() throws Exception {
        var base = config(gguf("personal.gguf"), List.of());
        block(base).remove("mode");
        var transform = library(base, List.of());
        transform.register("next", gguf("next.gguf"));
        assertEquals("managed", block(transform.configuration("next")).get("mode"));
        var real = new ManagedModelLibrary(base);
        real.register("next", temp.resolve("next.gguf"));
        var failure = assertThrows(IllegalArgumentException.class, () -> real.configuration("next"));
        assertTrue(failure.getMessage().contains("server binary"));
    }

    @Test
    void connectOnlyModeRefusesMutationAndDeletedImportsRefuseSelection() throws Exception {
        EngineConfig base = config(gguf("personal.gguf"), List.of());
        block(base).put("mode", "connect_only");
        var external = library(base, List.of());
        assertFalse(external.managed());
        assertThrows(IllegalStateException.class, () -> external.register("next", temp.resolve("next.gguf")));
        assertThrows(IllegalStateException.class, () -> external.configuration("personal"));
        block(base).put("mode", "managed");
        var managed = library(base, List.of());
        Path next = gguf("next.gguf");
        managed.register("next", next);
        Files.delete(next);
        assertEquals("unavailable locally", entry(managed.entries(), "next").availability());
        assertThrows(IllegalArgumentException.class, () -> managed.configuration("next"));
    }

    @Test
    void discoveryOnlyFindsKnownFilesWithinTheirOwnRepositorySnapshots() throws Exception {
        var profile = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();
        Path cache = Files.createDirectory(temp.resolve("cache"));
        Path wrong = Files.createDirectories(cache.resolve("models--wrong--repository/snapshots/other"));
        Files.write(wrong.resolve(profile.artifactFile()), new byte[] {'G', 'G', 'U', 'F'});
        var library = library(config(gguf("personal.gguf"), List.of()), List.of(cache));
        assertEquals("unavailable locally", entry(library.entries(), profile.alias()).availability());
        Path pinned = Files.createDirectories(cache.resolve("hub/models--" + profile.repository().replace("/", "--")
                + "/snapshots/" + profile.revision()));
        Files.write(pinned.resolve(profile.artifactFile()), new byte[] {'G', 'G', 'U', 'F'});
        assertEquals("local candidate (not verified)", entry(library.entries(), profile.alias()).availability());
    }

    @Test
    void discoversExactWizardManifestLayoutWithoutScanningOtherAliases() throws Exception {
        var profile = LlamaCppModelCatalog.byAlias("gpt-oss-20b").orElseThrow();
        Path root = Files.createDirectories(temp.resolve(".talos/models/gguf"));
        Path wrong = Files.createDirectories(root.resolve("other-alias"));
        Files.write(wrong.resolve(profile.artifactFile()), new byte[] {'G', 'G', 'U', 'F'});
        var library = library(config(gguf("personal.gguf"), List.of()), List.of(root));
        assertEquals("unavailable locally", entry(library.entries(), profile.alias()).availability());
        Path expected = Files.createDirectories(root.resolve(profile.alias())).resolve(profile.artifactFile());
        Files.write(expected, new byte[] {'G', 'G', 'U', 'F'});
        assertEquals("local candidate (not verified)", entry(library.entries(), profile.alias()).availability());
        assertEquals(expected.toRealPath().toString(), block(library.configuration(profile.alias())).get("model_path"));
    }

    private Path gguf(String file) throws Exception {
        return Files.write(temp.resolve(file), new byte[] {'G', 'G', 'U', 'F', 3, 0, 0, 0});
    }

    private static ManagedModelLibrary library(EngineConfig config, List<Path> roots) {
        // Configuration transformations are tested with synthetic GGUFs and mocked prelaunch validation.
        return new ManagedModelLibrary(config, roots, cfg -> {});
    }

    private static ManagedModelLibrary.Entry entry(List<ManagedModelLibrary.Entry> entries, String alias) {
        return entries.stream().filter(entry -> entry.alias().equals(alias)).findFirst().orElseThrow();
    }

    private static EngineConfig config(Path model, List<String> args) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("mode", "managed");
        block.put("model", "personal");
        block.put("model_path", model.toAbsolutePath().normalize().toString());
        block.put("server_path", "configured-server");
        block.put("host", "127.0.0.1");
        block.put("port", 8087);
        block.put("server_args", args);
        block.put("hf_repo", "");
        block.put("hf_file", "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("engines", new LinkedHashMap<>(Map.of("llama_cpp", block)));
        data.put("tools", new LinkedHashMap<>(Map.of("native_calling", false)));
        data.put("privacy", new LinkedHashMap<>(Map.of("mode", "local-only")));
        data.put("sampling", new LinkedHashMap<>(Map.of("temperature", 0.17)));
        data.put("embedding", new LinkedHashMap<>(Map.of("model", "keep-embedding")));
        return () -> data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> block(EngineConfig config) {
        return (Map<String, Object>) ((Map<String, Object>) config.data().get("engines")).get("llama_cpp");
    }
}
