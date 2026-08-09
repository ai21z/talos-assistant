package dev.talos.cli.repl;

import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionWriterLease;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent;
import dev.talos.runtime.trace.TurnTraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class T1190BareLastRestartRedTest {

    @Test
    void bareLastTraceAfterRestartDisplaysTheDurableFailedTerminalPair(
            @TempDir Path home
    ) throws Exception {
        Path workspace = home.resolve("workspace").toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        ReplRouter router = null;
        try {
            JsonSessionStore store = new JsonSessionStore(
                    home.resolve(".talos").resolve("sessions"));
            String workspaceId = JsonSessionStore.sessionIdFor(workspace);
            try (SessionWriterLease prior = store.reserveNewSession(
                    workspace,
                    Instant.parse("2026-08-03T16:00:00Z"))) {
                prior.persistTurnEnvelope(
                        prior.sessionId(),
                        failedRow(),
                        failedTrace(workspaceId));
            }

            router = TalosBootstrap.create(
                    sessionState(),
                    sessionConfig(),
                    new PrintStream(java.io.OutputStream.nullOutputStream()),
                    workspace);

            Result.TrustedInfo result = assertInstanceOf(
                    Result.TrustedInfo.class,
                    router.getRegistry().execute(
                            "last", "trace", router.context()));
            assertTrue(result.text.contains(
                    "Saved Startup Terminal-Turn Observation"), result.text);
            assertTrue(result.text.contains(
                    "Trace: trc-strict-restart-red"), result.text);
            assertTrue(result.text.contains(
                    "Settlement: FAILED at ASSISTANT_EXECUTION"), result.text);
            assertTrue(result.text.contains(
                    "class=java.lang.IllegalStateException"), result.text);
        } finally {
            if (router != null) router.getRuntimeSession().close();
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static TurnRecord failedRow() {
        return new TurnRecord(
                1,
                Instant.parse("2026-08-03T16:00:02Z"),
                12,
                "STRICT_RED_USER_CANARY",
                "STRICT_RED_ASSISTANT_CANARY",
                List.of(),
                0,
                0,
                0,
                "",
                "failed",
                TurnPolicyTrace.empty(),
                "trc-strict-restart-red");
    }

    private static LocalTurnTrace failedTrace(String workspaceId) {
        Instant started = Instant.parse("2026-08-03T16:00:00Z");
        return LocalTurnTrace.builder(
                        "trc-strict-restart-red",
                        workspaceId,
                        1,
                        started.toString())
                .mode("ask")
                .event(TurnTraceEvent.simple(
                        "TRACE_STARTED",
                        started.toString(),
                        Map.of("turnNumber", 1, "redactionMode", "DEFAULT")))
                .event(TurnTraceEvent.simple(
                        TurnEvidenceProjectionTraceEvent.TYPE,
                        started.plusSeconds(1).toString(),
                        projection()))
                .event(TurnTraceEvent.simple(
                        "TURN_SETTLED",
                        started.plusSeconds(1).toString(),
                        Map.of(
                                "schemaVersion", 1,
                                "settlementKind", "FAILED",
                                "stage", "ASSISTANT_EXECUTION",
                                "errorKind", "UNEXPECTED",
                                "errorCode", 500,
                                "exceptionClass", "java.lang.IllegalStateException",
                                "causalOwner", "UNKNOWN")))
                .event(TurnTraceEvent.simple(
                        "TRACE_COMPLETED",
                        started.plusSeconds(2).toString(),
                        Map.of()))
                .build();
    }

    private static Map<String, Object> projection() {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", 1);
        marker.put("completeness", "INCOMPLETE");
        marker.put("reason", "NON_CONVERSATIONAL_SAFE_PROJECTION");
        marker.put("providerLifecycle", "NOT_OBSERVED");
        marker.put("retrieval", "UNAVAILABLE");
        marker.put("captureFinalizers", "OBSERVED_COMPLETE");
        marker.put("omittedCategories", List.of(
                "CHECKPOINT_VERIFICATION",
                "POLICY_CONTEXT",
                "TOOL_LIFECYCLE"));
        return marker;
    }

    private static Config sessionConfig() {
        Config config = new Config();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("persistence", true);
        session.put("auto_load", false);
        config.data.put("session", session);
        return config;
    }

    private static SessionState sessionState() {
        return new SessionState() {
            private int k = 6;
            private boolean debug;

            @Override
            public int getK() {
                return k;
            }

            @Override
            public void setK(int value) {
                k = value;
            }

            @Override
            public boolean isDebug() {
                return debug;
            }

            @Override
            public void setDebug(boolean on) {
                debug = on;
            }
        };
    }
}
