package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.CanonicalToolDescriptors;
import dev.talos.runtime.toolcall.ToolSurfacePlanner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NoWorkspaceAuthorityProjectionTest {
    private static final String FIELD_PROMPT =
            "Without opening any workspace file, explain in one sentence what a JSON configuration file usually contains.";

    @Test
    void blanketNoWorkspaceInstructionProjectsOneDirectAnswerOnlyCapability() {
        TaskContract contract = TaskContractResolver.fromUserRequest(FIELD_PROMPT);

        assertEquals(TaskType.SMALL_TALK, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        assertFalse(contract.verificationRequired());
        assertEquals(ActionObligation.DIRECT_ANSWER_ONLY,
                ActionObligationPolicy.derive(contract, ExecutionPhase.INSPECT));
        assertEquals(EvidenceObligation.NONE,
                EvidenceObligationPolicy.derive(contract, ExecutionPhase.INSPECT, Path.of(".")));
    }

    @Test
    void blanketNoWorkspaceInstructionKeepsPromptAndRuntimeToolSurfacesEmpty() {
        TaskContract contract = TaskContractResolver.fromUserRequest(FIELD_PROMPT);

        ToolSurfacePlanner.Plan runtime = ToolSurfacePlanner.plan(
                contract,
                ExecutionPhase.INSPECT,
                CanonicalToolDescriptors.registry());

        assertEquals(List.of(), runtime.nativeToolNames());
        assertEquals(List.of(), runtime.nativeToolSpecs());
        assertEquals(List.of(), ToolSurfacePlanner.defaultVisibleToolNames(contract, ExecutionPhase.INSPECT));
        assertEquals("small-talk", runtime.reason());
    }
}
