package dev.talos.harness;

import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fail-closed approval gate for synchronized approval audit runs.
 *
 * <p>This is deliberately stricter than the normal scenario enum policy:
 * every approval prompt must be expected, matched, recorded, and answered.
 * If a prompt appears early, late, or with unexpected text, the audit fails
 * at the approval boundary instead of letting scripted input drift into a
 * later user turn.
 */
public final class ScriptedApprovalGate implements ApprovalGate {

    public record Step(
            String descriptionContains,
            String detailContains,
            ApprovalResponse response,
            boolean optional,
            boolean repeatable
    ) {
        public Step {
            descriptionContains = normalize(descriptionContains);
            detailContains = normalize(detailContains);
            response = response == null ? ApprovalResponse.DENIED : response;
        }

        public Step(String descriptionContains, String detailContains, ApprovalResponse response, boolean optional) {
            this(descriptionContains, detailContains, response, optional, false);
        }

        public Step(String descriptionContains, String detailContains, ApprovalResponse response) {
            this(descriptionContains, detailContains, response, false, false);
        }

        public static Step approve(String descriptionContains, String detailContains) {
            return new Step(descriptionContains, detailContains, ApprovalResponse.APPROVED);
        }

        public static Step optionalApprove(String descriptionContains, String detailContains) {
            return new Step(descriptionContains, detailContains, ApprovalResponse.APPROVED, true);
        }

        public static Step deny(String descriptionContains, String detailContains) {
            return new Step(descriptionContains, detailContains, ApprovalResponse.DENIED);
        }

        public static Step optionalDeny(String descriptionContains, String detailContains) {
            return new Step(descriptionContains, detailContains, ApprovalResponse.DENIED, true);
        }

        public static Step repeatableOptionalDeny(String descriptionContains, String detailContains) {
            return new Step(descriptionContains, detailContains, ApprovalResponse.DENIED, true, true);
        }

        public static Step remember(String descriptionContains, String detailContains) {
            return new Step(descriptionContains, detailContains, ApprovalResponse.APPROVED_REMEMBER);
        }
    }

    public record Event(String description, String detail, String prompt, ApprovalResponse response) {
        public Event {
            description = description == null ? "" : description;
            detail = detail == null ? "" : detail;
            prompt = prompt == null ? "" : prompt;
            response = response == null ? ApprovalResponse.DENIED : response;
        }
    }

    private static final String SYNTHETIC_PROMPT = "Allow? [y=yes, a=yes for session, N=no]";
    private static final String SYNTHETIC_ONCE_PROMPT = "Allow? [y=yes, N=no]";

    private final List<Step> steps;
    private final List<Event> events = new ArrayList<>();
    private int cursor;

    public ScriptedApprovalGate(List<Step> steps) {
        this.steps = steps == null ? List.of() : List.copyOf(steps);
    }

    @Override
    public boolean approve(String description, String detail) {
        return approveFull(description, detail).isApproved();
    }

    @Override
    public ApprovalResponse approveFull(String description, String detail) {
        return approveMatching(description, detail, SYNTHETIC_PROMPT, false);
    }

    @Override
    public ApprovalResponse approveOnce(String description, String detail) {
        return approveMatching(description, detail, SYNTHETIC_ONCE_PROMPT, true);
    }

    private ApprovalResponse approveMatching(
            String description,
            String detail,
            String prompt,
            boolean collapseRemember
    ) {
        if (cursor >= steps.size()) {
            throw new AssertionError("Unexpected approval prompt: " + safe(description));
        }
        String safeDescription = safe(description);
        String safeDetail = safe(detail);
        Step expected = nextMatchingStep(safeDescription, safeDetail);
        ApprovalResponse response = collapseRemember && expected.response().isApproved()
                ? ApprovalResponse.APPROVED
                : expected.response();
        Event event = new Event(description, detail, prompt, response);
        events.add(event);
        return event.response();
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public void assertExhausted() {
        while (cursor < steps.size() && steps.get(cursor).optional()) {
            cursor++;
        }
        if (cursor != steps.size()) {
            throw new AssertionError("Expected " + steps.size() + " approval prompt(s), observed " + cursor + ".");
        }
    }

    private Step nextMatchingStep(String description, String detail) {
        while (cursor < steps.size()) {
            Step expected = steps.get(cursor);
            if (contains(description, expected.descriptionContains())
                    && contains(detail, expected.detailContains())) {
                if (!expected.repeatable()) {
                    cursor++;
                }
                return expected;
            }
            if (expected.optional()) {
                cursor++;
                continue;
            }
            assertContains("approval description", description, expected.descriptionContains());
            assertContains("approval detail", detail, expected.detailContains());
        }
        throw new AssertionError("Unexpected approval prompt: " + description);
    }

    private static void assertContains(String label, String actual, String expected) {
        if (!contains(actual, expected)) {
            throw new AssertionError("Expected " + label + " to contain [" + expected + "], actual: " + actual);
        }
    }

    private static boolean contains(String actual, String expected) {
        if (expected.isBlank()) return true;
        String actualLower = actual.toLowerCase(Locale.ROOT);
        String expectedLower = expected.toLowerCase(Locale.ROOT);
        return actualLower.contains(expectedLower);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
