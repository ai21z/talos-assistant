package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1205SamePathWorkspaceOperationInvariantTest {

    @Test
    void twoPathObligationRejectsEqualNormalizedOperandsForEveryKind() {
        for (WorkspaceOperationObligation.Kind kind : List.of(
                WorkspaceOperationObligation.Kind.COPY_PATH,
                WorkspaceOperationObligation.Kind.MOVE_PATH,
                WorkspaceOperationObligation.Kind.RENAME_PATH)) {
            String verb = switch (kind) {
                case COPY_PATH -> "Copy";
                case MOVE_PATH -> "Move";
                case RENAME_PATH -> "Rename";
                default -> throw new AssertionError(kind);
            };
            String request = verb + " ./same.txt to same.txt.";
            int firstStart = request.indexOf("./same.txt");
            int firstEnd = firstStart + "./same.txt".length();
            int secondStart = request.lastIndexOf("same.txt");
            int secondEnd = secondStart + "same.txt".length();

            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> WorkspaceOperationObligation.create(
                            WorkspaceOperationObligation.Owner
                                    .SEQUENTIAL_CLAUSE,
                            kind,
                            List.of(
                                    new WorkspaceOperationObligation.Operand(
                                            WorkspaceOperationObligation.Role
                                                    .SOURCE,
                                            "./same.txt",
                                            "./same.txt",
                                            firstStart,
                                            firstEnd),
                                    new WorkspaceOperationObligation.Operand(
                                            WorkspaceOperationObligation.Role
                                                    .DESTINATION,
                                            "same.txt",
                                            "same.txt",
                                            secondStart,
                                            secondEnd)),
                            WorkspaceOperationObligation.OverwritePolicy
                                    .FAIL_IF_EXISTS,
                            kind == WorkspaceOperationObligation.Kind.COPY_PATH
                                    ? WorkspaceOperationObligation
                                            .RecursivePolicy.NON_RECURSIVE
                                    : WorkspaceOperationObligation
                                            .RecursivePolicy.NOT_APPLICABLE,
                            WorkspaceOperationObligation.ParentPolicy
                                    .REQUIRE_EXISTING_PARENT,
                            request,
                            0,
                            verb.length(),
                            0,
                            request.length()));

            assertEquals(
                    "workspace operation source and destination must be distinct",
                    rejected.getMessage(),
                    kind.toString());
        }
    }

    @Test
    void parserFailsClosedForEverySameNormalizedTwoPathRequest() {
        for (String request : List.of(
                "Copy ./same.txt to same.txt.",
                "Copy same.txt to sub/../same.txt and overwrite it.",
                "Move ./same.txt to same.txt.",
                "Move same.txt to sub/../same.txt and overwrite it.",
                "Rename ./same.txt to same.txt.")) {
            WorkspaceOperationAuthorityPlan plan = TaskContractResolver
                    .resolveFromUserRequest(request)
                    .workspaceOperationPlan();

            assertEquals(
                    WorkspaceOperationAuthorityPlan.State.BLOCKED,
                    plan.state(),
                    request);
            assertEquals(
                    "workspace-operation-not-fully-typed",
                    plan.reason(),
                    request);
            assertTrue(plan.authorizedOperations().isEmpty(), request);
            assertTrue(plan.blocksWorkspaceOperations(), request);
        }
    }

    @Test
    void operandLexicalValueMustEqualItsExactCanonicalRequestSpan() {
        String request = "Copy source.md to copied.md.";
        int sourceStart = request.indexOf("source.md");
        int destinationStart = request.indexOf("copied.md");

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> WorkspaceOperationObligation.create(
                        WorkspaceOperationObligation.Owner.SEQUENTIAL_CLAUSE,
                        WorkspaceOperationObligation.Kind.COPY_PATH,
                        List.of(
                                new WorkspaceOperationObligation.Operand(
                                        WorkspaceOperationObligation.Role.SOURCE,
                                        "forged.md",
                                        "source.md",
                                        sourceStart,
                                        sourceStart + "source.md".length()),
                                new WorkspaceOperationObligation.Operand(
                                        WorkspaceOperationObligation.Role.DESTINATION,
                                        "copied.md",
                                        "copied.md",
                                        destinationStart,
                                        destinationStart + "copied.md".length())),
                        WorkspaceOperationObligation.OverwritePolicy
                                .FAIL_IF_EXISTS,
                        WorkspaceOperationObligation.RecursivePolicy
                                .NON_RECURSIVE,
                        WorkspaceOperationObligation.ParentPolicy
                                .REQUIRE_EXISTING_PARENT,
                        request,
                        0,
                        "Copy".length(),
                        0,
                        request.length()));

        assertEquals(
                "workspace operand lexical value differs from its canonical request span",
                rejected.getMessage());
    }
}
