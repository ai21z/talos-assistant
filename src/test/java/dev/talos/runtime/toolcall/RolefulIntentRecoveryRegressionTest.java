package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceObligationPolicy;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolefulIntentRecoveryRegressionTest {

    @Test
    void scopedNegationStaysMutatingAndOnlyRequestedTargetDrivesProgress() {
        String prompt = "Improve only styles.css. Do not create extra files. "
                + "Do not modify index.html or scripts.js.";

        TaskContract contract = TaskContractResolver.fromUserRequest(prompt);
        List<String> visibleTools = ToolSurfacePlanner.defaultVisibleToolNames(contract, ExecutionPhase.APPLY);
        TurnPolicyTrace trace = TurnPolicyTrace.from(contract, "APPLY", visibleTools, visibleTools);
        LoopState state = state(prompt, Path.of("."));
        state.toolOutcomes.add(successfulWrite("styles.css"));

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationAllowed());
        assertEquals(Set.of("styles.css"), contract.expectedTargets());
        assertEquals(Set.of("index.html", "scripts.js"), contract.forbiddenTargets());
        assertTrue(visibleTools.contains("talos.write_file"), visibleTools.toString());
        assertTrue(visibleTools.contains("talos.edit_file"), visibleTools.toString());
        assertFalse(visibleTools.contains("talos.mkdir"), visibleTools.toString());
        assertEquals("MUST_MUTATE", roleFor(trace, "styles.css"));
        assertEquals("FORBIDDEN", roleFor(trace, "index.html"));
        assertEquals("FORBIDDEN", roleFor(trace, "scripts.js"));
        assertTrue(ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state).isEmpty());
    }

    @Test
    void explicitForbiddenTargetsAndConstraintTargetsDoNotBecomeMutationProgress() {
        String prompt = "Rewrite styles.css so index.html still works. "
                + "Do not edit index.html. Do not edit scripts.js.";

        TaskContract contract = TaskContractResolver.fromUserRequest(prompt);
        List<String> visibleTools = ToolSurfacePlanner.defaultVisibleToolNames(contract, ExecutionPhase.APPLY);
        TurnPolicyTrace trace = TurnPolicyTrace.from(contract, "APPLY", visibleTools, visibleTools);
        LoopState state = state(prompt, Path.of("."));
        state.toolOutcomes.add(successfulWrite("styles.css"));

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationAllowed());
        assertEquals(Set.of("styles.css"), contract.expectedTargets());
        assertEquals(Set.of("index.html", "scripts.js"), contract.forbiddenTargets());
        assertEquals("MUST_MUTATE", roleFor(trace, "styles.css"));
        assertEquals("FORBIDDEN", roleFor(trace, "index.html"));
        assertEquals("FORBIDDEN", roleFor(trace, "scripts.js"));
        assertTrue(ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state).isEmpty());
    }

    @Test
    void verifyOnlyConstraintTargetDoesNotBecomeMutationProgress() {
        String prompt = "Rewrite styles.css so index.html still works.";

        TaskContract contract = TaskContractResolver.fromUserRequest(prompt);
        TurnPolicyTrace trace = TurnPolicyTrace.from(
                contract,
                "APPLY",
                ToolSurfacePlanner.defaultVisibleToolNames(contract, ExecutionPhase.APPLY),
                List.of());
        LoopState state = state(prompt, Path.of("."));
        state.toolOutcomes.add(successfulWrite("styles.css"));

        assertEquals(Set.of("styles.css"), contract.expectedTargets());
        assertFalse(contract.expectedTargets().contains("index.html"));
        assertEquals("MUST_MUTATE", roleFor(trace, "styles.css"));
        assertEquals("VERIFY_ONLY", roleFor(trace, "index.html"));
        assertTrue(ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state).isEmpty());
    }

    @Test
    void readOnlyExistenceUsesReadOnlyRolesToolsAndEvidenceGuard() {
        String prompt = "Check whether scripts.js exists and whether script.js exists. Do not change anything.";

        TaskContract contract = TaskContractResolver.fromUserRequest(prompt);
        List<String> visibleTools = ToolSurfacePlanner.defaultVisibleToolNames(contract, ExecutionPhase.INSPECT);
        TurnPolicyTrace trace = TurnPolicyTrace.from(contract, "INSPECT", visibleTools, visibleTools);
        EvidenceObligation obligation = EvidenceObligationPolicy.derive(
                contract,
                ExecutionPhase.INSPECT,
                Path.of(".").toAbsolutePath());

        assertFalse(contract.mutationAllowed());
        assertEquals(List.of("talos.list_dir", "talos.read_file"), visibleTools);
        assertEquals(EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, obligation);
        assertFalse(trace.rolefulTargets().stream().anyMatch(target -> "MUST_MUTATE".equals(target.role())));
        assertEquals("MUST_READ", roleFor(trace, "scripts.js"));
        assertEquals("MUST_READ", roleFor(trace, "script.js"));
        assertEquals(
                EvidenceObligationVerifier.Status.UNSATISFIED,
                EvidenceObligationVerifier.verify(
                        obligation,
                        contract.expectedTargets(),
                        List.of(read("styles.css"))).status());
        assertEquals(
                EvidenceObligationVerifier.Status.SATISFIED,
                EvidenceObligationVerifier.verify(
                        obligation,
                        contract.expectedTargets(),
                        List.of(listDir("index.html\nscripts.js\nstyles.css\n"))).status());
    }

    @Test
    void workspaceReconciliationUsesObservedPluralFilesAndDoesNotGuessAmbiguousPairs(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("scripts.js"), "console.log('existing');\n");
        Files.writeString(workspace.resolve("styles.css"), "body { margin: 0; }\n");
        String prompt = "Create a modern synthwave website here with CSS styling and JavaScript interaction.";
        TaskContract raw = TaskContractResolver.fromUserRequest(prompt);

        TaskContract reconciled = WorkspaceTargetReconciler.reconcile(raw, workspace);
        LoopState state = state(prompt, workspace);
        state.toolOutcomes.add(successfulWrite("index.html"));
        state.toolOutcomes.add(successfulWrite("styles.css"));
        state.toolOutcomes.add(successfulWrite("scripts.js"));

        assertEquals(Set.of("index.html", "styles.css", "scripts.js"), reconciled.expectedTargets());
        assertFalse(reconciled.expectedTargets().contains("style.css"));
        assertFalse(reconciled.expectedTargets().contains("script.js"));
        assertTrue(ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state).isEmpty());

        Files.writeString(workspace.resolve("script.js"), "console.log('singular');\n");
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");

        TaskContract ambiguous = WorkspaceTargetReconciler.reconcile(raw, workspace);

        assertEquals(Set.of("index.html"), ambiguous.expectedTargets());
    }

    private static LoopState state(String userRequest, Path workspace) {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(userRequest))),
                workspace,
                null,
                null,
                5,
                0);
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                true,
                true,
                false,
                "wrote " + path,
                "");
    }

    private static ToolCallLoop.ToolOutcome read(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                true,
                false,
                false,
                "read " + path,
                "");
    }

    private static ToolCallLoop.ToolOutcome listDir(String summary) {
        return new ToolCallLoop.ToolOutcome(
                "talos.list_dir",
                ".",
                true,
                false,
                false,
                summary,
                "");
    }

    private static String roleFor(TurnPolicyTrace trace, String path) {
        return trace.rolefulTargets().stream()
                .filter(target -> path.equals(target.path()))
                .map(TurnPolicyTrace.RolefulTarget::role)
                .findFirst()
                .orElse("");
    }
}
