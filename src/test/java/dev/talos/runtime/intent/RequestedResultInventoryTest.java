package dev.talos.runtime.intent;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.policy.EvidenceGate;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestedResultInventoryTest {
    private CurrentTurnPlan plan(String request) {
        return CurrentTurnPlan.create(TaskContractResolver.fromUserRequest(request), ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file"), List.of(), List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Can you read this workspace? where exactly are we and is it empty?",
            "Where is the active workspace and is this folder empty?",
            "Please show the entries in this workspace and tell me the workspace path."
    })
    void compoundWorkspaceFactsShareOneBoundedInventory(String request) {
        var plan = plan(request);
        var inventory = plan.requestedResults();
        assertTrue(inventory.boundedWorkspaceQuery(), inventory.toString());
        assertTrue(inventory.requires(RequestedResultInventory.Kind.WORKSPACE_LOCATION));
        assertTrue(inventory.requires(RequestedResultInventory.Kind.DIRECTORY_CONTENTS));
        assertFalse(inventory.requires(RequestedResultInventory.Kind.PROJECT_PURPOSE));
        assertEquals(EvidenceObligation.LIST_DIRECTORY_ONLY.name(), plan.evidenceObligation());
        assertEquals(EvidenceObligation.LIST_DIRECTORY_ONLY, EvidenceGate.selectObligation(plan, Path.of(".")));
        for (var result : inventory.results()) {
            assertFalse(request.substring(result.region().start(), result.region().end()).isBlank());
        }
    }

    @Test void mixedPurposeRetainsIndependentLocationAndSourceRequirement() {
        var plan = plan("Where exactly is this workspace, and what does this project do?");
        assertTrue(plan.requestedResults().boundedWorkspaceQuery());
        assertTrue(plan.requestedResults().requires(RequestedResultInventory.Kind.WORKSPACE_LOCATION));
        assertTrue(plan.requestedResults().requires(RequestedResultInventory.Kind.PROJECT_PURPOSE));
        assertEquals(EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED.name(), plan.evidenceObligation());
    }

    @ParameterizedTest
    @ValueSource(strings = {"What is this workspace path?", "Which is the active workspace location?"})
    void locationAttributeDoesNotAcquireAnOverlappingPurposeResult(String request) {
        var inventory = plan(request).requestedResults();
        assertTrue(inventory.boundedWorkspaceQuery(), inventory.toString());
        assertTrue(inventory.requires(RequestedResultInventory.Kind.WORKSPACE_LOCATION));
        assertFalse(inventory.requires(RequestedResultInventory.Kind.PROJECT_PURPOSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Where is this workspace and why is the database failing?",
            "Explain the quoted question: \"Where is this workspace?\"",
            "Where is this workspace? Do not inspect it.",
            "Where is this workspace and list src instead.",
            "Where is this workspace and read notes.txt?",
            "Is it empty?",
            "Where exactly are we?"
    })
    void unresolvedMaterialDoesNotAcquireBoundedCompleteness(String request) {
        assertFalse(plan(request).requestedResults().boundedWorkspaceQuery());
    }

    @Test void requestObligationSchemaIsNotPromotedByWorkspaceInventory() {
        var plan = plan("Where is this workspace?");
        assertTrue(plan.requestedResults().boundedWorkspaceQuery());
        assertEquals("NOT_MIGRATED", plan.requestObligation().derivationStatus().name());
    }

    @Test void oversizedRequestsAndResultCountsRemainExplicitlyUnestablished() {
        for (String request : List.of("Where is this workspace? ".repeat(400),
                "Where is this workspace? ".repeat(17))) {
            var inventory = RequestedResultInventory.compile(TaskContract.unknown(request));
            assertFalse(inventory.boundedWorkspaceQuery());
            assertTrue(inventory.results().isEmpty());
            assertEquals(List.of(new RequestedResultInventory.SourceRegion(0, request.length())),
                    inventory.unresolvedRegions());
            assertEquals(request, inventory.originalRequest());
        }
    }
}
