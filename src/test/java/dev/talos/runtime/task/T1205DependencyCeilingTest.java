package dev.talos.runtime.task;

import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.toolcall.ToolCapabilityAvailability;
import dev.talos.runtime.toolcall.ToolSurfacePlanner;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict T1205 dependency ceilings for unresolved T1202/T1203 authority. */
class T1205DependencyCeilingTest {

    @Test
    void unresolvedOptionalContentTargetBlocksExactWorkspaceOperation() {
        String request = "Edit index.html and update style.css as needed, "
                + "then copy a.md to b.md.";

        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                Set.of("style.css"),
                resolution.intent().targets().pathsByRole(
                        TargetRole.MAY_MUTATE),
                resolution.toString());
        assertEquals(
                WorkspaceOperationAuthorityPlan.State.BLOCKED,
                resolution.workspaceOperationPlan().state(),
                resolution.toString());
        assertEquals(
                "workspace-operation-unresolved-optional-mutation-target",
                resolution.workspaceOperationPlan().reason(),
                resolution.toString());
        assertEquals(
                "optional-mutation-target",
                resolution.intent().targets().find("style.css")
                        .orElseThrow().derivation().reason());
        assertNoWorkspaceOperationTools(resolution);
    }

    @Test
    void primaryReferenceAmbiguityBlocksExactWorkspaceOperation() {
        String request = "Edit index.html and use style.css as reference, "
                + "then copy a.md to b.md.";

        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                WorkspaceOperationAuthorityPlan.State.BLOCKED,
                resolution.workspaceOperationPlan().state(),
                resolution.toString());
        assertEquals(
                "workspace-operation-primary-content-authority-ambiguous",
                resolution.workspaceOperationPlan().reason(),
                resolution.toString());
        assertEquals(
                dev.talos.runtime.intent.IntentDerivation.UNKNOWN_OFFSET,
                resolution.intent().targets().find("style.css")
                        .orElseThrow().derivation().startOffset());
        assertNoWorkspaceOperationTools(resolution);
    }

    @Test
    void exactRequiredContentPredecessorsStillAuthorizeExactWorkspaceOperation() {
        String request = "Edit index.html and update style.css, "
                + "then copy a.md to b.md.";

        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                resolution.workspaceOperationPlan().state(),
                resolution.toString());
        assertEquals(
                java.util.List.of("index.html", "style.css"),
                resolution.contentMutationPredecessorPaths(),
                resolution.toString());
        for (String path : resolution.contentMutationPredecessorPaths()) {
            var derivation = resolution.intent().targets().find(path)
                    .orElseThrow().derivation();
            assertEquals(
                    "explicit-primary-content-mutation-binding",
                    derivation.reason(),
                    path);
            assertTrue(derivation.startOffset() >= 0, path);
            assertEquals(
                    derivation.sourceText(),
                    request.substring(
                            derivation.startOffset(),
                            derivation.endOffset()),
                    path);
        }
        var operation = resolution.workspaceOperationPlan()
                .authorizedOperations().getFirst();
        assertEquals("talos.copy_path", operation.toolName());
        assertEquals("a.md", operation.sourcePath());
        assertEquals("b.md", operation.destinationPath());

        var surface = ToolSurfacePlanner.plan(
                resolution,
                ExecutionPhase.APPLY,
                dev.talos.runtime.toolcall.CanonicalToolDescriptors.registry(),
                ToolCapabilityAvailability.allEnabled());
        assertTrue(surface.nativeToolNames().contains("talos.write_file"),
                surface.nativeToolNames().toString());
        assertTrue(surface.nativeToolNames().contains("talos.edit_file"),
                surface.nativeToolNames().toString());
        assertTrue(surface.nativeToolNames().contains("talos.copy_path"),
                surface.nativeToolNames().toString());
    }

    private static void assertNoWorkspaceOperationTools(
            TaskResolution resolution
    ) {
        var surface = ToolSurfacePlanner.plan(
                resolution,
                ExecutionPhase.APPLY,
                dev.talos.runtime.toolcall.CanonicalToolDescriptors.registry(),
                ToolCapabilityAvailability.allEnabled());
        assertFalse(surface.nativeToolNames().stream()
                        .anyMatch(name -> Set.of(
                                "talos.copy_path",
                                "talos.move_path",
                                "talos.rename_path",
                                "talos.mkdir",
                                "talos.delete_path").contains(name)),
                surface.nativeToolNames().toString());
    }
}
