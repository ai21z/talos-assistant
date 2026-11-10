package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallDispatch;
import dev.talos.runtime.ToolCallDispatch.OperationKind;
import dev.talos.runtime.ToolCallDispatch.ProposedChange;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.outcome.MutationOutcomeStatus;
import dev.talos.runtime.outcome.MutationRecoveryAssessment;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.verification.TaskVerificationEvidence;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * D25 option B, review rounds 1 to 3: a call that never ran records, in its dispatch witness, the typed change it
 * proposed. The operation kind comes from the same tool-name resolution that chooses the operand roles. A copy
 * changes its destination, a move or rename its source and destination, a delete or mkdir its target, a file tool
 * its path argument. The change is complete only when every operand the kind changes is named and every named
 * operand could be kept. The outcomes come from the real refusal producers.
 */
class ToolOutcomeProposedChangePathsTest {
    @Test
    void aWorkspaceOperationRecordsItsKindAndThePathsItWouldChangeByRole() {
        assertEquals(new ProposedChange(OperationKind.COPY, List.of("index.html"),
                        List.of("draft/index.html", "index.html"), true),
                change(new ToolCall("talos.copy_path", Map.of("from", "draft/index.html", "to", "index.html"))));
        assertEquals(List.of("index.bak"), change(new ToolCall("talos.copy_path",
                Map.of("source", "index.html", "destination", "index.bak"))).changedPaths());
        assertEquals(new ProposedChange(OperationKind.MOVE, List.of("a.md", "b.md"), List.of("a.md", "b.md"), true),
                change(new ToolCall("talos.move_path", Map.of("from", "a.md", "to", "b.md"))));
        assertEquals(new ProposedChange(OperationKind.RENAME, List.of("docs/a.md", "docs/b.md"),
                        List.of("docs/a.md", "docs/b.md"), true),
                change(new ToolCall("talos.rename_path", Map.of("path", "docs/a.md", "new_name", "b.md"))));
        assertEquals(new ProposedChange(OperationKind.DELETE, List.of("old.md"), List.of("old.md"), true),
                change(new ToolCall("talos.delete_path", Map.of("path", "old.md"))));
        assertEquals(new ProposedChange(OperationKind.MKDIR, List.of("site"), List.of("site"), true),
                change(new ToolCall("talos.mkdir", Map.of("path", "site"))));
        assertEquals(new ProposedChange(OperationKind.BATCH, List.of(), List.of(), false),
                change(new ToolCall("talos.apply_workspace_batch", Map.of("path", ".", "operations_json", "[]"))));
    }

    /**
     * Round 3, defect 1: the kind comes from the producer's own name resolution, so a folder delete under a rejected
     * namespace is still a delete.
     */
    @Test
    void aRejectedNamespaceKeepsTheOperationKind() {
        assertEquals(OperationKind.DELETE, change(new ToolCall("functions.delete_path", Map.of("path", "docs"))).kind());
    }

    /**
     * Rounds 2 and 3: an operation whose operands are not all named, or whose operand cannot be kept, is incomplete.
     * The operands that are named and that the operation changes are still recorded, because each of them is a file
     * the call would change.
     */
    @Test
    void anIncompleteOrUnkeptOperandSetIsRecordedAsIncomplete() {
        assertEquals(new ProposedChange(OperationKind.MOVE, List.of("draft.txt"), List.of("draft.txt"), false),
                change(new ToolCall("talos.move_path", Map.of("src", "draft.txt", "dst", "notes.md"))));
        assertEquals(new ProposedChange(OperationKind.RENAME, List.of("draft.md"), List.of("draft.md"), false),
                change(new ToolCall("talos.rename_path", Map.of("from", "draft.md", "to", "notes.md"))));
        assertEquals(new ProposedChange(OperationKind.COPY, List.of(), List.of("notes.md"), false),
                change(new ToolCall("talos.copy_path", Map.of("from", "notes.md"))));
        assertEquals(new ProposedChange(OperationKind.DELETE, List.of(), List.of(), false),
                change(new ToolCall("talos.delete_path", Map.of("destination", "notes.md"))));
        assertEquals(new ProposedChange(OperationKind.MOVE, List.of("outside.md"), List.of("outside.md"), false),
                change(new ToolCall("talos.move_path", Map.of("from", "x".repeat(241) + ".md", "to", "outside.md"))));
    }

    @Test
    void aFileToolRecordsItsPathArgumentAndAModelSuppliedLineBreakIsDropped() {
        assertEquals(new ProposedChange(OperationKind.FILE, List.of("docs/a.md"), List.of("docs/a.md"), true),
                change(new ToolCall("talos.edit_file",
                        Map.of("path", "docs\\a.md", "old_string", "a", "new_string", "b"))));
        assertEquals(new ProposedChange(OperationKind.FILE, List.of(), List.of(), false),
                change(new ToolCall("talos.write_file", Map.of("path", "a.md\n- c.md", "content", "x"))));
        assertEquals(new ProposedChange(OperationKind.FILE, List.of(), List.of(), false),
                change(new ToolCall("talos.write_file", Map.of("content", "x"))));
    }

    @Test
    void everyRefusalProducerRecordsTheSameChange() {
        ToolCall copy = new ToolCall("talos.copy_path", Map.of("from", "draft/index.html", "to", "index.html"));
        ProposedChange expected = change(copy);

        assertEquals(expected, ToolOutcomeFactory.failedAuthority(copy, "authority").dispatch().proposedChange());
        assertEquals(expected, ToolOutcomeFactory.failedPreExecutionMutation(copy, "draft/index.html",
                        "refused", null, ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED)
                .dispatch().proposedChange());
        assertEquals(expected, ToolOutcomeFactory.requestDenied(copy,
                dev.talos.tools.ToolResult.fail(dev.talos.tools.ToolError.denied(
                        ToolFailureReason.PERMISSION_POLICY_DENIED, "denied")), null, true).dispatch().proposedChange());
        assertEquals(expected, ToolOutcomeFactory.executed(copy, "draft/index.html",
                dev.talos.tools.ToolResult.fail(dev.talos.tools.ToolError.denied(
                        ToolFailureReason.USER_APPROVAL_DENIED, "declined")), null, null, null, null, null,
                dev.talos.runtime.TurnRecord.ToolCallDisposition.BLOCKED).dispatch().proposedChange());
        assertEquals(List.of("index.html"), ToolCallDispatch.blocked("draft/index.html", expected).proposedChangedPaths());
    }

    /**
     * Round 4, critic item 1: a file tool's path under a key the tool does not read names no file it would change,
     * so the change is incomplete and the proposal is not identified by that path.
     */
    @Test
    void aFileToolPathUnderAKeyTheToolDoesNotReadIsIncomplete() {
        assertEquals(new ProposedChange(OperationKind.FILE, List.of(), List.of("src"), false),
                change(new ToolCall("talos.write_file", Map.of("dir", "src", "name", "orders.py", "content", "x"))));
        assertEquals(new ProposedChange(OperationKind.FILE, List.of("src/orders.py"), List.of("src/orders.py"), true),
                change(new ToolCall("talos.write_file", Map.of("file_path", "src/orders.py", "content", "x"))));
    }

    /**
     * Round 4 test gaps, through the real refusal producer into the assessment: an incomplete operation or a batch
     * after every requested file's last change keeps the turn partial as unidentified, and a refused folder delete
     * under an accepted or a rejected namespace keeps it partial as a possibility.
     */
    @Test
    void producerOutcomesKeepThePartialStatusWhenTheyMayAffectARequestedFile() {
        String h2 = "Change the function name get_qty to quantity_on_hand in inventory.py, orders.py and report.py, "
                + "including its definition, imports and calls, so that the code works as before.";
        for (ToolCall call : List.of(
                new ToolCall("talos.move_path", Map.of("from", "helper.py", "dst", "x.py")),
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", "[]")),
                new ToolCall("talos.write_file", Map.of("dir", ".", "name", "orders.py", "content", "x")))) {
            MutationRecoveryAssessment assessment = assess(h2, List.of(edited("inventory.py"), edited("orders.py"),
                    edited("report.py"), refused(call)));

            assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status(), call.toolName());
            assertEquals(MutationRecoveryAssessment.CoverageGap.UNIDENTIFIED_PROPOSAL,
                    assessment.requiredTargetCoverage().gap(), call.toolName());
        }
        String docs = "In docs/a.md and docs/b.md, replace alpha with beta.";
        for (String name : List.of("talos.delete_path", "functions.delete_path")) {
            MutationRecoveryAssessment assessment = assess(docs, List.of(edited("docs/a.md"), edited("docs/b.md"),
                    refused(new ToolCall(name, Map.of("path", "docs")))));

            assertEquals(MutationOutcomeStatus.PARTIAL, assessment.mutationOutcome().status(), name);
            assertEquals(List.of("docs/a.md", "docs/b.md"),
                    assessment.requiredTargetCoverage().changeNotRunPossible().stream().sorted().toList(), name);
        }
    }

    private static MutationRecoveryAssessment assess(String request, List<ToolCallLoop.ToolOutcome> outcomes) {
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        return MutationRecoveryAssessment.assess(contract, RequestObligationCompiler.compile(contract),
                new ToolCallLoop.LoopResult("answer", 1, outcomes.size(),
                        outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), List.of(),
                        (int) outcomes.stream().filter(outcome -> !outcome.success()).count(), 0, false,
                        (int) outcomes.stream().filter(outcome -> outcome.mutating() && outcome.success()).count(),
                        List.of(), 0, 0, 0, 0, outcomes),
                0, TaskVerificationEvidence.notRun("not run"), null);
    }

    private static ToolCallLoop.ToolOutcome edited(String path) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, true, true, false, "Edited " + path, "", null, "",
                null, ToolMutationEvidence.exactEdit("old", "new"), ToolFailureReason.NONE, List.of(),
                "call-edit-" + path).withDispatch(ToolCallDispatch.executed());
    }

    private static ToolCallLoop.ToolOutcome refused(ToolCall call) {
        return ToolOutcomeFactory.failedPreExecutionMutation(call, ToolCallSupport.resolvePathHint(call), "refused",
                null, ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED);
    }

    private static ProposedChange change(ToolCall call) {
        return ToolOutcomeFactory.failedPreExecutionMutation(call, ToolCallSupport.resolvePathHint(call), "refused",
                null, ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED).dispatch().proposedChange();
    }
}
