package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step-2 tests: per-turn structured durability.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code appendTurn} + {@code loadTurns} round-trip multiple turns</li>
 *   <li>Snapshot {@code save/load} (existing behavior) still works unchanged</li>
 *   <li>Snapshot and per-turn log are independent companion files</li>
 *   <li>Malformed JSONL lines are skipped (not fatal)</li>
 *   <li>Deleting a session removes both companion files</li>
 * </ul>
 */
class JsonSessionStoreTurnsTest {

    @Test
    void appendAndLoadTurnsRoundTrip(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-abc";

        store.appendTurn(sid, new TurnRecord(
                1, Instant.parse("2026-04-18T10:00:00Z"), 250,
                "hello", "hi there",
                List.of(new TurnRecord.ToolCallSummary("talos.read_file", "index.html", true)),
                0, 0, 0, ""));
        store.appendTurn(sid, new TurnRecord(
                2, Instant.parse("2026-04-18T10:00:05Z"), 4800,
                "edit title", "done",
                List.of(new TurnRecord.ToolCallSummary("talos.edit_file", "index.html", true)),
                1, 1, 0, "3 stages, 42.1ms, final=4"));

        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(2, loaded.size(), "both turns persisted");
        assertEquals(1, loaded.get(0).turnNumber());
        assertEquals("hello", loaded.get(0).userInput());
        assertEquals("hi there", loaded.get(0).assistantText());
        assertEquals("talos.read_file", loaded.get(0).toolCalls().get(0).name());
        assertTrue(loaded.get(0).toolCalls().get(0).success());

        assertEquals(2, loaded.get(1).turnNumber());
        assertEquals(1, loaded.get(1).approvalsRequired());
        assertEquals(4800, loaded.get(1).durationMs());
        assertEquals("3 stages, 42.1ms, final=4", loaded.get(1).retrievalTraceSummary());
    }

    @Test
    void session_turn_log_does_not_contain_raw_canary_after_grep(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-canary";

        store.appendTurn(sid, new TurnRecord(
                1,
                Instant.parse("2026-04-18T10:00:00Z"),
                250,
                "Search for DO_NOT_LEAK but do not print values.",
                "PRIVATE_MARKER = DO_NOT_LEAK_T267_SESSION",
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.grep",
                        "notes.md",
                        true,
                        "notes.md:1 | PRIVATE_MARKER = DO_NOT_LEAK_T267_SESSION")),
                0,
                0,
                0,
                "trace: DO_NOT_LEAK_T267_TRACE"));

        String rawJsonl = java.nio.file.Files.readString(dir.resolve(sid + ".turns.jsonl"));

        assertFalse(rawJsonl.contains("DO_NOT_LEAK_T267_SESSION"));
        assertFalse(rawJsonl.contains("DO_NOT_LEAK_T267_TRACE"));
        assertTrue(rawJsonl.contains("PRIVATE_MARKER=[redacted]"));
    }

    @Test
    void policyTraceRoundTrips(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-policy";
        TurnPolicyTrace trace = new TurnPolicyTrace(
                "FILE_CREATE",
                true,
                true,
                List.of("index.html"),
                List.of(),
                "APPLY",
                "VERIFY",
                List.of("talos.read_file", "talos.write_file"),
                List.of("talos.read_file", "talos.write_file"),
                List.of("approval denied by user for talos.write_file"));

        store.appendTurn(sid, new TurnRecord(
                1,
                Instant.parse("2026-04-18T10:00:00Z"),
                250,
                "create site",
                "No file changed.",
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.write_file",
                        "index.html",
                        false,
                        "approval denied by user for talos.write_file")),
                1,
                0,
                1,
                "",
                "ok",
                trace));

        TurnRecord loaded = store.loadTurns(sid).get(0);

        assertEquals("FILE_CREATE", loaded.policyTrace().taskType());
        assertTrue(loaded.policyTrace().mutationAllowed());
        assertEquals("APPLY", loaded.policyTrace().initialPhase());
        assertEquals("VERIFY", loaded.policyTrace().finalPhase());
        assertEquals(List.of("talos.read_file", "talos.write_file"), loaded.policyTrace().nativeTools());
        assertEquals(List.of("approval denied by user for talos.write_file"), loaded.policyTrace().blocks());
        assertEquals("approval denied by user for talos.write_file", loaded.toolCalls().get(0).reason());
    }

    @Test
    void policyTraceRolefulTargetsRoundTrip(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-policy-roleful";
        TurnPolicyTrace trace = TurnPolicyTrace.from(
                dev.talos.runtime.task.TaskContractResolver.fromUserRequest(
                        "Rewrite styles.css so index.html still works."),
                "APPLY",
                List.of("talos.write_file", "talos.edit_file"),
                List.of("talos.write_file", "talos.edit_file"));

        store.appendTurn(sid, new TurnRecord(
                1,
                Instant.parse("2026-04-18T10:00:00Z"),
                250,
                "rewrite styles",
                "No file changed.",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                trace));

        TurnRecord loaded = store.loadTurns(sid).getFirst();

        assertEquals(List.of("styles.css"), loaded.policyTrace().expectedTargets());
        assertTrue(loaded.policyTrace().rolefulTargets().stream()
                .anyMatch(target -> "styles.css".equals(target.path())
                        && "MUST_MUTATE".equals(target.role())));
        assertTrue(loaded.policyTrace().rolefulTargets().stream()
                .anyMatch(target -> "index.html".equals(target.path())
                        && "VERIFY_ONLY".equals(target.role())));
    }

    @Test
    void exactPolicyTraceOffsetsRoundTripAndLegacyMissingOffsetsRemainUnknown(
            @TempDir Path dir
    ) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String canonicalRequest =
                "Edit index.html so the selector matches, and update style.css too.";
        String request = " \r\n" + canonicalRequest + "\r\n ";
        var resolution =
                dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(request);
        TurnPolicyTrace trace = TurnPolicyTrace.from(
                resolution.contract(),
                resolution.intent(),
                "APPLY",
                List.of("talos.edit_file"),
                List.of("talos.edit_file"));
        TurnPolicyTrace.RolefulTarget expected = trace.rolefulTargets().stream()
                .filter(target -> "style.css".equals(target.path()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                canonicalRequest.lastIndexOf("style.css")
                        + "style.css".length(),
                expected.authorityEndOffset());
        assertTrue(expected.authorityEndOffset() < expected.endOffset());
        assertEquals(
                expected.sourceText(),
                canonicalRequest.substring(
                        expected.startOffset(),
                        expected.endOffset()));

        String sid = "session-policy-exact-offset";
        store.appendTurn(sid, new TurnRecord(
                1,
                Instant.parse("2026-04-18T10:00:00Z"),
                10,
                request,
                "partial",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                trace));

        TurnPolicyTrace.RolefulTarget loaded = store.loadTurns(sid)
                .getFirst()
                .policyTrace()
                .rolefulTargets()
                .stream()
                .filter(target -> "style.css".equals(target.path()))
                .findFirst()
                .orElseThrow();
        assertEquals(expected, loaded);

        String legacyKnownSid = "session-policy-legacy-known-offset";
        String persisted = Files.readString(
                dir.resolve(sid + ".turns.jsonl"));
        String withoutAuthorityEnd = persisted.replaceAll(
                "\"authorityEndOffset\"\\s*:\\s*-?\\d+\\s*,",
                "");
        assertFalse(persisted.equals(withoutAuthorityEnd));
        Files.writeString(
                dir.resolve(legacyKnownSid + ".turns.jsonl"),
                withoutAuthorityEnd);
        TurnPolicyTrace.RolefulTarget legacyKnown = new JsonSessionStore(dir)
                .loadTurns(legacyKnownSid)
                .getFirst()
                .policyTrace()
                .rolefulTargets()
                .stream()
                .filter(target -> "style.css".equals(target.path()))
                .findFirst()
                .orElseThrow();
        assertEquals(expected.startOffset(), legacyKnown.startOffset());
        assertEquals(-1, legacyKnown.authorityEndOffset());
        assertEquals(expected.endOffset(), legacyKnown.endOffset());
        assertEquals(expected.sourceText(), legacyKnown.sourceText());

        String legacySid = "session-policy-legacy-offset";
        Files.writeString(
                dir.resolve(legacySid + ".turns.jsonl"),
                turnLineWithRolefulTargets("""
                        [{"path":"style.css","role":"MUST_MUTATE",
                          "source":"USER_REQUEST","reason":"legacy",
                          "sourceText":"update style.css.","confidence":1.0}]
                        """));
        TurnPolicyTrace.RolefulTarget legacy = new JsonSessionStore(dir)
                .loadTurns(legacySid)
                .getFirst()
                .policyTrace()
                .rolefulTargets()
                .getFirst();
        assertEquals(-1, legacy.startOffset());
        assertEquals(-1, legacy.authorityEndOffset());
        assertEquals(-1, legacy.endOffset());
    }

    @Test
    void sanitizingCanonicalInputInvalidatesPersistedExactOffsets(
            @TempDir Path dir
    ) throws Exception {
        String request = "DO_NOT_LEAK_T1196_PREFIX. Edit index.html so it works, "
                + "and update style.css too.";
        int start = request.indexOf("update style.css");
        String sourceText = "update style.css too.";
        TurnPolicyTrace trace = new TurnPolicyTrace(
                "FILE_EDIT",
                true,
                true,
                List.of("style.css"),
                List.of(),
                "APPLY",
                "APPLY",
                List.of("talos.edit_file"),
                List.of("talos.edit_file"),
                List.of(),
                "sanitized-coordinate-test",
                List.of(new TurnPolicyTrace.RolefulTarget(
                        "style.css",
                        "MUST_MUTATE",
                        "USER_REQUEST",
                        "explicit-mutation-target",
                        sourceText,
                        1.0,
                        start,
                        start + "update style.css".length(),
                        start + sourceText.length())));
        String sid = "session-sanitized-coordinate";
        JsonSessionStore store = new JsonSessionStore(dir);

        store.appendTurn(sid, new TurnRecord(
                1,
                Instant.parse("2026-07-30T00:00:00Z"),
                10,
                request,
                "partial",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                trace));

        String raw = Files.readString(dir.resolve(sid + ".turns.jsonl"));
        assertFalse(raw.contains("DO_NOT_LEAK_T1196_PREFIX"));
        TurnPolicyTrace.RolefulTarget loaded = store.loadTurns(sid)
                .getFirst()
                .policyTrace()
                .rolefulTargets()
                .getFirst();
        assertEquals(-1, loaded.startOffset());
        assertEquals(-1, loaded.authorityEndOffset());
        assertEquals(-1, loaded.endOffset());
        assertEquals(sourceText, loaded.sourceText());
    }

    @Test
    void requiredPostReadMutationRoleRoundTripsWithoutCoercion(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-policy-required-post-read";
        TurnPolicyTrace trace = TurnPolicyTrace.from(
                new dev.talos.runtime.task.TaskContract(
                        dev.talos.runtime.task.TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        java.util.Set.of("style.css"),
                        java.util.Set.of("style.css"),
                        java.util.Set.of(),
                        "Read style.css, then update style.css.",
                        "required-post-read-mutation"),
                "APPLY",
                List.of("talos.read_file", "talos.edit_file"),
                List.of("talos.read_file", "talos.edit_file"));

        store.appendTurn(sid, new TurnRecord(
                1,
                Instant.parse("2026-04-18T10:00:00Z"),
                250,
                "read then update style",
                "No file changed.",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                trace));

        TurnPolicyTrace loaded = store.loadTurns(sid).getFirst().policyTrace();
        var styleTargets = loaded.rolefulTargets().stream()
                .filter(target -> "style.css".equals(target.path()))
                .toList();
        assertEquals(1, styleTargets.size());
        assertEquals("MUST_MUTATE_AFTER_SOURCE_EVIDENCE", styleTargets.getFirst().role());
        assertEquals(trace.rolefulTargets(), loaded.rolefulTargets());
    }

    @Test
    void malformedPersistedPolicyRolesFailClosedWithoutCoercion(@TempDir Path dir) throws Exception {
        List<String> malformedRolefulTargets = List.of(
                "{}",
                """
                ["not-an-object"]
                """,
                """
                [{"path":"style.css","role":"MUTATE_IF_CONVENIENT","source":"RUNTIME_DEFAULT",
                  "reason":"invalid-role","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"  ","source":"RUNTIME_DEFAULT",
                  "reason":"blank-role","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":null,"source":"RUNTIME_DEFAULT",
                  "reason":"null-role","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":123,"source":"RUNTIME_DEFAULT",
                  "reason":"numeric-role","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"./style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"duplicate-a","sourceText":"","confidence":1.0},
                 {"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"duplicate-b","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"conflict-a","sourceText":"","confidence":1.0},
                 {"path":"style.css","role":"SOURCE_EVIDENCE","source":"RUNTIME_DEFAULT",
                   "reason":"conflict-b","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"malformed-offset","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":"junk","endOffset":"junk"}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"fractional-offset","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":1.5,"endOffset":18}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"numeric-string-offset","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":"1","endOffset":18}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"textual-authority-end","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":"16","endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"fractional-authority-end","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":15.5,"endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"authority-without-outer-span","sourceText":"update style.css.",
                  "confidence":1.0,"authorityEndOffset":16}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"authority-at-start","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":0,"endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"authority-after-end","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":18,"endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"authority-before-target","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":2,"endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"mismatched-exact-span","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,"endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"explicit-zero-pair","sourceText":"",
                  "confidence":1.0,"startOffset":0,"endOffset":0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"MESSAGE_HISTORY",
                  "reason":"history-known-offset","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":1,"endOffset":18}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"NOT_A_SOURCE",
                  "reason":"unknown-source","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":123,
                  "reason":"numeric-source","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"string-confidence","sourceText":"","confidence":"1.0"}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"missing-confidence","sourceText":""}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"high-confidence","sourceText":"","confidence":1.01}]
                """);

        for (int i = 0; i < malformedRolefulTargets.size(); i++) {
            String sid = "session-malformed-policy-role-" + i;
            Files.writeString(
                    dir.resolve(sid + ".turns.jsonl"),
                    turnLineWithRolefulTargets(malformedRolefulTargets.get(i)));

            assertTrue(
                    new JsonSessionStore(dir).loadTurns(sid).isEmpty(),
                    "malformed role row must fail its turn closed: " + malformedRolefulTargets.get(i));
        }
    }

    @Test
    void appendRejectsKnownUserRequestCoordinatesThatCannotRoundTrip(
            @TempDir Path dir
    ) {
        String sid = "session-write-invalid-coordinate";
        TurnPolicyTrace trace = new TurnPolicyTrace(
                "FILE_EDIT",
                true,
                true,
                List.of("style.css"),
                List.of(),
                "APPLY",
                "APPLY",
                List.of("talos.edit_file"),
                List.of("talos.edit_file"),
                List.of(),
                "invalid-write-coordinate",
                List.of(new TurnPolicyTrace.RolefulTarget(
                        "style.css",
                        "MUST_MUTATE",
                        "USER_REQUEST",
                        "explicit-mutation-target",
                        "update style.css.",
                        1.0,
                        0,
                        "update style.css.".length())));
        JsonSessionStore store = new JsonSessionStore(dir);

        store.appendTurn(sid, new TurnRecord(
                1,
                Instant.parse("2026-07-30T00:00:00Z"),
                10,
                "Edit index.html.",
                "not written",
                List.of(),
                0,
                0,
                0,
                "",
                "failed",
                trace));

        assertFalse(Files.exists(dir.resolve(sid + ".turns.jsonl")));
        assertTrue(store.loadTurns(sid).isEmpty());
    }

    @Test
    void durableRolefulEvidenceShapeIsStrictBeforePublication() {
        TurnPolicyTrace.RolefulTarget legacyUnknownAuthority =
                assertDoesNotThrow(() -> new TurnPolicyTrace.RolefulTarget(
                        "style.css",
                        "MUST_MUTATE",
                        "USER_REQUEST",
                        "legacy-known-outer-span",
                        "update style.css.",
                        1.0,
                        3,
                        20));
        assertEquals(-1, legacyUnknownAuthority.authorityEndOffset());
        assertDoesNotThrow(() -> new TurnPolicyTrace.RolefulTarget(
                "style.css",
                "MUST_MUTATE",
                "RUNTIME_DEFAULT",
                "boundary-zero",
                "",
                0.0,
                -1,
                -1));
        assertDoesNotThrow(() -> new TurnPolicyTrace.RolefulTarget(
                "style.css",
                "MUST_MUTATE",
                "RUNTIME_DEFAULT",
                "boundary-one",
                "",
                1.0,
                -1,
                -1));
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "NOT_A_SOURCE",
                                "", "", 1.0, -1, -1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "RUNTIME_DEFAULT",
                                "", "", Double.NaN, -1, -1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "RUNTIME_DEFAULT",
                                "", "", Double.POSITIVE_INFINITY, -1, -1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "", 1.0, 0, 0)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "", 1.0, 3, 3)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "MESSAGE_HISTORY",
                                "", "css", 1.0, 0, 3)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "update style.css.", 1.0,
                                -1, 16, -1)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "update style.css.", 1.0,
                                3, 3, 20)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "update style.css.", 1.0,
                                3, 21, 20)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new TurnPolicyTrace.RolefulTarget(
                                "style.css", "MUST_MUTATE", "USER_REQUEST",
                                "", "update style.css.", 1.0,
                                0, 2, 17)));
    }

    @Test
    void malformedPersistedLocalTraceRolesFailClosed(@TempDir Path dir) throws Exception {
        List<String> malformedRolefulTargets = List.of(
                "{}",
                """
                ["not-an-object"]
                """,
                """
                [{"path":"style.css","role":"MUTATE_IF_CONVENIENT","source":"RUNTIME_DEFAULT",
                  "reason":"invalid-role","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"  ","source":"RUNTIME_DEFAULT",
                  "reason":"blank-role","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":null,"source":"RUNTIME_DEFAULT",
                  "reason":"null-role","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":123,"source":"RUNTIME_DEFAULT",
                  "reason":"numeric-role","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"duplicate-a","sourceText":"","confidence":1.0},
                 {"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"duplicate-b","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"./style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"conflict-a","sourceText":"","confidence":1.0},
                 {"path":"style.css","role":"SOURCE_EVIDENCE","source":"RUNTIME_DEFAULT",
                   "reason":"conflict-b","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"fractional","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":1.5,"endOffset":18}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"numeric-string","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":"1","endOffset":18}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"textual-authority-end","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":"16","endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"fractional-authority-end","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":15.5,"endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"authority-without-outer-span","sourceText":"update style.css.",
                  "confidence":1.0,"authorityEndOffset":16}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"authority-at-start","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":0,"endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"authority-after-end","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":18,"endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"authority-before-target","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":0,
                  "authorityEndOffset":2,"endOffset":17}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"half-known","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":1}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"explicit-zero-pair","sourceText":"",
                  "confidence":1.0,"startOffset":0,"endOffset":0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"USER_REQUEST",
                  "reason":"span-length-mismatch","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":1,"endOffset":20}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"MESSAGE_HISTORY",
                  "reason":"history-known-offset","sourceText":"update style.css.",
                  "confidence":1.0,"startOffset":1,"endOffset":18}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"NOT_A_SOURCE",
                  "reason":"unknown-source","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":123,
                  "reason":"numeric-source","sourceText":"","confidence":1.0}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"string-confidence","sourceText":"","confidence":"1.0"}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"missing-confidence","sourceText":""}]
                """,
                """
                [{"path":"style.css","role":"MUST_MUTATE","source":"RUNTIME_DEFAULT",
                  "reason":"negative-confidence","sourceText":"","confidence":-0.01}]
                """);

        JsonSessionStore store = new JsonSessionStore(dir);
        for (int i = 0; i < malformedRolefulTargets.size(); i++) {
            String sid = "session-malformed-local-trace-role-" + i;
            String traceId = "trc-malformed-" + i;
            Path traceDir = dir.resolve("traces").resolve(sid);
            Files.createDirectories(traceDir);
            Files.writeString(
                    traceDir.resolve("000001-" + traceId + ".json"),
                    localTraceWithRolefulTargets(
                            sid,
                            traceId,
                            malformedRolefulTargets.get(i)));

            assertTrue(
                    store.loadTrace(sid, traceId).isEmpty(),
                    "malformed local role must fail direct load closed: "
                            + malformedRolefulTargets.get(i));
            assertTrue(
                    store.loadLatestTrace(sid).isEmpty(),
                    "malformed local role must fail latest load closed: "
                            + malformedRolefulTargets.get(i));
        }
    }

    @Test
    void legacyPolicyTraceWithoutRolefulTargetsStillLoads(@TempDir Path dir) throws Exception {
        String sid = "session-legacy-policy";
        Files.writeString(dir.resolve(sid + ".turns.jsonl"), """
                {"turnNumber":1,"timestamp":"2026-04-18T10:00:00Z","durationMs":10,"userInput":"q","assistantText":"a","approvalsRequired":0,"approvalsGranted":0,"approvalsDenied":0,"retrievalTraceSummary":"","status":"ok","traceId":"trc-legacy","policyTrace":{"taskType":"FILE_EDIT","mutationAllowed":true,"verificationRequired":true,"expectedTargets":["styles.css"],"forbiddenTargets":[],"initialPhase":"APPLY","finalPhase":"APPLY","nativeTools":["talos.write_file"],"promptTools":["talos.write_file"],"blocks":[],"classificationReason":"legacy"},"toolCalls":[]}
                """);
        JsonSessionStore store = new JsonSessionStore(dir);

        TurnRecord loaded = store.loadTurns(sid).getFirst();

        assertEquals(List.of("styles.css"), loaded.policyTrace().expectedTargets());
        assertTrue(loaded.policyTrace().rolefulTargets().isEmpty());
    }

    @Test
    void legacyLocalTraceWithoutRolefulTargetsStillLoads(@TempDir Path dir) throws Exception {
        String sid = "session-legacy-trace";
        Path traceDir = dir.resolve("traces").resolve(sid);
        Files.createDirectories(traceDir);
        Files.writeString(traceDir.resolve("000001-trc-legacy.json"), """
                {
                  "schemaVersion": 2,
                  "traceId": "trc-legacy",
                  "sessionId": "session-legacy-trace",
                  "turnNumber": 1,
                  "timestamp": "2026-04-18T10:00:00Z",
                  "workspaceHash": "hash",
                  "mode": "auto",
                  "model": {"backend": "test", "model": "model"},
                  "taskContract": {
                    "type": "FILE_EDIT",
                    "mutationAllowed": true,
                    "verificationRequired": true,
                    "mutationRequested": true,
                    "expectedTargets": ["styles.css"],
                    "forbiddenTargets": [],
                    "classificationReason": "legacy"
                  }
                }
                """);
        JsonSessionStore store = new JsonSessionStore(dir);

        var loaded = store.loadTrace(sid, "trc-legacy").orElseThrow();

        assertEquals(List.of("styles.css"), loaded.taskContract().expectedTargets());
        assertTrue(loaded.taskContract().rolefulTargets().isEmpty());
    }

    @Test
    void snapshotPathUnchangedByTurnsLog(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "session-snapshot-compat";

        SessionData data = new SessionData(sid, dir.toString(),
                "my sketch", 2, Instant.now(),
                List.of(new SessionData.Turn("user", "q"),
                        new SessionData.Turn("assistant", "a")));
        store.save(data);

        // Independently append a per-turn record.
        store.appendTurn(sid, new TurnRecord(
                1, Instant.now(), 100, "q", "a",
                List.of(), 0, 0, 0, ""));

        Optional<SessionData> reloaded = store.load(sid);
        assertTrue(reloaded.isPresent(), "snapshot still loads");
        assertEquals("my sketch", reloaded.get().sketch());
        assertEquals(2, reloaded.get().turns().size());
        assertEquals(1, store.loadTurns(sid).size());
    }

    @Test
    void oldSnapshotOnlySessionLoadsEvenWithoutTurnsLog(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "old-session";
        SessionData data = new SessionData(sid, dir.toString(),
                "", 0, Instant.now(), List.of());
        store.save(data);

        assertTrue(store.load(sid).isPresent(), "old snapshot still loads");
        assertTrue(store.loadTurns(sid).isEmpty(),
                "no jsonl file → empty turn log (no error)");
    }

    @Test
    void loadTurnsIsEmptyForMissingSession(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        assertTrue(store.loadTurns("nonexistent").isEmpty());
    }

    @Test
    void deleteRemovesBothSnapshotAndTurnsLog(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "to-delete";
        store.save(new SessionData(sid, dir.toString(), "", 0, Instant.now(), List.of()));
        store.appendTurn(sid, new TurnRecord(
                1, Instant.now(), 10, "q", "a", List.of(), 0, 0, 0, ""));

        assertTrue(java.nio.file.Files.exists(dir.resolve(sid + ".json")));
        assertTrue(java.nio.file.Files.exists(dir.resolve(sid + ".turns.jsonl")));

        assertTrue(store.delete(sid));
        assertFalse(java.nio.file.Files.exists(dir.resolve(sid + ".json")));
        assertFalse(java.nio.file.Files.exists(dir.resolve(sid + ".turns.jsonl")));
    }

    @Test
    void malformedLineIsSkipped(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "partial";
        store.appendTurn(sid, new TurnRecord(
                1, Instant.now(), 10, "q", "a", List.of(), 0, 0, 0, ""));

        Path f = dir.resolve(sid + ".turns.jsonl");
        java.nio.file.Files.writeString(f,
                java.nio.file.Files.readString(f) + "not-json-at-all\n",
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        // Append another valid line after the corrupt one.
        store.appendTurn(sid, new TurnRecord(
                2, Instant.now(), 20, "q2", "a2", List.of(), 0, 0, 0, ""));

        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(2, loaded.size(), "valid lines survive a corrupt middle line");
    }

    /**
     * Prompt 5 - lenient UTF-8 decoding on load.
     *
     * <p>A partial multi-byte-char write during a crash / power loss can leave
     * the file with an invalid UTF-8 sequence in exactly one line. Previously
     * this aborted the entire load (the strict decoder in {@code readAllLines}
     * raised {@code MalformedInputException}) and the user lost the whole
     * session transcript. The hardened loader must contain the damage to the
     * corrupt line only.
     */
    @Test
    void malformedUtf8ByteOnlyLosesAffectedLine(@TempDir Path dir) throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sid = "utf8-partial";
        Path f = dir.resolve(sid + ".turns.jsonl");

        // Build a file: [good line]\n[line with malformed UTF-8]\n[good line]\n
        store.appendTurn(sid, new TurnRecord(
                1, Instant.parse("2026-04-18T10:00:00Z"), 10,
                "before", "ok", List.of(), 0, 0, 0, ""));

        byte[] corrupt = new byte[] {
                // Three illegal UTF-8 lead bytes - the REPLACE decoder turns
                // them into U+FFFD each, producing a line that is not remotely
                // valid JSON and Jackson must reject.
                (byte) 0xFF, (byte) 0xFE, (byte) 0xFD,
                ' ', 'g', 'a', 'r', 'b', 'a', 'g', 'e',
                '\n'
        };
        java.nio.file.Files.write(f, corrupt,
                java.nio.file.StandardOpenOption.APPEND);

        store.appendTurn(sid, new TurnRecord(
                2, Instant.parse("2026-04-18T10:00:05Z"), 20,
                "after", "ok", List.of(), 0, 0, 0, ""));

        List<TurnRecord> loaded = store.loadTurns(sid);
        assertEquals(2, loaded.size(),
                "corrupt UTF-8 must only lose its own line; surrounding lines survive");
        assertEquals("before", loaded.get(0).userInput());
        assertEquals("after", loaded.get(1).userInput());
    }

    private static String turnLineWithRolefulTargets(String rolefulTargetsJson) {
        return """
                {"turnNumber":1,"timestamp":"2026-04-18T10:00:00Z","durationMs":10,
                 "userInput":"q","assistantText":"a","approvalsRequired":0,
                 "approvalsGranted":0,"approvalsDenied":0,"retrievalTraceSummary":"",
                 "status":"ok","traceId":"trc-role","policyTrace":{
                   "taskType":"FILE_EDIT","mutationAllowed":true,"verificationRequired":true,
                   "expectedTargets":["style.css"],"forbiddenTargets":[],
                   "initialPhase":"APPLY","finalPhase":"APPLY",
                   "nativeTools":["talos.write_file"],"promptTools":["talos.write_file"],
                   "blocks":[],"classificationReason":"role-test","rolefulTargets":%s},
                 "toolCalls":[]}
                """.formatted(rolefulTargetsJson)
                .replace("\r", "")
                .replace("\n", "");
    }

    private static String localTraceWithRolefulTargets(
            String sessionId,
            String traceId,
            String rolefulTargetsJson
    ) {
        return """
                {
                  "schemaVersion": 5,
                  "traceId": "%s",
                  "sessionId": "%s",
                  "turnNumber": 1,
                  "timestamp": "2026-04-18T10:00:00Z",
                  "workspaceHash": "hash",
                  "mode": "auto",
                  "model": {"backend": "test", "model": "model"},
                  "taskContract": {
                    "type": "FILE_EDIT",
                    "mutationAllowed": true,
                    "verificationRequired": true,
                    "mutationRequested": true,
                    "expectedTargets": ["style.css"],
                    "forbiddenTargets": [],
                    "classificationReason": "malformed-role",
                    "rolefulTargets": %s
                  }
                }
                """.formatted(traceId, sessionId, rolefulTargetsJson);
    }
}

