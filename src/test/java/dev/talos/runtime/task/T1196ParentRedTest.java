package dev.talos.runtime.task;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class T1196ParentRedTest {

    @Test
    void explicitMutationAfterConstraintRetainsBothTargets() {
        String request =
                "Now apply the smallest fix by editing index.html so the CSS and JavaScript "
                        + ".cta-button selector has a matching element in the HTML, "
                        + "and update style.css too.";

        assertEquals(
                Set.of("index.html", "style.css"),
                TaskContractResolver.fromUserRequest(request).expectedTargets());
    }

    @Test
    void reversedConstraintKeepsOnlyTheDirectMutationTarget() {
        assertEquals(
                Set.of("styles.css"),
                TaskContractResolver.fromUserRequest(
                        "Rewrite styles.css so index.html still works.")
                        .expectedTargets());
    }

    @Test
    void quotedImperativeCannotGrantAuthority() {
        assertEquals(
                Set.of("index.html"),
                TaskContractResolver.fromUserRequest(
                        "Edit index.html so the paragraph says \"and update style.css\".")
                        .expectedTargets());
    }

    @Test
    void optionalMutationCannotBecomeRequired() {
        assertEquals(
                Set.of("index.html"),
                TaskContractResolver.fromUserRequest(
                        "Edit index.html so it works, and update style.css if needed.")
                        .expectedTargets());
    }

    @Test
    void proposalOnlyMutationCannotBecomeRequired() {
        assertEquals(
                Set.of("index.html"),
                TaskContractResolver.fromUserRequest(
                        "Edit index.html so the ticket proposes: update style.css.")
                        .expectedTargets());
    }

    @Test
    void explicitPrimaryMutationRemainsAuthorized() {
        assertEquals(
                Set.of("index.html"),
                TaskContractResolver.fromUserRequest(
                        "Edit index.html so it works.")
                        .expectedTargets());
    }

    @Test
    void workspaceOperationCannotBecomeOrdinaryEditAuthority() {
        var contract = TaskContractResolver.fromUserRequest(
                "Edit index.html so it works, then delete style.css.");

        assertEquals(Set.of("index.html"), contract.expectedTargets());
        assertFalse(contract.expectedTargets().contains("style.css"));
    }
}
