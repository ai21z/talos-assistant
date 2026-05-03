package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderRequestControlPolicyTest {

    @Test
    void mutatingObligationRequiresToolChoiceWhenSupportedAndWriteToolsVisible() {
        var contract = TaskContractResolver.fromUserRequest("Create scripts.js with a click handler.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file", "talos.edit_file"),
                List.of("talos.write_file", "talos.edit_file"),
                List.of());

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(tool("talos.write_file"), tool("talos.edit_file")),
                true);

        assertEquals(ToolChoiceMode.REQUIRED, controls.toolChoice());
        assertEquals(List.of("action-obligation:MUTATING_TOOL_REQUIRED"), controls.debugTags());
    }

    @Test
    void evidenceObligationRequiresToolChoiceWhenSupportedAndReadToolsVisible() {
        var contract = TaskContractResolver.fromUserRequest("Inspect this project and explain what it does.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.read_file", "talos.grep"),
                List.of("talos.read_file", "talos.grep"),
                List.of());

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(tool("talos.read_file"), tool("talos.grep")),
                true);

        assertEquals(ToolChoiceMode.REQUIRED, controls.toolChoice());
        assertEquals(List.of("action-obligation:INSPECT_REQUIRED",
                "evidence-obligation:WORKSPACE_INSPECTION_REQUIRED"), controls.debugTags());
    }

    @Test
    void directAnswerDoesNotForceTools() {
        var contract = TaskContractResolver.fromUserRequest("Hello, what can you do?");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.INSPECT,
                List.of(),
                List.of(),
                List.of());

        var controls = ProviderRequestControlPolicy.forTurn(plan, List.of(), true);

        assertEquals(ToolChoiceMode.AUTO, controls.toolChoice());
    }

    @Test
    void unsupportedBackendDoesNotForceTools() {
        var contract = TaskContractResolver.fromUserRequest("Create scripts.js with a click handler.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(tool("talos.write_file")),
                false);

        assertEquals(ToolChoiceMode.AUTO, controls.toolChoice());
    }

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, "test tool", "{}");
    }
}
