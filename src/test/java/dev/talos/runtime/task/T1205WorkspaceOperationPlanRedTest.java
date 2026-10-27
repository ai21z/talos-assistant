package dev.talos.runtime.task;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.toolcall.CanonicalToolDescriptors;
import dev.talos.runtime.toolcall.ToolCapabilityAvailability;
import dev.talos.runtime.toolcall.ToolSurfacePlanner;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parent-compatible strict reds for the T1205 typed operation boundary. */
class T1205WorkspaceOperationPlanRedTest {

    @Test
    void mixedWorkspaceFamiliesCompileOneTypedAuthorityInsteadOfRemainingBlocked()
            throws Exception {
        for (Fixture fixture : List.of(
                new Fixture(
                        "Edit index.html so it works, then copy style.css to theme.css.",
                        "COPY_PATH", "style.css", "theme.css", ""),
                new Fixture(
                        "Edit index.html so it works, then move style.css to theme.css.",
                        "MOVE_PATH", "style.css", "theme.css", ""),
                new Fixture(
                        "Edit index.html so it works, then rename style.css to theme.css.",
                        "RENAME_PATH", "style.css", "theme.css", ""),
                new Fixture(
                        "Edit index.html so it works, then create a directory named assets.",
                        "MKDIR", "", "", "assets"),
                new Fixture(
                        "Edit index.html so it works, then delete style.css.",
                        "DELETE_PATH", "", "", "style.css"))) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(fixture.request());

            Object plan = invoke(resolution, "workspaceOperationPlan");
            @SuppressWarnings("unchecked")
            List<Object> operations = (List<Object>) invoke(plan, "operations");

            assertEquals(1, operations.size(), fixture.request());
            Object operation = operations.getFirst();
            assertEquals(fixture.kind(), invoke(operation, "kind").toString(), fixture.request());
            assertEquals("AUTHORIZED", invoke(operation, "status").toString(), fixture.request());
            assertEquals(fixture.source(), invoke(operation, "sourcePath"), fixture.request());
            assertEquals(fixture.destination(), invoke(operation, "destinationPath"), fixture.request());
            assertEquals(fixture.target(), invoke(operation, "targetPath"), fixture.request());
            assertFalse(resolution.blocksWorkspaceOperations(), fixture.request());
        }
    }

    @Test
    void standaloneOperationAlsoUsesTheStoredTypedPlan() throws Exception {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(
                "Copy README.md to docs/README-copy.md.");

        Object plan = invoke(resolution, "workspaceOperationPlan");
        @SuppressWarnings("unchecked")
        List<Object> operations = (List<Object>) invoke(plan, "operations");

        assertEquals(1, operations.size());
        Object operation = operations.getFirst();
        assertEquals("COPY_PATH", invoke(operation, "kind").toString());
        assertEquals("README.md", invoke(operation, "sourcePath"));
        assertEquals("docs/README-copy.md", invoke(operation, "destinationPath"));
        assertEquals("", invoke(operation, "targetPath"));
        assertEquals("FAIL_IF_EXISTS", invoke(operation, "overwritePolicy").toString());
        assertEquals("NON_RECURSIVE", invoke(operation, "recursivePolicy").toString());
        assertEquals("REQUIRE_EXISTING_PARENT", invoke(operation, "parentPolicy").toString());
        assertNotNull(invoke(operation, "operationId"));
        assertNotNull(invoke(plan, "planId"));
    }

    @Test
    void explicitFlagsAreStoredInsteadOfBeingInferredDownstream()
            throws Exception {
        TaskResolution copy = TaskContractResolver.resolveFromUserRequest(
                "Copy a.txt to b.txt recursively and overwrite it.");
        Object copyOperation = firstOperation(copy);
        assertEquals("OVERWRITE_EXPLICIT",
                invoke(copyOperation, "overwritePolicy").toString());
        assertEquals("RECURSIVE_EXPLICIT",
                invoke(copyOperation, "recursivePolicy").toString());
        assertEquals("REQUIRE_EXISTING_PARENT",
                invoke(copyOperation, "parentPolicy").toString());

        TaskResolution delete = TaskContractResolver.resolveFromUserRequest(
                "Delete archive/source-dir recursively.");
        Object deleteOperation = firstOperation(delete);
        assertEquals("NOT_APPLICABLE",
                invoke(deleteOperation, "overwritePolicy").toString());
        assertEquals("RECURSIVE_EXPLICIT",
                invoke(deleteOperation, "recursivePolicy").toString());
        assertEquals("NOT_APPLICABLE",
                invoke(deleteOperation, "parentPolicy").toString());
    }

    @Test
    void restrictiveWorkspaceTextNeverProducesExecutableAuthority() throws Exception {
        for (String request : List.of(
                "Edit index.html. The report says rename style.css to theme.css.",
                "Edit index.html. Maybe rename style.css to theme.css.",
                "Edit index.html. Do not rename style.css to theme.css.",
                "Edit index.html. As a proposal, rename style.css to theme.css.",
                "Edit index.html. `rename style.css to theme.css`.",
                "Edit index.html. Rename style.css or theme.css.")) {
            TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
            Object plan = invoke(resolution, "workspaceOperationPlan");
            @SuppressWarnings("unchecked")
            List<Object> authorized = (List<Object>) invoke(plan, "authorizedOperations");
            assertTrue(authorized.isEmpty(), request);
        }
    }

    @Test
    void unresolvedDependencyTargetsFailClosedWhileExactRequiredTargetsRemainTyped()
            throws Exception {
        for (String request : List.of(
                "Edit index.html and update style.css as needed, "
                        + "then copy a.md to b.md.",
                "Edit index.html and use style.css as reference, "
                        + "then copy a.md to b.md.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            Object plan = invoke(resolution, "workspaceOperationPlan");

            assertEquals("BLOCKED", invoke(plan, "state").toString(),
                    request);
            @SuppressWarnings("unchecked")
            List<Object> authorized = (List<Object>) invoke(
                    plan,
                    "authorizedOperations");
            assertTrue(authorized.isEmpty(), request);
            assertTrue(resolution.blocksWorkspaceOperations(), request);
            assertFalse(visibleTools(resolution).contains(
                    "talos.copy_path"), request);
        }

        String required = "Edit index.html and update style.css, "
                + "then copy a.md to b.md.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(required);
        Object plan = invoke(resolution, "workspaceOperationPlan");
        assertEquals("AUTHORIZED_SINGLE", invoke(plan, "state").toString());
        assertEquals(
                List.of("index.html", "style.css"),
                invoke(resolution, "contentMutationPredecessorPaths"));
        assertTrue(visibleTools(resolution).contains("talos.copy_path"));
    }

    private static List<String> visibleTools(TaskResolution resolution) {
        return ToolSurfacePlanner.plan(
                resolution,
                ExecutionPhase.APPLY,
                CanonicalToolDescriptors.registry(),
                ToolCapabilityAvailability.allEnabled())
                .nativeToolNames();
    }

    private static Object invoke(Object target, String method) throws Exception {
        assertNotNull(target, "reflection target");
        Method accessor;
        try {
            accessor = target.getClass().getMethod(method);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(
                    "T1205 typed accessor is absent: "
                            + target.getClass().getName() + "." + method + "()",
                    missing);
        }
        return accessor.invoke(target);
    }

    private static Object firstOperation(TaskResolution resolution)
            throws Exception {
        Object plan = invoke(resolution, "workspaceOperationPlan");
        @SuppressWarnings("unchecked")
        List<Object> operations = (List<Object>) invoke(
                plan,
                "authorizedOperations");
        assertEquals(1, operations.size());
        return operations.getFirst();
    }

    private record Fixture(
            String request,
            String kind,
            String source,
            String destination,
            String target
    ) {}
}
