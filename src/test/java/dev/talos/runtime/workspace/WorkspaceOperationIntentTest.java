package dev.talos.runtime.workspace;

import org.junit.jupiter.api.Test;

import dev.talos.runtime.intent.WorkspaceOperationObligation;
import dev.talos.runtime.task.TaskContractResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceOperationIntentTest {

    @Test
    void dormantCompatibilityParserHasNoProductionConsumer() throws Exception {
        Path productionRoot = Path.of("src/main/java");
        Path compatibilityOwner = productionRoot.resolve(
                "dev/talos/runtime/workspace/WorkspaceOperationIntent.java").normalize();
        List<String> consumers = new ArrayList<>();

        try (var paths = Files.walk(productionRoot)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java"))
                    .filter(candidate -> !candidate.normalize().equals(compatibilityOwner))
                    .toList()) {
                if (Files.readString(path).contains("WorkspaceOperationIntent")) {
                    consumers.add(productionRoot.relativize(path)
                            .toString()
                            .replace('\\', '/'));
                }
            }
        }

        assertEquals(
                List.of(),
                consumers.stream().sorted().toList(),
                "WorkspaceOperationIntent is a dormant compatibility parser and must not regain "
                        + "production authority");
    }

    @Test
    void naturalMkdirPhrasesCompileExactTypedAuthority() {
        for (String request : List.of(
                "Create a new dir called workspace-notes.",
                "Create a new folder named audit-output.",
                "Make a new directory reports/daily.",
                "Can you create a folder called docs?",
                "make me a folder called ideas")) {
            var plan = TaskContractResolver.resolveFromUserRequest(request)
                    .workspaceOperationPlan();

            assertEquals(
                    WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                    plan.state(),
                    request);
            assertEquals(1, plan.authorizedOperations().size(), request);
            assertEquals(
                    WorkspaceOperationObligation.Kind.MKDIR,
                    plan.authorizedOperations().getFirst().kind(),
                    request);
        }
    }

    @Test
    void explicitDeleteWithFileTargetCompilesExactTypedAuthority() {
        var plan = TaskContractResolver.resolveFromUserRequest(
                "Delete docs/old-plan.md please.").workspaceOperationPlan();

        assertEquals(
                WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                plan.state());
        assertEquals(1, plan.authorizedOperations().size());
        assertEquals(
                WorkspaceOperationObligation.Kind.DELETE_PATH,
                plan.authorizedOperations().getFirst().kind());
        assertEquals("", plan.authorizedOperations().getFirst().sourcePath());
        assertEquals("", plan.authorizedOperations().getFirst().destinationPath());
        assertEquals(
                "docs/old-plan.md",
                plan.authorizedOperations().getFirst().targetPath());
    }

    @Test
    void toolNameProseWithoutTypedDeletePlanFailsClosed() {
        var plan = TaskContractResolver.resolveFromUserRequest(
                "Use talos.delete_path to delete delete-me.tmp. Perform only that workspace operation.")
                .workspaceOperationPlan();

        assertEquals(WorkspaceOperationAuthorityPlan.State.BLOCKED, plan.state());
        assertTrue(plan.authorizedOperations().isEmpty());
    }

    @Test
    void ambiguousDeleteWithoutConcreteTargetHasNoTypedAuthority() {
        var plan = TaskContractResolver.resolveFromUserRequest(
                "Delete the old one please.").workspaceOperationPlan();

        assertTrue(plan.authorizedOperations().isEmpty());
    }

    @Test
    void naturalMultiOperationBatchFailsClosedWithoutOneTypedOperation() {
        var plan = TaskContractResolver.resolveFromUserRequest(
                "batch this: create batch-one and batch-two, then copy styles.css to batch-one/styles-copy.css.")
                .workspaceOperationPlan();

        assertEquals(WorkspaceOperationAuthorityPlan.State.BLOCKED, plan.state());
        assertTrue(plan.authorizedOperations().isEmpty());
        assertTrue(plan.toolNames().isEmpty());
    }

    @Test
    void scopedSymbolRenameInsideFileHasNoWorkspaceOperationAuthority() {
        for (String request : List.of(
                "Rename reset to resetAll in script.js only. scripts.js is a different file and must remain unchanged.",
                "In script.js, rename reset to resetAll.",
                "Please rename reset to resetAll inside script.js.")) {
            var plan = TaskContractResolver.resolveFromUserRequest(request)
                    .workspaceOperationPlan();
            assertTrue(plan.authorizedOperations().isEmpty(), request);
        }
    }

    @Test
    void concreteFilesystemRenameCompilesExactTypedAuthority() {
        for (String request : List.of(
                "Rename old.txt to new.txt.",
                "Rename docs/old-plan.md to docs/new-plan.md.")) {
            var plan = TaskContractResolver.resolveFromUserRequest(request)
                    .workspaceOperationPlan();

            assertEquals(
                    WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                    plan.state(),
                    request);
            assertEquals(1, plan.authorizedOperations().size(), request);
            assertEquals(
                    WorkspaceOperationObligation.Kind.RENAME_PATH,
                    plan.authorizedOperations().getFirst().kind(),
                    request);
        }
    }
}
