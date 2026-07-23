package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.core.security.NoFollowPathPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Publishes the exact current trace from Talos-owned session storage. */
final class QualificationTraceEvidencePublication {
    static final String OUTPUT_FILE = "trace.json";
    static final int TRANSACTIONAL_TRACE_SCHEMA_VERSION = 5;
    private static final int MAX_TRACE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_SESSION_DIRECTORIES = 1_024;
    private static final int MAX_TRACES_PER_SESSION = 4_096;
    private static final String SESSION_TRACE_ROOT = "home/.talos/sessions/traces";
    private static final Pattern LAST_TRACE_LINE = Pattern.compile(
            "(?:\\A|\\r?\\n)  Local trace: "
                    + "([A-Za-z0-9][A-Za-z0-9._-]{0,127})"
                    + "(?:\u001B\\[0K)?(?=\\r?\\n|\\z)");

    private QualificationTraceEvidencePublication() {}

    record Result(Path trace, String traceId, String sha256, boolean created) {
        Result {
            trace = Objects.requireNonNull(trace, "published trace");
            traceId = requireText(traceId, "trace id");
            sha256 = Objects.requireNonNull(sha256, "trace SHA-256");
        }
    }

    record CommittedTrace(byte[] bytes, Path commitPath) {
        CommittedTrace {
            bytes = Objects.requireNonNull(bytes, "committed trace bytes").clone();
            commitPath = Objects.requireNonNull(commitPath, "trace commit path");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        boolean matches(byte[] candidate) {
            return candidate != null && Arrays.equals(bytes, candidate);
        }
    }

    static Result publish(
            Path packetRoot,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        return QualificationPacketPublisher.withLease(
                packetRoot,
                lease -> publishUnderLease(
                        lease, requestedRunRoot, scenarioId, catalog));
    }

    static Result publishUnderLease(
            QualificationPacketPublisher.Lease lease,
            Path requestedRunRoot,
            String scenarioId,
            QualificationScenarioBank.Catalog catalog) throws IOException {
        QualificationArtifactPublication.DeclaredRun declared =
                QualificationArtifactPublication.requireWritableDeclaredRun(
                        lease.packetRoot(), requestedRunRoot, scenarioId, catalog);
        NoFollowPathPolicy.GuardedRoot packetFiles = declared.packetFiles();
        Path packetRoot = declared.packetRoot();
        Path target = declared.runRoot().resolve(OUTPUT_FILE);
        lease.requireTransactionProvenance(
                QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                List.of(target));

        Path transcript = declared.runRoot().resolve("transcript.raw.json");
        QualificationPacketPublisher.CommittedSet committedCapture =
                lease.requireCommittedSet(
                        QualificationPacketPublisher.PublicationKind.PTY_CAPTURE,
                        List.of(transcript));
        QualificationPtyEvidence.Capture capture = QualificationPtyEvidence.parse(
                committedCapture.bytes(packetRoot, transcript),
                QualificationProtocol.VERSION);
        String selectedTraceId = selectedTraceId(capture);
        Path sourceRoot = packetFiles.requireDirectory(
                packetRoot.resolve(SESSION_TRACE_ROOT));
        Path source = uniqueTraceSource(packetFiles, sourceRoot, selectedTraceId);
        byte[] bytes = boundedBytes(packetFiles, source);
        JsonNode trace = QualificationJson.readTree(bytes);
        if (!trace.isObject()) {
            throw new IllegalArgumentException("trace source must be a JSON object");
        }
        int schemaVersion = QualificationJson.requiredInt(
                trace.get("schemaVersion"), "trace schemaVersion");
        if (schemaVersion != TRANSACTIONAL_TRACE_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "trace publication requires trace schema v"
                            + TRANSACTIONAL_TRACE_SCHEMA_VERSION);
        }
        String traceId = requireText(
                QualificationJson.requiredText(trace.get("traceId"), "trace traceId"),
                "trace id");
        if (!traceId.equals(selectedTraceId)) {
            throw new IllegalArgumentException(
                    "trace identity differs from the committed /last trace output");
        }
        String sessionId = requireText(
                QualificationJson.requiredText(trace.get("sessionId"), "trace sessionId"),
                "trace session id");
        int turnNumber = QualificationJson.requiredInt(
                trace.get("turnNumber"), "trace turnNumber");
        if (turnNumber < 1 || turnNumber > 999_999) {
            throw new IllegalArgumentException("trace turnNumber is outside the supported range");
        }
        String expectedName = "%06d-%s.json".formatted(turnNumber, traceId);
        if (!source.getParent().getFileName().toString().equals(sessionId)
                || !source.getFileName().toString().equals(expectedName)) {
            throw new IllegalArgumentException(
                    "trace file coordinates differ from its persisted identity");
        }
        requireCaptureTimestamp(
                QualificationJson.requiredText(trace.get("timestamp"), "trace timestamp"),
                capture);
        QualificationTraceEvidenceEnvelope.validate(
                bytes, catalog.scenario(scenarioId));
        QualificationPacketPublisher.CommittedSet committed = lease.publish(
                QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                List.of(new QualificationPacketPublisher.Member(target, bytes)));
        return new Result(
                target,
                traceId,
                QualificationScenarioBank.sha256(bytes),
                committed.created());
    }

    static String selectedTraceId(QualificationPtyEvidence.Capture capture) {
        int commandIndex = -1;
        for (int index = 0; index < capture.events().size(); index++) {
            QualificationPtyEvidence.Event event = capture.events().get(index);
            if (event.type().equals("input") && event.data().equals("last-trace")) {
                if (commandIndex >= 0) {
                    throw new IllegalArgumentException(
                            "PTY capture contains multiple /last trace commands");
                }
                commandIndex = index;
            }
        }
        if (commandIndex < 0) {
            throw new IllegalArgumentException(
                    "PTY capture does not contain the /last trace command");
        }
        StringBuilder commandOutput = new StringBuilder();
        for (int index = commandIndex + 1; index < capture.events().size(); index++) {
            QualificationPtyEvidence.Event event = capture.events().get(index);
            if (event.type().equals("input")) break;
            if (event.type().equals("output")) commandOutput.append(event.data());
        }
        Matcher matches = LAST_TRACE_LINE.matcher(commandOutput);
        List<String> traceIds = new ArrayList<>();
        while (matches.find()) traceIds.add(matches.group(1));
        if (traceIds.size() != 1) {
            throw new IllegalArgumentException(
                    "PTY /last trace output must identify exactly one local trace");
        }
        return traceIds.getFirst();
    }

    private static Path uniqueTraceSource(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path sourceRoot,
            String traceId) throws IOException {
        List<NoFollowPathPolicy.TreeEntry> sessions = packetFiles.list(sourceRoot);
        if (sessions.size() > MAX_SESSION_DIRECTORIES) {
            throw new IllegalArgumentException("session trace directory count exceeds the limit");
        }
        String suffix = "-" + traceId + ".json";
        List<Path> matches = new ArrayList<>();
        for (NoFollowPathPolicy.TreeEntry session : sessions) {
            if (session.type() != NoFollowPathPolicy.EntryType.DIRECTORY) {
                throw new IllegalArgumentException(
                        "session trace root contains a non-directory entry");
            }
            List<NoFollowPathPolicy.TreeEntry> traces = packetFiles.list(session.path());
            if (traces.size() > MAX_TRACES_PER_SESSION) {
                throw new IllegalArgumentException("session trace count exceeds the limit");
            }
            for (NoFollowPathPolicy.TreeEntry candidate : traces) {
                if (candidate.type() != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                    throw new IllegalArgumentException(
                            "session trace directory contains a non-file entry");
                }
                String name = candidate.path().getFileName().toString();
                if (name.matches("[0-9]{6}" + Pattern.quote(suffix))) {
                    matches.add(candidate.path());
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "committed /last trace identity must resolve to exactly one session trace");
        }
        return matches.getFirst();
    }

    private static void requireCaptureTimestamp(
            String rawTimestamp,
            QualificationPtyEvidence.Capture capture) {
        try {
            Instant timestamp = Instant.parse(rawTimestamp);
            if (!timestamp.toString().equals(rawTimestamp)
                    || timestamp.isBefore(capture.startedAt())
                    || timestamp.isAfter(capture.endedAt())) {
                throw new IllegalArgumentException(
                        "trace timestamp is outside the committed PTY capture");
            }
        } catch (DateTimeParseException malformed) {
            throw new IllegalArgumentException("trace timestamp is invalid", malformed);
        }
    }

    /**
     * Returns no publication authority for historical trace v4. Current trace v5 must have an
     * exact committed TRACE_EVIDENCE member; all other versions are rejected by this boundary.
     */
    static Optional<CommittedTrace> requireCommittedForSchema(
            Path packetRoot,
            Path requestedRunRoot,
            int traceSchemaVersion,
            byte[] observedTraceBytes) throws IOException {
        if (traceSchemaVersion == 4) return Optional.empty();
        if (traceSchemaVersion != TRANSACTIONAL_TRACE_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported qualification trace schema: " + traceSchemaVersion);
        }
        Path target = requestedRunRoot.toAbsolutePath().normalize().resolve(OUTPUT_FILE);
        QualificationPacketPublisher.CommittedSet committed =
                QualificationPacketPublisher.requireCommitted(
                        packetRoot,
                        QualificationPacketPublisher.PublicationKind.TRACE_EVIDENCE,
                        List.of(target));
        CommittedTrace trace = new CommittedTrace(
                committed.bytes(packetRoot, target), committed.commitPath());
        if (!trace.matches(Objects.requireNonNull(
                observedTraceBytes, "observed trace bytes"))) {
            throw new IOException("committed trace evidence differs from parsed trace bytes");
        }
        return Optional.of(trace);
    }

    private static byte[] boundedBytes(
            NoFollowPathPolicy.GuardedRoot packetFiles,
            Path source) throws IOException {
        try (InputStream input = packetFiles.openRegularFile(source)) {
            byte[] bytes = input.readNBytes(MAX_TRACE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_TRACE_BYTES) {
                throw new IllegalArgumentException(
                        "trace source size is outside the allowed range");
            }
            return bytes;
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is blank");
        }
        return value;
    }
}
