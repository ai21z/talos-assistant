package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticRepairTargetProgressAccountingTest {

    @Test
    void remainingFullRewriteRepairTargetsSubtractsSuccessfulMutations() {
        LoopState state = stateWithRepairContext("styles.css, assets/index.html, scripts.js");
        state.toolOutcomes.add(outcome("talos.write_file", "assets\\index.html", true, true));
        state.toolOutcomes.add(outcome("talos.read_file", "scripts.js", true, false));
        state.toolOutcomes.add(outcome("talos.write_file", "styles.css", false, true));

        assertEquals(
                List.of("scripts.js", "styles.css"),
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state));
    }

    @Test
    void remainingFullRewriteRepairTargetsIncludesRuntimeRequiredTargetsWithoutRenderedContext() {
        LoopState state = emptyState();
        state.staticWebFullRewriteRequiredTargets.add("scripts.js");
        state.staticWebFullRewriteRequiredTargets.add("index.html");
        state.toolOutcomes.add(outcome("talos.write_file", "scripts.js", true, true));

        assertEquals(
                List.of("index.html"),
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state));
        assertFalse(StaticRepairTargetProgressAccounting.hasStaticRepairContext(state));
    }

    @Test
    void hasStaticRepairContextRequiresRenderedFullRewriteTargets() {
        LoopState state = stateWithRepairContext("index.html, styles.css");

        assertTrue(StaticRepairTargetProgressAccounting.hasStaticRepairContext(state));
        assertFalse(StaticRepairTargetProgressAccounting.hasStaticRepairContext(emptyState()));
        assertFalse(StaticRepairTargetProgressAccounting.hasStaticRepairContext(null));
    }

    private static LoopState stateWithRepairContext(String targets) {
        LoopState state = emptyState();
        state.messages.add(ChatMessage.system("""
                [Static verification repair context]
                Previous static verification problems:
                - Static verification failed.
                Full-file replacement targets: %s
                """.formatted(targets)));
        return state;
    }

    private static LoopState emptyState() {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(),
                Path.of("."),
                null,
                null,
                10,
                0);
    }

    private static ToolCallLoop.ToolOutcome outcome(
            String toolName,
            String pathHint,
            boolean success,
            boolean mutating
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                success,
                mutating,
                false,
                "summary",
                "");
    }

    @Test
    void fullRewriteTargetRewrittenUnderItsAbsoluteSpellingIsNotRemaining(@TempDir Path temporary)
            throws IOException {
        Path root = workspaceWith(temporary,
                "web/styles.css", "web/index.html", "web/scripts.js", "archive/index.html");
        LoopState state = stateWithRepairContextAt(root, "web/styles.css, web/index.html, web/scripts.js");
        state.toolOutcomes.add(outcome("talos.write_file",
                root.resolve("web").resolve("styles.css").toString(), true, true));
        state.toolOutcomes.add(outcome("talos.write_file",
                root.resolve("archive").resolve("index.html").toString(), true, true));
        state.toolOutcomes.add(outcome("talos.write_file",
                root.resolve("web").resolve("scripts.js").toString(), false, true));

        assertEquals(
                List.of("web/index.html", "web/scripts.js"),
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state));
    }

    @Test
    void runtimeRequiredTargetRewrittenUnderItsAbsoluteSpellingIsNotRemaining(@TempDir Path temporary)
            throws IOException {
        Path root = workspaceWith(temporary, "web/scripts.js", "web/index.html", "old/scripts.js");
        LoopState state = stateAt(root);
        state.staticWebFullRewriteRequiredTargets.add("web/scripts.js");
        state.staticWebFullRewriteRequiredTargets.add("web/index.html");
        state.toolOutcomes.add(outcome("talos.write_file",
                root.resolve("web").resolve("scripts.js").toString(), true, true));
        state.toolOutcomes.add(outcome("talos.write_file",
                root.resolve("old").resolve("scripts.js").toString(), true, true));

        assertEquals(
                List.of("web/index.html"),
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state));
    }

    private static Path workspaceWith(Path temporary, String... files) throws IOException {
        Path root = temporary.toRealPath();
        for (String file : files) {
            Path path = root.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, file + "\n");
        }
        return root;
    }

    private static LoopState stateWithRepairContextAt(Path root, String targets) {
        LoopState state = stateAt(root);
        state.messages.add(ChatMessage.system("""
                [Static verification repair context]
                Previous static verification problems:
                - Static verification failed.
                Full-file replacement targets: %s
                """.formatted(targets)));
        return state;
    }

    private static LoopState stateAt(Path root) {
        return new LoopState("", List.of(), new ArrayList<>(), root, null, null, 10, 0);
    }
}
