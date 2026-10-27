package dev.talos.runtime.policy;

import dev.talos.core.Config;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderRequestControlPolicyTest {

    @Test
    void exactSelectedNativeSurfaceOwnsProviderToolAuthority() {
        CurrentTurnPlan plan = plan(
                List.of("talos.read_file"),
                List.of("talos.read_file"));

        var controls = ProviderRequestControlPolicy.forTurn(
                plan, List.of(tool("talos.read_file")), true);

        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                controls.toolAuthorityMode());
    }

    @Test
    void exactSelectedPromptOnlySurfaceOwnsJsonTextAuthority() {
        CurrentTurnPlan plan = plan(
                List.of(),
                List.of("talos.read_file"));

        var controls = ProviderRequestControlPolicy.forTurn(
                plan, List.of(tool("talos.read_file")), false);

        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON,
                controls.toolAuthorityMode());
    }

    @Test
    void nativeAuthorityWinsWhenBothSelectedSurfacesContainTheVisibleTool() {
        CurrentTurnPlan plan = plan(
                List.of("talos.read_file"),
                List.of("talos.read_file"));

        var controls = ProviderRequestControlPolicy.forTurn(
                plan, List.of(tool("talos.read_file")), false);

        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                controls.toolAuthorityMode());
    }

    @Test
    void emptyOrMismatchedVisibleSurfaceCannotCreateProviderToolAuthority() {
        CurrentTurnPlan promptRead = plan(
                List.of(),
                List.of("talos.read_file"));

        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.NONE,
                ProviderRequestControlPolicy.forTurn(
                        promptRead, List.of(tool("talos.write_file")), false)
                        .toolAuthorityMode());
        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.NONE,
                ProviderRequestControlPolicy.forTurn(
                        promptRead, List.of(), false).toolAuthorityMode());
        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.NONE,
                ProviderRequestControlPolicy.forTurn(
                        null, List.of(tool("talos.read_file")), false)
                        .toolAuthorityMode());
    }

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
        assertEquals(1024, controls.maxOutputTokens());
    }

    @Test
    void conditionalReviewFixRequiresToolChoiceWithoutMutatingTag() {
        var contract = TaskContractResolver.fromUserRequest(
                "Review the BMI calculator you just created and fix any obvious issue "
                        + "that would stop it from working in a browser.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                List.of());

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(tool("talos.read_file"), tool("talos.write_file"), tool("talos.edit_file")),
                true);

        assertEquals(ToolChoiceMode.REQUIRED, controls.toolChoice());
        assertEquals(List.of("action-obligation:CONDITIONAL_REVIEW_FIX"), controls.debugTags());
        assertEquals(1024, controls.maxOutputTokens());
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
        assertEquals(512, controls.maxOutputTokens());
    }

    @Test
    void unnamedFileLookupRequiresSourceEvidenceToolFromFirstGeneration() {
        var contract = TaskContractResolver.fromUserRequest(
                "Locate the service port, name the file, and quote only the relevant key and value. "
                        + "Do not edit anything.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.read_file", "talos.grep", "talos.retrieve"),
                List.of("talos.read_file", "talos.grep", "talos.retrieve"),
                List.of());

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(tool("talos.read_file"), tool("talos.grep"), tool("talos.retrieve")),
                true);

        assertEquals("FILE_CONTENT_LOOKUP_REQUIRED", plan.evidenceObligation());
        assertEquals(ToolChoiceMode.REQUIRED, controls.toolChoice());
        assertEquals(List.of("evidence-obligation:FILE_CONTENT_LOOKUP_REQUIRED"), controls.debugTags());
        assertEquals(512, controls.maxOutputTokens());
    }

    @Test
    void explicitCommandProfileRequestRequiresRunCommandToolChoice() {
        var contract = TaskContractResolver.fromUserRequest(
                "Run the approved Gradle test command profile for this workspace and report the exact command result. "
                        + "Do not invent a pass if the command cannot run.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.VERIFY,
                List.of("talos.run_command"),
                List.of("talos.run_command"),
                List.of());

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(tool("talos.run_command")),
                true);

        assertEquals("explicit-command-verification-request", contract.classificationReason());
        assertEquals(ToolChoiceMode.REQUIRED, controls.toolChoice());
        assertEquals(List.of("action-obligation:VERIFY_FROM_EVIDENCE",
                "evidence-obligation:VERIFY_FROM_TRACE_OR_EVIDENCE",
                "required-tool:talos.run_command"), controls.debugTags());
        assertEquals(512, controls.maxOutputTokens());
    }

    @Test
    void multiOperationContractCannotCreateWorkspaceProviderAuthority() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Use talos.apply_workspace_batch to create directory docs/reports and copy source.md "
                        + "to docs/reports/source-copy.md in one batch.");
        CurrentTurnPlan plan = workspacePlan(resolution, List.of());
        assertEquals(ActionObligation.NONE, plan.actionObligation());
        assertEquals(List.of(), plan.nativeTools());

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(),
                true,
                true);

        assertEquals(ToolChoiceMode.AUTO, controls.toolChoice());
        assertEquals("", controls.namedTool());
        assertEquals(List.of(), controls.debugTags());
    }

    @Test
    void workspaceObligationUsesNamedChoiceWhenSingleWorkspaceToolVisible() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Copy source.md to source-copy.md.");
        CurrentTurnPlan plan = workspacePlan(
                resolution,
                List.of("talos.copy_path"));
        assertEquals(ActionObligation.WORKSPACE_OPERATION_REQUIRED, plan.actionObligation());

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(tool("talos.copy_path")),
                true,
                true);

        assertEquals(ToolChoiceMode.NAMED, controls.toolChoice());
        assertEquals("talos.copy_path", controls.namedTool());
        assertEquals(List.of(
                "action-obligation:WORKSPACE_OPERATION_REQUIRED",
                "required-tool:talos.copy_path"), controls.debugTags());
        assertEquals(1024, controls.maxOutputTokens());
    }

    @Test
    void workspaceObligationFallsBackToRequiredWithoutNamedSupport() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Copy source.md to source-copy.md.");
        CurrentTurnPlan plan = workspacePlan(
                resolution,
                List.of("talos.copy_path"));

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(tool("talos.copy_path")),
                true,
                false);

        assertEquals(ToolChoiceMode.REQUIRED, controls.toolChoice());
        assertEquals("", controls.namedTool());
    }

    @Test
    void workspaceObligationDoesNotForceToolsOnUnsupportedBackend() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Copy source.md to source-copy.md.");
        CurrentTurnPlan plan = workspacePlan(
                resolution,
                List.of("talos.copy_path"));

        var controls = ProviderRequestControlPolicy.forTurn(
                plan,
                List.of(tool("talos.copy_path")),
                false,
                true);

        assertEquals(ToolChoiceMode.AUTO, controls.toolChoice());
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
    void obligationTurnsCarryNearGreedySamplingAndDirectAnswersDoNot() {
        var mutating = TaskContractResolver.fromUserRequest("Create scripts.js with a click handler.");
        CurrentTurnPlan mutatingPlan = CurrentTurnPlan.create(
                mutating,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());
        var obligationControls = ProviderRequestControlPolicy.forTurn(
                mutatingPlan, List.of(tool("talos.write_file")), true);
        assertEquals(SamplingControls.NEAR_GREEDY, obligationControls.sampling());

        var smallTalk = TaskContractResolver.fromUserRequest("Hello, what can you do?");
        CurrentTurnPlan directPlan = CurrentTurnPlan.create(
                smallTalk, ExecutionPhase.INSPECT, List.of(), List.of(), List.of());
        var directControls = ProviderRequestControlPolicy.forTurn(directPlan, List.of(), true);
        assertEquals(SamplingControls.none(), directControls.sampling());
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
        assertEquals(1024, controls.maxOutputTokens());
    }

    @Test
    void unsupportedBackendStillCapsInspectionToolObligationWithoutForcingTools() {
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
                false);

        assertEquals(ToolChoiceMode.AUTO, controls.toolChoice());
        assertEquals(512, controls.maxOutputTokens());
    }

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, "test tool", "{}");
    }

    private static CurrentTurnPlan plan(
            List<String> nativeTools,
            List<String> promptTools) {
        return CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(
                        "Inspect README.md and report what it contains."),
                ExecutionPhase.INSPECT,
                nativeTools,
                promptTools,
                List.of());
    }

    private static CurrentTurnPlan workspacePlan(
            dev.talos.runtime.task.TaskResolution resolution,
            List<String> visibleTools
    ) {
        return CurrentTurnPlan.createWithActionObligation(
                resolution,
                ExecutionPhase.APPLY,
                visibleTools,
                visibleTools,
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.derivedVerifierProfile(
                        resolution.contract()),
                ActionObligationPolicy.derive(
                        resolution,
                        ExecutionPhase.APPLY),
                new Config());
    }
}
