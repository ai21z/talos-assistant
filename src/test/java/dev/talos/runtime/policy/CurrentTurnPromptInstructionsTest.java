package dev.talos.runtime.policy;

import dev.talos.runtime.context.ProjectMemoryContext;
import dev.talos.runtime.context.ProjectMemoryDecision;
import dev.talos.runtime.context.ProjectMemorySource;
import dev.talos.runtime.context.ProjectMemoryStatus;
import dev.talos.runtime.context.ProjectMemoryTier;
import dev.talos.runtime.context.ProjectMemoryTrust;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTurnPromptInstructionsTest {

    @Test
    void projectMemoryInstructionIsInsertedAfterBaseSystemBeforeHistoryAndCurrentTurnFrame() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("base system"),
                ChatMessage.user("earlier request"),
                ChatMessage.assistant("earlier answer"),
                ChatMessage.user("Explain this project.")));
        ProjectMemoryContext memory = memoryContext("Repo memory: Project Helios.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                new TaskContract(
                        TaskType.WORKSPACE_EXPLAIN,
                        false,
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        "Explain this project."),
                ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("talos.list_dir", "talos.read_file"),
                List.of());

        CurrentTurnPromptInstructions.injectProjectMemoryInstruction(messages, memory);
        CurrentTurnPromptInstructions.injectTaskContractInstruction(messages, plan);

        assertEquals("base system", messages.get(0).content());
        assertTrue(messages.get(1).content().contains("[ProjectMemory]"), messages.toString());
        assertTrue(messages.get(1).content().contains("untrusted local context"));
        assertTrue(messages.get(1).content().contains("Project Helios"));
        assertEquals("earlier request", messages.get(2).content());
        assertTrue(messages.get(messages.size() - 2).content().contains("[CurrentTurnCapability]"),
                messages.toString());
        assertEquals("Explain this project.", messages.get(messages.size() - 1).content());
    }

    @Test
    void noArgTaskContractInstructionPreservesDefaultMutationTools() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Create README.md."));

        CurrentTurnPromptInstructions.injectTaskContractInstruction(messages);

        String frame = messages.stream()
                .filter(message -> "system".equals(message.role()))
                .map(ChatMessage::content)
                .filter(content -> content.startsWith("[CurrentTurnCapability]"))
                .findFirst()
                .orElseThrow();

        assertTrue(frame.contains("type: FILE_CREATE"));
        assertTrue(frame.contains("obligation: MUTATING_TOOL_REQUIRED"));
        assertTrue(frame.contains("visibleTools: talos.apply_workspace_batch"));
        assertTrue(frame.contains("talos.copy_path"));
        assertTrue(frame.contains("talos.mkdir"));
        assertTrue(frame.contains("talos.move_path"));
        assertTrue(frame.contains("talos.rename_path"));
        assertTrue(frame.contains("talos.write_file"));
        assertTrue(frame.contains("talos.edit_file"));
    }

    @Test
    void taskContractInstructionUsesExplicitPlanAfterMessagesDrift() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Overwrite README.md with exactly Line one. Use talos.write_file."));
        messages.add(ChatMessage.assistant("Updated README.md."));
        messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

        CurrentTurnPlan plan = CurrentTurnPlan.create(
                TaskContractResolver.fromMessages(messages),
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());

        messages.add(ChatMessage.assistant("I can help with that."));
        messages.add(ChatMessage.user(
                "The current-turn obligation was not satisfied. Call the write tool now."));

        CurrentTurnPromptInstructions.injectTaskContractInstruction(messages, plan);

        String frame = messages.stream()
                .filter(message -> "system".equals(message.role()))
                .map(ChatMessage::content)
                .filter(content -> content.startsWith("[CurrentTurnCapability]"))
                .findFirst()
                .orElseThrow();

        assertTrue(frame.contains("type: FILE_EDIT"));
        assertTrue(frame.contains("mutationAllowed: true"));
        assertTrue(frame.contains("visibleTools: talos.write_file"));
        assertTrue(frame.contains("obligation: MUTATING_TOOL_REQUIRED"));
        assertTrue(frame.contains("[ExactFileWrite]"), frame);
        assertTrue(frame.contains("target: index.html"), frame);
        assertTrue(frame.contains("\nAFTER\n"), frame);
        assertFalse(frame.contains("target: README.md"), frame);
        assertFalse(frame.contains("\nLine one\n"), frame);
    }

    @Test
    void taskContractInstructionIsIdempotent() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Check the workspace. Do not change anything."));

        CurrentTurnPromptInstructions.injectTaskContractInstruction(messages);
        CurrentTurnPromptInstructions.injectTaskContractInstruction(messages);

        long count = messages.stream()
                .filter(message -> "system".equals(message.role()))
                .filter(message -> message.content() != null)
                .filter(message -> message.content().startsWith("[CurrentTurnCapability]"))
                .count();
        assertEquals(1, count);
    }

    @Test
    void staleStaticRepairContextIsSkippedForFreshUnrelatedTargetsAndRecordedInTrace() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Edit README.md now using talos.write_file. The complete file must contain exactly two lines."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - README.md literal content mismatch]

                The requested task is not verified complete.
                Remaining static verification problems:
                - README.md: literal content did not match the exact requested content.
                """));
        messages.add(ChatMessage.user(
                "Create index.html, styles.css, and scripts.js for a BMI calculator. Use talos.write_file."));
        var contract = TaskContractResolver.fromMessages(messages);

        LocalTurnTraceCapture.begin(
                "trc-t818",
                "session-t818",
                1,
                "2026-06-15T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                messages.get(messages.size() - 1).content());
        try {
            CurrentTurnPromptInstructions.injectStaticVerificationRepairInstruction(messages, contract);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(messages.stream()
                    .filter(message -> "system".equals(message.role()))
                    .map(message -> message.content() == null ? "" : message.content())
                    .noneMatch(content -> content.startsWith("[Static verification repair context]")));
            assertEquals("SKIPPED", trace.repair().status());
            assertTrue(trace.repair().summary().contains("targets did not overlap"),
                    trace.repair().summary());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void staticVerificationRepairPromptIncludesCurrentSelectorFactsForMixedSelectorRepair(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                <head>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <button type="button">Calculate BMI</button>
                  <p id="result"></p>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                .button {
                  color: white;
                }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.querySelector('.missing-button').addEventListener('click', () => {
                  document.querySelector('#result').textContent = 'Ready';
                });
                """);

        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - selector mismatches remain]

                The requested task is not verified complete.
                Unresolved static verification problems:
                - CSS references missing class selectors: `.button`
                - JavaScript references missing class selectors: `.missing-button`

                Applied mutating tool calls:
                - index.html: Updated index.html
                - styles.css: Updated styles.css
                - scripts.js: Updated scripts.js
                """));
        messages.add(ChatMessage.user("Fix the remaining static verification problems now."));

        CurrentTurnPromptInstructions.injectStaticVerificationRepairInstruction(
                messages,
                TaskContractResolver.fromMessages(messages),
                workspace);

        String repairInstruction = messages.stream()
                .map(message -> message.content() == null ? "" : message.content())
                .filter(content -> content.contains("[Static verification repair context]"))
                .findFirst()
                .orElse("");

        assertTrue(repairInstruction.contains("Full-file replacement targets: scripts.js, styles.css"),
                repairInstruction);
        assertFalse(repairInstruction.contains("CSS selector repair constraint"), repairInstruction);
        assertTrue(repairInstruction.contains("[Current static selector facts]"), repairInstruction);
        assertTrue(repairInstruction.contains("Observed in HTML:"), repairInstruction);
        assertTrue(repairInstruction.contains("- Classes: none"), repairInstruction);
        assertTrue(repairInstruction.contains("CSS references missing class selectors: `.button`"),
                repairInstruction);
        assertTrue(repairInstruction.contains("JavaScript references missing class selectors: `.missing-button`"),
                repairInstruction);
    }

    private static ProjectMemoryContext memoryContext(String content) {
        ProjectMemorySource source = new ProjectMemorySource(
                ProjectMemoryTier.REPO_ROOT,
                ProjectMemoryTrust.WORKSPACE_PROVIDED,
                "TALOS.md",
                content,
                "sha256:test",
                content.length(),
                content.getBytes(StandardCharsets.UTF_8).length,
                1,
                16,
                false);
        return new ProjectMemoryContext(
                ProjectMemoryStatus.LOADED,
                "WORKSPACE_EXPLAIN",
                List.of(source),
                List.of(new ProjectMemoryDecision(
                        source.tier(),
                        source.trust(),
                        source.pathHint(),
                        "INCLUDED_IN_MODEL_PROMPT",
                        "LOADED",
                        source.contentHash(),
                        source.chars(),
                        source.bytes(),
                        source.lines(),
                        source.estimatedTokens(),
                        source.truncated())));
    }
}
