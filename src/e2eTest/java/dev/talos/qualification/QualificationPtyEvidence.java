package dev.talos.qualification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact, version-dispatched representation of qualification true-PTY evidence. */
final class QualificationPtyEvidence {
    static final String SCHEMA_NAME = "talos.modelQualification.ptyCapture";
    static final Set<String> TRANSPORTS =
            Set.of("WINDOWS_PTY", "UNIX_PTY", "TEST_PTY");

    private static final Set<String> CORE_FIELDS = Set.of(
            "schemaName",
            "schemaVersion",
            "transport",
            "tty",
            "captureStartedAt",
            "captureEndedAt",
            "command",
            "workspace",
            "events",
            "rawOutput");
    private static final Set<String> EXTENDED_FIELDS = union(
            CORE_FIELDS,
            Set.of("expectedApprovalBehavior", "terminalOutcome", "captureFailure"));
    private static final Set<String> CLEANUP_FIELDS =
            union(EXTENDED_FIELDS, Set.of("processCleanup"));
    private static final Set<String> EVENT_FIELDS =
            Set.of("timestamp", "type", "data");
    private static final Set<String> APPROVAL_EVENT_FIELDS =
            Set.of("type", "data");
    private static final Set<String> CLEANUP_RESULT_FIELDS = Set.of(
            "outcome",
            "gracefulExitRequested",
            "campaignServerDiscoveryVerified",
            "capturedDescendantPids",
            "campaignServerPids",
            "normalStopPids",
            "forcedStopPids",
            "survivingPids",
            "errors");

    private QualificationPtyEvidence() {}

    static Capture parse(Path path, int expectedProtocol) throws IOException {
        return parse(NoFollowPathPolicy.readAllBytes(path), expectedProtocol);
    }

    static Capture parse(byte[] bytes, int expectedProtocol) throws IOException {
        return parse(QualificationJson.readTree(bytes), expectedProtocol);
    }

    static Capture parse(JsonNode root, int expectedProtocol) {
        requireSupportedProtocol(expectedProtocol);
        requireObject(root, "PTY capture");
        Set<String> fields = fields(root);
        if (!allowedTopLevelShapes(expectedProtocol).contains(fields)) {
            throw invalid("PTY capture has unsupported fields for protocol v"
                    + expectedProtocol + ": " + fields);
        }

        String schemaName = text(root, "schemaName");
        if (!SCHEMA_NAME.equals(schemaName)) {
            throw invalid("PTY capture schemaName is invalid");
        }
        int schemaVersion = integer(root, "schemaVersion");
        if (schemaVersion != expectedProtocol) {
            throw invalid("PTY capture schemaVersion does not match the expected protocol");
        }
        String transport = text(root, "transport");
        boolean tty = bool(root, "tty");
        String startedText = text(root, "captureStartedAt");
        String endedText = text(root, "captureEndedAt");
        Instant started = instant(startedText, "captureStartedAt");
        Instant ended = instant(endedText, "captureEndedAt");
        if (ended.isBefore(started)) {
            throw invalid("PTY capture ends before it starts");
        }
        String command = text(root, "command");
        String workspace = text(root, "workspace");
        String expectedApprovalBehavior =
                fields.contains("expectedApprovalBehavior")
                        ? text(root, "expectedApprovalBehavior") : null;
        if (expectedApprovalBehavior != null) {
            try {
                QualificationProtocol.ApprovalBehavior.valueOf(expectedApprovalBehavior);
            } catch (IllegalArgumentException unsupported) {
                throw invalid("PTY capture expectedApprovalBehavior is invalid");
            }
        }
        String terminalOutcome =
                fields.contains("terminalOutcome") ? text(root, "terminalOutcome") : null;
        String captureFailure =
                fields.contains("captureFailure") ? text(root, "captureFailure") : null;
        ProcessCleanup processCleanup =
                fields.contains("processCleanup")
                        ? parseCleanup(required(root, "processCleanup")) : null;

        List<Event> events = parseEvents(
                required(root, "events"), started, ended, true);
        String rawOutput = text(root, "rawOutput");
        String reconstructed = events.stream()
                .filter(event -> event.type().equals("output"))
                .map(Event::data)
                .collect(java.util.stream.Collectors.joining());
        if (!rawOutput.equals(reconstructed)) {
            throw invalid("PTY capture rawOutput differs from ordered output events");
        }
        return new Capture(
                schemaName,
                schemaVersion,
                transport,
                tty,
                startedText,
                endedText,
                command,
                workspace,
                expectedApprovalBehavior,
                terminalOutcome,
                captureFailure,
                processCleanup,
                events,
                rawOutput);
    }

    /**
     * Compatibility parser for the pre-versioned approvals command input. Authority
     * validation uses {@link #parse(JsonNode, int)} and passes its typed events.
     */
    static List<Event> parseApprovalEvents(JsonNode capture) {
        requireObject(capture, "PTY approval capture");
        requireFields(capture, Set.of("events"), "PTY approval capture");
        JsonNode eventArray = required(capture, "events");
        if (!eventArray.isArray()) {
            throw invalid("PTY capture events must be an array");
        }
        List<Event> events = new ArrayList<>();
        for (int index = 0; index < eventArray.size(); index++) {
            JsonNode event = eventArray.get(index);
            requireObject(event, "PTY approval event " + index);
            Set<String> keys = fields(event);
            if (!keys.equals(APPROVAL_EVENT_FIELDS) && !keys.equals(EVENT_FIELDS)) {
                throw invalid("PTY approval event has unsupported fields: " + keys);
            }
            String timestamp = keys.contains("timestamp")
                    ? text(event, "timestamp") : Instant.EPOCH.toString();
            if (keys.contains("timestamp")) {
                instant(timestamp, "event timestamp");
            }
            events.add(new Event(timestamp, text(event, "type"), text(event, "data")));
        }
        return List.copyOf(events);
    }

    static boolean validEvidence(
            Capture capture,
            String requestCommand,
            String requestTransport) {
        if (capture == null
                || !capture.tty()
                || !TRANSPORTS.contains(capture.transport())
                || capture.rawOutput().isBlank()
                || capture.workspace().isBlank()
                || capture.command().isBlank()
                || requestCommand == null
                || requestCommand.isBlank()
                || !"TRUE_PTY".equals(requestTransport)) {
            return false;
        }
        String normalizedCapture = capture.command().replace('\\', '/');
        String normalizedRequest = requestCommand.replace('\\', '/');
        if (!normalizedCapture.equals(normalizedRequest)
                && !normalizedCapture.endsWith("/" + normalizedRequest)) {
            return false;
        }
        boolean scenarioInput = false;
        boolean lastTrace = false;
        boolean nonemptyOutput = false;
        for (Event event : capture.events()) {
            if (event.type().equals("input") && event.data().equals("scenario-prompt")) {
                scenarioInput = true;
            }
            if (event.type().equals("input") && event.data().equals("last-trace")) {
                lastTrace = true;
            }
            if (event.type().equals("output") && !event.data().isBlank()) {
                nonemptyOutput = true;
            }
        }
        return scenarioInput && lastTrace && nonemptyOutput;
    }

    private static List<Event> parseEvents(
            JsonNode eventArray,
            Instant started,
            Instant ended,
            boolean requireTimestamp) {
        if (!eventArray.isArray()) {
            throw invalid("PTY capture events must be an array");
        }
        List<Event> events = new ArrayList<>();
        Instant previous = started;
        for (int index = 0; index < eventArray.size(); index++) {
            JsonNode event = eventArray.get(index);
            requireObject(event, "PTY event " + index);
            Set<String> expected = requireTimestamp ? EVENT_FIELDS : APPROVAL_EVENT_FIELDS;
            if (!fields(event).equals(expected)) {
                throw invalid("PTY event has unsupported fields: " + fields(event));
            }
            String timestamp = requireTimestamp
                    ? text(event, "timestamp") : Instant.EPOCH.toString();
            Instant observed = instant(timestamp, "event timestamp");
            if (observed.isBefore(previous) || observed.isAfter(ended)) {
                throw invalid("PTY event timestamp is outside the ordered capture window");
            }
            previous = observed;
            events.add(new Event(timestamp, text(event, "type"), text(event, "data")));
        }
        return List.copyOf(events);
    }

    private static ProcessCleanup parseCleanup(JsonNode node) {
        requireObject(node, "PTY processCleanup");
        requireFields(node, CLEANUP_RESULT_FIELDS, "PTY processCleanup");
        QualificationProcessCleanup.Outcome outcome;
        try {
            outcome = QualificationProcessCleanup.Outcome.valueOf(text(node, "outcome"));
        } catch (IllegalArgumentException invalid) {
            throw invalid("PTY processCleanup outcome is invalid");
        }
        return new ProcessCleanup(
                outcome,
                bool(node, "gracefulExitRequested"),
                bool(node, "campaignServerDiscoveryVerified"),
                longs(node, "capturedDescendantPids"),
                longs(node, "campaignServerPids"),
                longs(node, "normalStopPids"),
                longs(node, "forcedStopPids"),
                longs(node, "survivingPids"),
                texts(node, "errors"));
    }

    private static List<Long> longs(JsonNode parent, String name) {
        JsonNode array = required(parent, name);
        if (!array.isArray()) throw invalid(name + " must be an array");
        List<Long> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw invalid(name + " must contain exact integers");
            }
            long parsed = value.longValue();
            if (parsed <= 0) throw invalid(name + " must contain positive process IDs");
            values.add(parsed);
        }
        return List.copyOf(values);
    }

    private static List<String> texts(JsonNode parent, String name) {
        JsonNode array = required(parent, name);
        if (!array.isArray()) throw invalid(name + " must be an array");
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (!value.isTextual()) throw invalid(name + " must contain exact strings");
            values.add(value.textValue());
        }
        return List.copyOf(values);
    }

    private static List<Set<String>> allowedTopLevelShapes(int protocol) {
        return switch (protocol) {
            case 1 -> List.of(CORE_FIELDS);
            case 2 -> List.of(CORE_FIELDS, EXTENDED_FIELDS);
            case 3 -> List.of(EXTENDED_FIELDS, CLEANUP_FIELDS);
            case 4, 5, QualificationProtocol.VERSION -> List.of(CLEANUP_FIELDS);
            default -> throw invalid("unsupported qualification protocol: " + protocol);
        };
    }

    private static void requireSupportedProtocol(int protocol) {
        if (protocol < 1 || protocol > QualificationProtocol.VERSION) {
            throw invalid("unsupported qualification protocol: " + protocol);
        }
    }

    private static JsonNode required(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw invalid(name + " is required and must not be null");
        }
        return value;
    }

    private static String text(JsonNode parent, String name) {
        JsonNode value = required(parent, name);
        if (!value.isTextual()) throw invalid(name + " must be an exact string");
        return value.textValue();
    }

    private static boolean bool(JsonNode parent, String name) {
        JsonNode value = required(parent, name);
        if (!value.isBoolean()) throw invalid(name + " must be an exact boolean");
        return value.booleanValue();
    }

    private static int integer(JsonNode parent, String name) {
        JsonNode value = required(parent, name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(name + " must be an exact integer");
        }
        return value.intValue();
    }

    private static Instant instant(String value, String name) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw invalid(name + " must be an ISO-8601 instant");
        }
    }

    private static void requireObject(JsonNode value, String label) {
        if (value == null || !value.isObject()) {
            throw invalid(label + " must be a JSON object");
        }
    }

    private static void requireFields(JsonNode value, Set<String> expected, String label) {
        Set<String> actual = fields(value);
        if (!actual.equals(expected)) {
            throw invalid(label + " has unsupported fields: " + actual);
        }
    }

    private static Set<String> fields(JsonNode value) {
        Set<String> names = new HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Capture(
            String schemaName,
            int schemaVersion,
            String transport,
            boolean tty,
            String captureStartedAt,
            String captureEndedAt,
            String command,
            String workspace,
            String expectedApprovalBehavior,
            String terminalOutcome,
            String captureFailure,
            ProcessCleanup processCleanup,
            List<Event> events,
            String rawOutput) {
        Capture {
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(transport, "transport");
            Objects.requireNonNull(captureStartedAt, "captureStartedAt");
            Objects.requireNonNull(captureEndedAt, "captureEndedAt");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(workspace, "workspace");
            events = List.copyOf(events);
            Objects.requireNonNull(rawOutput, "rawOutput");
        }

        Instant startedAt() {
            return Instant.parse(captureStartedAt);
        }

        Instant endedAt() {
            return Instant.parse(captureEndedAt);
        }
    }

    record Event(String timestamp, String type, String data) {
        Event {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(data, "data");
        }
    }

    record ProcessCleanup(
            QualificationProcessCleanup.Outcome outcome,
            boolean gracefulExitRequested,
            boolean campaignServerDiscoveryVerified,
            List<Long> capturedDescendantPids,
            List<Long> campaignServerPids,
            List<Long> normalStopPids,
            List<Long> forcedStopPids,
            List<Long> survivingPids,
            List<String> errors) {
        ProcessCleanup {
            Objects.requireNonNull(outcome, "outcome");
            capturedDescendantPids = List.copyOf(capturedDescendantPids);
            campaignServerPids = List.copyOf(campaignServerPids);
            normalStopPids = List.copyOf(normalStopPids);
            forcedStopPids = List.copyOf(forcedStopPids);
            survivingPids = List.copyOf(survivingPids);
            errors = List.copyOf(errors);
        }

        static ProcessCleanup from(QualificationProcessCleanup.Result result) {
            Objects.requireNonNull(result, "result");
            return new ProcessCleanup(
                    result.outcome(),
                    result.gracefulExitRequested(),
                    result.campaignServerDiscoveryVerified(),
                    result.capturedDescendantPids(),
                    result.campaignServerPids(),
                    result.normalStopPids(),
                    result.forcedStopPids(),
                    result.survivingPids(),
                    result.errors());
        }
    }
}
