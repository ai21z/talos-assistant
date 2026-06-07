package dev.talos.runtime.context;

import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectMemoryLoaderTest {
    @TempDir Path tempDir;

    @AfterEach
    void clearLedger() {
        ContextLedgerCapture.clear();
    }

    @Test
    void loadsDeterministicTieredMarkdownMemoryForWorkspaceTasks() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(userHome.resolve(".talos"));
        Files.createDirectories(workspace.resolve(".git"));
        Files.createDirectories(workspace.resolve(".talos"));
        Files.createDirectories(workspace.resolve("src").resolve(".talos"));
        Files.writeString(userHome.resolve(".talos").resolve("TALOS.md"),
                "Global preference: use short answers.", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("TALOS.md"),
                "Repo memory: this is Project Helios.", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".talos").resolve("rules.md"),
                "Workspace rule: prefer Java 21.", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("src").resolve(".talos").resolve("rules.md"),
                "Directory memory: src code uses package-private helpers.", StandardCharsets.UTF_8);

        ContextLedgerCapture.begin("trc-project-memory", 1);
        ProjectMemoryContext context = new ProjectMemoryLoader(ProjectMemoryLimits.defaults())
                .load(new ProjectMemoryRequest(
                        workspace,
                        userHome,
                        contract(TaskType.FILE_EDIT, true, "Update src/App.java", Set.of("src/App.java"))));

        assertEquals(ProjectMemoryStatus.LOADED, context.status());
        assertEquals(4, context.includedSources().size());
        assertEquals(ProjectMemoryTier.USER_GLOBAL, context.includedSources().get(0).tier());
        assertEquals(ProjectMemoryTier.REPO_ROOT, context.includedSources().get(1).tier());
        assertEquals(ProjectMemoryTier.WORKSPACE_ROOT, context.includedSources().get(2).tier());
        assertEquals(ProjectMemoryTier.DIRECTORY_LOCAL, context.includedSources().get(3).tier());
        assertTrue(context.renderForPrompt().contains("[ProjectMemory]"));
        assertTrue(context.renderForPrompt().contains("untrusted local context"));
        assertTrue(context.renderForPrompt().contains("Project Helios"));

        var ledger = ContextLedgerCapture.snapshot();
        assertEquals(4, ledger.summary().bySource().get("PROJECT_MEMORY"));
        assertEquals(1, ledger.summary().byBoundary().get("LOCAL_USER_CONFIGURATION"));
        assertEquals(3, ledger.summary().byBoundary().get("LOCAL_WORKSPACE"));
        assertEquals(4, ledger.summary().byDecision().get("INCLUDED_IN_MODEL_PROMPT"));
    }

    @Test
    void suppressesMemoryForSmallTalkAndPrivacyTurns() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(userHome.resolve(".talos"));
        Files.createDirectories(workspace);
        Files.writeString(userHome.resolve(".talos").resolve("TALOS.md"),
                "Global secret-ish preference that must not appear.", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("TALOS.md"),
                "Workspace memory that must not appear.", StandardCharsets.UTF_8);

        ProjectMemoryLoader loader = new ProjectMemoryLoader(ProjectMemoryLimits.defaults());

        ProjectMemoryContext smallTalk = loader.load(new ProjectMemoryRequest(
                workspace,
                userHome,
                contract(TaskType.SMALL_TALK, false, "hello", Set.of())));
        assertEquals(ProjectMemoryStatus.SUPPRESSED, smallTalk.status());
        assertTrue(smallTalk.includedSources().isEmpty());
        assertFalse(smallTalk.renderForPrompt().contains("Workspace memory"));

        ProjectMemoryContext privacy = loader.load(new ProjectMemoryRequest(
                workspace,
                userHome,
                contract(TaskType.READ_ONLY_QA, false, "What data leaves my machine?", Set.of())));
        assertEquals(ProjectMemoryStatus.SUPPRESSED, privacy.status());
        assertTrue(privacy.includedSources().isEmpty());
        assertFalse(privacy.renderForPrompt().contains("Global secret-ish"));
    }

    @Test
    void explicitProjectMemoryOptOutSuppressesLoadingForCurrentTurn() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(userHome.resolve(".talos"));
        Files.createDirectories(workspace);
        Files.writeString(userHome.resolve(".talos").resolve("TALOS.md"),
                "Global memory that must be suppressed.", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("TALOS.md"),
                "Workspace memory that must be suppressed.", StandardCharsets.UTF_8);

        ProjectMemoryLoader loader = new ProjectMemoryLoader(ProjectMemoryLimits.defaults());

        ProjectMemoryContext readOnly = loader.load(new ProjectMemoryRequest(
                workspace,
                userHome,
                contract(TaskType.READ_ONLY_QA, false,
                        "Explain this project, but do not load project memory.", Set.of())));
        ProjectMemoryContext mutation = loader.load(new ProjectMemoryRequest(
                workspace,
                userHome,
                contract(TaskType.FILE_EDIT, true,
                        "Update README.md, but ignore TALOS.md for this turn.", Set.of("README.md"))));

        assertEquals(ProjectMemoryStatus.SUPPRESSED, readOnly.status());
        assertEquals("USER_OPTED_OUT_PROJECT_MEMORY", readOnly.reason());
        assertTrue(readOnly.includedSources().isEmpty());
        assertFalse(readOnly.renderForPrompt().contains("Workspace memory"));

        assertEquals(ProjectMemoryStatus.SUPPRESSED, mutation.status());
        assertEquals("USER_OPTED_OUT_PROJECT_MEMORY", mutation.reason());
        assertTrue(mutation.includedSources().isEmpty());
        assertFalse(mutation.renderForPrompt().contains("Global memory"));
    }

    @Test
    void genericMemoryCodePhrasesDoNotSuppressProjectMemory() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(userHome);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("TALOS.md"),
                "Repo memory: use Java 21.", StandardCharsets.UTF_8);

        ProjectMemoryLoader loader = new ProjectMemoryLoader(ProjectMemoryLimits.defaults());

        ProjectMemoryContext leak = loader.load(new ProjectMemoryRequest(
                workspace,
                userHome,
                contract(TaskType.FILE_EDIT, true,
                        "Fix the memory leak in src/App.java.", Set.of("src/App.java"))));
        ProjectMemoryContext cache = loader.load(new ProjectMemoryRequest(
                workspace,
                userHome,
                contract(TaskType.READ_ONLY_QA, false,
                        "Explain the in-memory cache used by this project.", Set.of())));

        assertEquals(ProjectMemoryStatus.LOADED, leak.status());
        assertTrue(leak.renderForPrompt().contains("Repo memory: use Java 21."), leak.renderForPrompt());
        assertEquals(ProjectMemoryStatus.LOADED, cache.status());
        assertTrue(cache.renderForPrompt().contains("Repo memory: use Java 21."), cache.renderForPrompt());
    }

    @Test
    void budgetKeepsSpecificWorkspaceMemoryOverBroadGlobalMemory() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(userHome.resolve(".talos"));
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(userHome.resolve(".talos").resolve("TALOS.md"),
                "global ".repeat(200), StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("TALOS.md"),
                "Repo fact: keep this specific workspace memory.", StandardCharsets.UTF_8);

        ProjectMemoryLimits limits = new ProjectMemoryLimits(
                8,
                3,
                4096,
                4096,
                200,
                120);
        ProjectMemoryContext context = new ProjectMemoryLoader(limits).load(new ProjectMemoryRequest(
                workspace,
                userHome,
                contract(TaskType.FILE_EDIT, true, "Improve README.md", Set.of("README.md"))));

        assertEquals(ProjectMemoryStatus.LOADED, context.status());
        String prompt = context.renderForPrompt();
        assertTrue(prompt.contains("Repo fact: keep this specific workspace memory."), prompt);
        assertFalse(prompt.contains("global global global"), prompt);
        assertTrue(context.decisions().stream().anyMatch(decision ->
                decision.tier() == ProjectMemoryTier.USER_GLOBAL
                        && decision.decisionReason().equals("BUDGET_DROPPED_LEAST_SPECIFIC")));
    }

    @Test
    void blankSanitizedMemorySourceIsSkippedWithAuditableDecision() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(userHome);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("TALOS.md"),
                "   \r\n\t\n", StandardCharsets.UTF_8);

        ProjectMemoryContext context = new ProjectMemoryLoader(ProjectMemoryLimits.defaults())
                .load(new ProjectMemoryRequest(
                        workspace,
                        userHome,
                        contract(TaskType.WORKSPACE_EXPLAIN, false, "Explain this project", Set.of())));

        assertEquals(ProjectMemoryStatus.EMPTY, context.status());
        assertTrue(context.includedSources().isEmpty());
        assertFalse(context.renderForPrompt().contains("[Source]"), context.renderForPrompt());
        assertTrue(context.decisions().stream().anyMatch(decision ->
                decision.pathHint().equals("TALOS.md")
                        && decision.action().equals("WITHHELD_FROM_MODEL")
                        && decision.decisionReason().equals("BLANK_AFTER_SANITIZATION")),
                context.decisions().toString());
    }

    @Test
    void protectedWorkspaceMemoryCandidateIsNotReadIntoPrompt() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(userHome);
        Files.createDirectories(workspace.resolve("protected"));
        Files.writeString(workspace.resolve("protected").resolve("TALOS.md"),
                "PRIVATE_MARKER = DO_NOT_LEAK_7F39", StandardCharsets.UTF_8);

        ProjectMemoryContext context = new ProjectMemoryLoader(ProjectMemoryLimits.defaults())
                .load(new ProjectMemoryRequest(
                        workspace,
                        userHome,
                        contract(TaskType.FILE_EDIT, true, "Update the nested file.", Set.of("protected/file.txt"))));

        assertTrue(context.includedSources().isEmpty());
        assertFalse(context.renderForPrompt().contains("DO_NOT_LEAK_7F39"));
        assertTrue(context.decisions().stream().anyMatch(decision ->
                decision.decisionReason().equals("PROTECTED_PATH")));
    }

    @Test
    void unsupportedMarkdownImportsRemainPlainTextNotExpanded() throws Exception {
        Path userHome = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(userHome);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("TALOS.md"),
                "Main memory.\n@include private.md\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("private.md"),
                "This must not be imported.", StandardCharsets.UTF_8);

        ProjectMemoryContext context = new ProjectMemoryLoader(ProjectMemoryLimits.defaults())
                .load(new ProjectMemoryRequest(
                        workspace,
                        userHome,
                        contract(TaskType.WORKSPACE_EXPLAIN, false, "Explain this project", Set.of())));

        String prompt = context.renderForPrompt();
        assertTrue(prompt.contains("@include private.md"), prompt);
        assertFalse(prompt.contains("This must not be imported."), prompt);
    }

    private static TaskContract contract(
            TaskType type,
            boolean mutationAllowed,
            String request,
            Set<String> targets
    ) {
        return new TaskContract(
                type,
                mutationAllowed,
                mutationAllowed,
                mutationAllowed,
                targets,
                Set.of(),
                request);
    }
}
