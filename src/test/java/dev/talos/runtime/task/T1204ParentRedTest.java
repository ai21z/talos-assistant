package dev.talos.runtime.task;

import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1204ParentRedTest {

    @Test
    void readEditThenLaterSameTargetMutationRetainsCompositeRoleAndExactSpan() {
        assertCompositeLaterMutation(
                "Read style.css and edit index.html, then update style.css.",
                "style.css");
    }

    @Test
    void declaredTopLevelSequentialBoundariesRetainTheSameCompositeRole() {
        for (String request : List.of(
                "Read style.css and edit index.html, update style.css.",
                "Read style.css and edit index.html; update style.css.",
                "Read style.css and edit index.html\nUpdate style.css.",
                "Read style.css and edit index.html\r\nUpdate style.css.",
                "Read style.css and edit index.html. Update style.css.")) {
            assertCompositeLaterMutation(request, "style.css");
        }
    }

    @Test
    void reversedOrderAndThreeDirectClausesRetainEveryRequiredRole() {
        TaskResolution reversed = TaskContractResolver.resolveFromUserRequest(
                "Read index.html and edit style.css. Update index.html.");
        assertEquals(Set.of("index.html", "style.css"), reversed.contract().expectedTargets());
        assertEquals(Set.of("index.html"), reversed.contract().sourceEvidenceTargets());
        assertRole(reversed, "style.css", TargetRole.MUST_MUTATE);
        assertExactLaterRole(
                reversed,
                "index.html",
                TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE);

        TaskResolution three = TaskContractResolver.resolveFromUserRequest(
                "Edit index.html. Update style.css. Rewrite script.js.");
        assertEquals(
                Set.of("index.html", "style.css", "script.js"),
                three.contract().expectedTargets());
        assertRole(three, "index.html", TargetRole.MUST_MUTATE);
        assertExactLaterRole(three, "style.css", TargetRole.MUST_MUTATE);
        assertExactLaterRole(three, "script.js", TargetRole.MUST_MUTATE);
    }

    @Test
    void nonAuthoritativeLaterClausesCannotGrantMutationAuthority() {
        for (String request : List.of(
                "Read style.css and edit index.html. The ticket says update style.css.",
                "Read style.css and edit index.html. If needed, update style.css.",
                "Read style.css and edit index.html. Consider updating style.css.",
                "Read style.css and edit index.html. \"Update style.css.\"")) {
            TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
            assertEquals(Set.of("index.html"), resolution.contract().expectedTargets(), request);
            assertFalse(
                    resolution.intent().targets().find("style.css")
                            .map(target -> target.role() == TargetRole.MUST_MUTATE
                                    || target.role()
                                            == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE)
                            .orElse(false),
                    request);
        }
    }

    @Test
    void workspaceOperationInLaterClauseDoesNotBecomeContentMutationAuthority() {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(
                "Edit index.html. Delete style.css.");

        assertEquals(Set.of("index.html"), resolution.contract().expectedTargets());
        assertFalse(
                resolution.intent().targets().find("style.css")
                        .map(target -> target.role() == TargetRole.MUST_MUTATE
                                || target.role()
                                        == TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE)
                        .orElse(false));
    }

    private static void assertCompositeLaterMutation(String request, String target) {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertEquals(Set.of("index.html", target), resolution.contract().expectedTargets(), request);
        assertEquals(Set.of(target), resolution.contract().sourceEvidenceTargets(), request);
        assertRole(resolution, "index.html", TargetRole.MUST_MUTATE);
        assertExactLaterRole(
                resolution,
                target,
                TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE);
    }

    private static TargetRef assertRole(
            TaskResolution resolution,
            String path,
            TargetRole role
    ) {
        TargetRef ref = resolution.intent().targets().find(path).orElseThrow();
        assertEquals(role, ref.role(), resolution.intent().originalUserRequest());
        return ref;
    }

    private static void assertExactLaterRole(
            TaskResolution resolution,
            String path,
            TargetRole role
    ) {
        TargetRef ref = assertRole(resolution, path, role);
        String request = resolution.intent().originalUserRequest();
        assertEquals(TargetSource.USER_REQUEST, ref.derivation().source(), request);
        assertTrue(ref.derivation().startOffset() >= 0, request);
        assertTrue(ref.derivation().authorityEndOffset() > ref.derivation().startOffset(), request);
        assertTrue(ref.derivation().endOffset() > ref.derivation().authorityEndOffset(), request);
        assertEquals(
                ref.derivation().sourceText(),
                request.substring(
                        ref.derivation().startOffset(),
                        ref.derivation().endOffset()),
                request);
        assertTrue(
                ref.derivation().startOffset()
                        >= request.toLowerCase(java.util.Locale.ROOT)
                                .lastIndexOf("update ")
                        || request.toLowerCase(java.util.Locale.ROOT)
                                .substring(ref.derivation().startOffset())
                                .startsWith("rewrite "),
                request);
    }
}
