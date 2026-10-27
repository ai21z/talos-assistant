package dev.talos.runtime;

import dev.talos.core.security.Redactor;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.observation.TurnRequestFrame;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.WorkspaceResultEvidencePolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeLocationPresentationTest {
    private static final String REQUEST = "Where is this workspace?";
    @TempDir Path workspace;

    @AfterEach void closeFrame() {
        if (TurnRequestFrame.current() != null) TurnRequestFrame.current().close();
    }

    @Test void onlyTheRuntimeComposedAdmittedFieldIsPublished() {
        try (var frame = frame(true, false)) {
            var assessment = assessment();
            String answer = assessment.compose("Provider prose is untrusted.");
            var field = RuntimeLocationPresentation.issue(assessment, "Provider prose is untrusted.");
            assertNotNull(field);
            assertEquals(answer, project(field, answer));
            String forged = "Provider says " + workspace;
            assertEquals(new Redactor().redactBlock(forged), project(field, forged));
            assertFalse(field.toString().contains(workspace.toString()));
            assertTrue(field.answerIdentity().matches("[0-9a-f]{64}"));
        }
    }

    @Test void outerWhitespaceIsLayoutButChangedProseIsNot() {
        try (var frame = frame(true, false)) {
            var assessment = assessment();
            String answer = assessment.compose("");
            var field = RuntimeLocationPresentation.issue(assessment, "");
            assertNotNull(field);
            assertEquals(" \n" + answer + "\r\n  ", project(field, " \n" + answer + "\r\n  "));
            String changed = "Altered prefix\n" + answer;
            assertEquals(new Redactor().redactBlock(changed), project(field, changed));
        }
    }

    @Test void secretMaskingPrecedesTheLocationExceptionAndProjectedRevalidationIsStable() {
        try (var frame = frame(true, false)) {
            var assessment = assessment();
            var field = RuntimeLocationPresentation.issue(assessment, "");
            assertNotNull(field);
            String answer = assessment.compose("");
            Redactor redactor = new Redactor(Map.of("redact", Map.of("secrets", List.of(Pattern.quote(workspace.toString())))));
            String projected = field.project(redactor, answer, REQUEST, workspace, 7);
            assertFalse(projected.contains(workspace.toString()));
            assertEquals(projected, field.project(redactor, projected, REQUEST, workspace, 7));
        }
    }

    @Test void wrongRequestWorkspaceRevisionAndSanitationShiftDenyTheException() {
        try (var frame = frame(true, false)) {
            var assessment = assessment();
            var field = RuntimeLocationPresentation.issue(assessment, "");
            assertNotNull(field);
            String answer = assessment.compose("");
            String ordinary = new Redactor().redactBlock(answer);
            assertEquals(ordinary, field.project(new Redactor(), answer, REQUEST + " next", workspace, 7));
            assertEquals(ordinary, field.project(new Redactor(), answer, REQUEST, workspace.resolve("child"), 7));
            assertEquals(ordinary, field.project(new Redactor(), answer, REQUEST, workspace, 8));
            String controlled = "\u001b[31m" + answer;
            assertEquals(new Redactor().redactBlock(controlled), project(field, controlled));
        }
    }

    @Test void closedLaterAndWrongThreadFramesCannotReuseTheField() throws Exception {
        WorkspaceResultEvidencePolicy.Assessment assessment;
        RuntimeLocationPresentation field;
        String answer;
        try (var frame = frame(true, false)) {
            assessment = assessment();
            answer = assessment.compose("");
            field = RuntimeLocationPresentation.issue(assessment, "");
            assertNotNull(field);
            String wrongThread = CompletableFuture.supplyAsync(() -> project(field, answer)).get(2, TimeUnit.SECONDS);
            assertEquals(new Redactor().redactBlock(answer), wrongThread);
        }
        assertNull(RuntimeLocationPresentation.issue(assessment, ""));
        assertEquals(new Redactor().redactBlock(answer), project(field, answer));
        try (var later = frame(true, false)) {
            assertNull(RuntimeLocationPresentation.issue(assessment, ""));
            assertEquals(new Redactor().redactBlock(answer), project(field, answer));
        }
    }

    @Test void withheldPublicationIsFrozenAcrossCloseAndALaterPermissiveFrame() {
        WorkspaceResultEvidencePolicy.Assessment assessment;
        String answer;
        try (var frame = frame(false, false)) {
            assessment = assessment();
            answer = assessment.compose("");
            assertTrue(answer.contains("withheld"));
            assertFalse(answer.contains(workspace.toString()));
            assertNull(RuntimeLocationPresentation.issue(assessment, ""));
        }
        assertEquals(answer, assessment.compose(""));
        try (var later = frame(true, false)) {
            assertEquals(answer, assessment.compose(""));
            assertNull(RuntimeLocationPresentation.issue(assessment, ""));
        }
    }

    @Test void inferredUnframedAndUnresolvedRequestsCannotIssueTheField() {
        var legacy = assessment();
        assertNull(RuntimeLocationPresentation.issue(legacy, ""));
        assertFalse(new Redactor().redactBlock(legacy.compose("")).contains(workspace.toString()));
        try (var unresolved = frame(true, true)) {
            assertEquals(TurnRequestFrame.State.UNRESOLVED, unresolved.state());
            assertNull(RuntimeLocationPresentation.issue(assessment(), ""));
        }
    }

    private WorkspaceResultEvidencePolicy.Assessment assessment() {
        var contract = TaskContractResolver.resolveFromUserRequest(REQUEST).contract();
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT, List.of(), List.of(), List.of());
        return WorkspaceResultEvidencePolicy.assess(plan, null, workspace);
    }

    private TurnRequestFrame frame(boolean locationPublic, boolean unresolved) {
        var frame = TurnRequestFrame.open(REQUEST, workspace, 7, (owned, task) -> {
            var location = new RequestedResultInventory.RequestedResult("location-result",
                    new RequestedResultInventory.SourceRegion(0, REQUEST.length()),
                    RequestedResultInventory.Kind.WORKSPACE_LOCATION, "active workspace", "current turn",
                    RequestedResultInventory.EvidenceBasis.TURN_WORKSPACE);
            return new TurnRequestFrame.Meaning(new RequestedResultInventory(REQUEST, List.of(location), List.of()),
                    Set.of(), TurnRequestFrame.Publication.NO_NAMES, locationPublic, unresolved);
        });
        frame.adopt(TaskContractResolver.resolveFromUserRequest(REQUEST));
        return frame;
    }

    private String project(RuntimeLocationPresentation field, String answer) {
        return field.project(new Redactor(), answer, REQUEST, workspace, 7);
    }
}
