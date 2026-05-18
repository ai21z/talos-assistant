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

    public record Step(String descriptionContains, String detailContains, ApprovalResponse response) {
        public Step {
            descriptionContains = normalize(descriptionContains);
            detailContains = normalize(detailContains);
            response = response == null ? ApprovalResponse.DENIED : response;
        }

        public static Step approve(String descriptionContains, String detailContains) {
            return new Step(descriptionContains, detailContains, ApprovalResponse.APPROVED);
        }

        public static Step deny(String descriptionContains, String detailContains) {
            return new Step(descriptionContains, detailContains, ApprovalResponse.DENIED);
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
        if (cursor >= steps.size()) {
            throw new AssertionError("Unexpected approval prompt: " + safe(description));
        }
        Step expected = steps.get(cursor++);
        assertContains("approval description", safe(description), expected.descriptionContains());
        assertContains("approval detail", safe(detail), expected.detailContains());
        Event event = new Event(description, detail, SYNTHETIC_PROMPT, expected.response());
        events.add(event);
        return event.response();
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public void assertExhausted() {
        if (cursor != steps.size()) {
            throw new AssertionError("Expected " + steps.size() + " approval prompt(s), observed " + cursor + ".");
        }
    }

    private static void assertContains(String label, String actual, String expected) {
        if (expected.isBlank()) return;
        String actualLower = actual.toLowerCase(Locale.ROOT);
        String expectedLower = expected.toLowerCase(Locale.ROOT);
        if (!actualLower.contains(expectedLower)) {
            throw new AssertionError("Expected " + label + " to contain [" + expected + "], actual: " + actual);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
