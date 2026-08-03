package dev.talos.runtime.task;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parent-compatible strict red for equal normalized workspace operands. */
class T1205SamePathWorkspaceOperationRedTest {

    @Test
    void copyMoveAndRenameNeverAuthorizeTheSameNormalizedPath()
            throws Exception {
        for (String request : List.of(
                "Copy ./same.txt to same.txt.",
                "Move ./same.txt to same.txt.",
                "Rename ./same.txt to same.txt.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            Object plan = invoke(resolution, "workspaceOperationPlan");
            @SuppressWarnings("unchecked")
            List<Object> authorized = (List<Object>) invoke(
                    plan,
                    "authorizedOperations");

            assertEquals("BLOCKED", invoke(plan, "state").toString(), request);
            assertTrue(authorized.isEmpty(), request);
            assertTrue((Boolean) invoke(plan, "blocksWorkspaceOperations"), request);
        }
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
}
