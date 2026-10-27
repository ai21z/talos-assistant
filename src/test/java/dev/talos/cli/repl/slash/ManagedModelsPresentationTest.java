package dev.talos.cli.repl.slash;

import dev.talos.engine.llamacpp.ManagedModelLibrary.Entry;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import dev.talos.cli.repl.RenderEngine;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.Result;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ManagedModelsPresentationTest {
    private final List<Entry> entries = List.of(
            new Entry("qwen", "qwen-file.gguf", "local candidate (not verified)", "accepted beta stability"),
            new Entry("custom", "custom-file.gguf", "local candidate (not verified)", "custom unverified"),
            new Entry("missing", "missing-file.gguf", "unavailable locally", "experimental selectable"));

    @Test void summarySeparatesSelectionLocalCandidatesAndMissingFiles() {
        String text = ModelsCommand.renderManagedModels(entries, "llama_cpp/custom", ModelIdentitySnapshot.empty(), false);
        assertTrue(text.contains("Current selection\n  custom  [SELECTED]"), text);
        assertTrue(text.contains("Local candidates, accepted beta stability\n  qwen"));
        assertTrue(text.contains("Not available locally\n  missing"));
        assertTrue(text.contains("Local candidates are not verified"));
        assertFalse(text.contains(".gguf"));
        assertFalse(text.contains("[ACTIVE]"));
        assertTrue(text.contains("/models --verbose"));
    }

    @Test void detailsPreserveEveryFilenameAndDispositionWithoutPromotingReadiness() {
        String text = ModelsCommand.renderManagedModels(entries, "llama_cpp/custom", ModelIdentitySnapshot.empty(), false, true);
        for (var entry : entries) {
            assertTrue(text.contains(entry.fileName()), text);
            assertTrue(text.contains(entry.disposition()), text);
        }
        assertTrue(text.contains("SELECTED is configuration only"));
        assertFalse(text.contains("[ACTIVE]"));
    }

    @Test void activeStillRequiresTheMatchedServedIdentity() {
        var matched = ModelIdentitySnapshot.builder().backend("llama_cpp").configuredModel("qwen")
                .chatAvailable(true).propsState("AVAILABLE").servedIdentityState("MATCHED")
                .resolution("UNQUALIFIED").build();
        assertTrue(ModelsCommand.renderManagedModels(entries, "llama_cpp/qwen", matched, false).contains("qwen  [ACTIVE]"));
        assertFalse(ModelsCommand.renderManagedModels(entries, "llama_cpp/custom", matched, false).contains("[ACTIVE]"));
        String unavailable = ModelsCommand.renderManagedModels(entries, "llama_cpp/qwen", matched, true);
        assertFalse(unavailable.contains("[ACTIVE]"));
        assertFalse(unavailable.contains("[SELECTED]"));
        assertTrue(unavailable.contains("incomplete switch"));
    }

    @Test void emptyLibraryOffersHelpWithoutInventingModels() {
        String text = ModelsCommand.renderManagedModels(List.of(), "", ModelIdentitySnapshot.empty(), false);
        assertTrue(text.contains("No local model entries"));
        assertTrue(text.contains("/help models"));
    }

    @Test void actualCommandRendererKeepsTheSummaryReadableAtNarrowAndNormalWidths() {
        for (int width : List.of(40, 80, 120)) {
            var sink = new ByteArrayOutputStream();
            var renderer = new RenderEngine(new Config(null), new Redactor(),
                    new PrintStream(sink, true, StandardCharsets.UTF_8), false, () -> width);
            renderer.renderCommand("models", new Result.Ok(ModelsCommand.renderManagedModels(
                    entries, "llama_cpp/custom", ModelIdentitySnapshot.empty(), false)));
            String output = sink.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("custom  [SELECTED]"), output);
            assertFalse(output.contains("answer"));
            assertFalse(output.contains(".gguf"));
            output.lines().forEach(line -> assertTrue(line.length() <= width, line));
            if (width == 80) System.out.println(output);
        }
    }
}
