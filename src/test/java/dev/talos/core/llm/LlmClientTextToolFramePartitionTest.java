package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.core.tool.ToolNamePolicy;
import dev.talos.core.tool.ToolProtocolText;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LlmClientTextToolFramePartitionTest {

    private static final List<ChatMessage> MESSAGES =
            List.of(ChatMessage.user("Write the exact requested file."));

    private static final String COMPLETE_BACKTICK_FRAME = fenced("""
            {"name":"talos.write_file","parameters":{"path":"scripts.js","content":"result.textContent = 'Ready'; result.textContent = `BMI ${value}`;"}}
            """);

    private static final String PARTITION_FRAME = fenced("""
            {"name":"talos.write_file","parameters":{"path":"scripts.js","content":"result.textContent = 'Ready';"}}
            """);
    private static final String COMPLETE_BACKTICK_FRAME_SHA256 =
            "6cb4218708d5126ec2090cc67b47d6f29f5cc0d9c59ab1d4743de66985700b9a";
    private static final String PARTITION_FRAME_SHA256 =
            "5ee04564c9c6f3ae516f2ab07be03124984bd57fc95c7ba9df1840b10ed204c8";
    private static final String MULTI_FRAME_RESPONSE_SHA256 =
            "94b1d41c7d94e71d1efb2398a884a62dcb2c8e9253321725f539736857b6f7bb";
    private static final String SECOND_FRAME = fenced("""
            {"tool_name":"write_file","params":{"path":"styles.css","content":"body { color: red; }"}}
            """);
    private static final String THIRD_FRAME = fenced("""
            {"tool_call":{"function_name":"talos.write_file","args":{"path":"index.html","content":"<div>Ready</div>"}}}
            """);
    private static final String MULTI_FRAME_RESPONSE =
            COMPLETE_BACKTICK_FRAME + "\n" + SECOND_FRAME + "\n" + THIRD_FRAME;

    @Test
    void oneChunkBacktickFrameIsExactAcrossEveryPublicAssemblyPath() {
        assertEquals(
                COMPLETE_BACKTICK_FRAME_SHA256,
                sha256(COMPLETE_BACKTICK_FRAME),
                "fixture identity changed");
        List<String> failures = new ArrayList<>();
        for (AssemblyPath path : AssemblyPath.values()) {
            Observation observed = invoke(path, List.of(COMPLETE_BACKTICK_FRAME));
            collectIdentityFailures(
                    failures,
                    path,
                    path + " one-chunk",
                    COMPLETE_BACKTICK_FRAME,
                    observed);
        }
        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    @Test
    void everyTwoWaySplitMatchesTheOneChunkControlAcrossEveryPublicAssemblyPath() {
        assertEquals(
                PARTITION_FRAME_SHA256,
                sha256(PARTITION_FRAME),
                "fixture identity changed");
        List<String> failures = new ArrayList<>();
        for (AssemblyPath path : AssemblyPath.values()) {
            Observation control = invoke(path, List.of(PARTITION_FRAME));
                collectIdentityFailures(
                        failures,
                        path,
                        path + " one-chunk-control",
                    PARTITION_FRAME,
                    control);

            for (int split = 1; split < PARTITION_FRAME.length(); split++) {
                Observation observed = invoke(
                        path,
                        List.of(
                                PARTITION_FRAME.substring(0, split),
                                PARTITION_FRAME.substring(split)));
                collectIdentityFailures(
                        failures,
                        path,
                        path + " split=" + split,
                        PARTITION_FRAME,
                        observed);
            }
        }
        assertTrue(failures.isEmpty(), failureSummary(failures));
    }

    @Test
    void unsafeFenceTailProjectionIsExactAcrossEverySplitAndPublicAssemblyPath() {
        String selected =
                "{\"name\":\"talos.read_file\","
                        + "\"arguments\":{\"path\":\"README.md\"}}";
        String selectedHash = sha256(selected);
        List<String> responses = List.of(
                "```json\n" + selected + "\n<script>outside()</script>\n```",
                "```json\n" + selected + "\n<think>outside</think>\n```",
                "```json\n" + selected
                        + "\n\"unterminated <script>outside()</script>\n```",
                "```json\n" + selected + "\n"
                        + "{\"name\":\"talos.write_file\",\"arguments\":{"
                        + "\"path\":\"x.js\","
                        + "\"content\":\"<script>outside()</script>\"}}\n```"
        );

        List<String> failures = new ArrayList<>();
        for (String response : responses) {
            for (AssemblyPath path : AssemblyPath.values()) {
                Observation control = invoke(path, List.of(response));
                collectTailProjectionFailures(
                        failures,
                        path,
                        path + " tail-control=" + sha256(response).substring(0, 12),
                        selectedHash,
                        control);

                for (int split = 0; split <= response.length(); split++) {
                    Observation observed = invoke(
                            path,
                            List.of(
                                    response.substring(0, split),
                                    response.substring(split)));
                    String label = path + " tail="
                            + sha256(response).substring(0, 12)
                            + " split=" + split;
                    if (!control.text().equals(observed.text())) {
                        failures.add(label + " projection hash="
                                + sha256(observed.text())
                                + " expected=" + sha256(control.text()));
                    }
                    collectTailProjectionFailures(
                            failures, path, label, selectedHash, observed);
                }
            }
        }
        assertTrue(failures.isEmpty(), failureSummary(failures));
    }

    @Test
    void oneCharacterChunksMatchTheOneChunkControlAcrossEveryPublicAssemblyPath() {
        assertEquals(
                PARTITION_FRAME_SHA256,
                sha256(PARTITION_FRAME),
                "fixture identity changed");
        List<String> chunks = PARTITION_FRAME.codePoints()
                .mapToObj(Character::toString)
                .toList();
        List<String> failures = new ArrayList<>();
        for (AssemblyPath path : AssemblyPath.values()) {
            Observation observed = invoke(path, chunks);
            collectIdentityFailures(
                    failures,
                    path,
                    path + " one-code-point-chunks",
                    PARTITION_FRAME,
                    observed);
        }
        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    @Test
    void multipleAdjacentProposalsAreIndependentOfEveryTwoWaySplit() {
        assertEquals(
                MULTI_FRAME_RESPONSE_SHA256,
                sha256(MULTI_FRAME_RESPONSE),
                "realistic multi-frame fixture identity changed");
        List<ToolCall> expectedCalls = ToolCallParser.parse(MULTI_FRAME_RESPONSE);
        assertEquals(3, expectedCalls.size(), "fixture must contain three executable proposals");

        List<String> failures = new ArrayList<>();
        for (AssemblyPath path : AssemblyPath.values()) {
            for (int split = 1; split < MULTI_FRAME_RESPONSE.length(); split++) {
                Observation observed = invoke(
                        path,
                        List.of(
                                MULTI_FRAME_RESPONSE.substring(0, split),
                                MULTI_FRAME_RESPONSE.substring(split)));
                collectIdentityFailures(
                        failures,
                        path,
                        path + " multi split=" + split,
                        MULTI_FRAME_RESPONSE,
                        observed);
            }
        }
        assertTrue(failures.isEmpty(), failureSummary(failures));
    }

    @Test
    void acceptedAliasesWrappersStandaloneAndLiteralThinkArePhysicalIdentities() {
        String literal = "result.textContent = '<think>literal</think>'; "
                + "const value = `BMI ${bmi}`;";
        List<String> responses = List.of(
                fenced("{\"tool_name\":\"write_file\",\"params\":"
                        + "{\"path\":\"alias.js\",\"content\":"
                        + jsonString(literal) + "}}"),
                fenced("{\"tool_call\":{\"function_name\":\"talos.write_file\","
                        + "\"args\":{\"path\":\"wrapped.js\",\"content\":"
                        + jsonString(literal) + "}}}"),
                "{\"tool\":\"write_file\",\"arguments\":{\"path\":\"bare.js\","
                        + "\"content\":" + jsonString(literal) + "}}");

        List<String> failures = new ArrayList<>();
        for (String response : responses) {
            for (AssemblyPath path : AssemblyPath.values()) {
                Observation observed = invoke(
                        path,
                        response.codePoints().mapToObj(Character::toString).toList());
                collectIdentityFailures(
                        failures,
                        path,
                        path + " protocol-shape=" + sha256(response).substring(0, 12),
                        response,
                        observed);
            }
        }
        assertTrue(failures.isEmpty(), failureSummary(failures));
    }

    @Test
    void everyExecutableJsonGrammarDimensionSurvivesOneCharacterChunks() {
        String content = "result.textContent = '<think>literal</think>'; "
                + "const value = `BMI ${bmi}`;";
        Map<String, String> jsonShapes = new LinkedHashMap<>();
        for (String nameKey
                : List.of("name", "function", "function_name", "tool_name", "tool")) {
            jsonShapes.put(
                    "name-key-" + nameKey,
                    toolJson(nameKey, "talos.write_file", "parameters", "", content));
        }
        for (String parameterKey
                : List.of("parameters", "arguments", "args", "params")) {
            jsonShapes.put(
                    "parameter-key-" + parameterKey,
                    toolJson("name", "talos.write_file", parameterKey, "", content));
        }
        for (String wrapper : List.of("tool_call", "function_call")) {
            jsonShapes.put(
                    "wrapper-" + wrapper,
                    toolJson(
                            "name",
                            "talos.write_file",
                            "parameters",
                            wrapper,
                            content));
        }
        for (String toolName : ToolNamePolicy.acceptedToolNames().stream().sorted().toList()) {
            jsonShapes.put(
                    "accepted-name-" + toolName,
                    toolJson("name", toolName, "arguments", "", content));
        }
        jsonShapes.put(
                "custom-talos-name",
                toolJson("name", "talos.echo", "arguments", "", content));

        Set<Map.Entry<String, String>> responses = new LinkedHashSet<>();
        for (Map.Entry<String, String> shape : jsonShapes.entrySet()) {
            responses.add(Map.entry(shape.getKey() + "-fenced", fenced(shape.getValue())));
            responses.add(Map.entry(shape.getKey() + "-standalone", shape.getValue()));
        }

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> response : responses) {
            List<ToolCall> fixtureCalls = ToolCallParser.parse(response.getValue());
            assertEquals(1, fixtureCalls.size(), response.getKey());
            assertEquals(content, fixtureCalls.getFirst().param("content"), response.getKey());

            List<String> chunks = response.getValue().codePoints()
                    .mapToObj(Character::toString)
                    .toList();
            for (AssemblyPath path : AssemblyPath.values()) {
                Observation observed = invoke(path, chunks);
                collectIdentityFailures(
                        failures,
                        path,
                        path + " " + response.getKey(),
                        response.getValue(),
                        observed);
            }
        }
        assertTrue(failures.isEmpty(), failureSummary(failures));
    }

    @Test
    void surrogatePairSplitInsideArgumentRemainsExact() {
        String response = fenced("""
                {"name":"talos.write_file","parameters":{"path":"unicode.txt","content":"before \uD83D\uDE00 after"}}
                """);
        int split = response.indexOf("\uD83D\uDE00") + 1;
        List<String> failures = new ArrayList<>();
        for (AssemblyPath path : AssemblyPath.values()) {
            Observation observed = invoke(
                    path,
                    List.of(response.substring(0, split), response.substring(split)));
            collectIdentityFailures(
                    failures,
                    path,
                    path + " surrogate split",
                    response,
                    observed);
        }
        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    @Test
    void exactRawCapPassesWhileBoundaryPlusOneAndMidFrameFailClosed() {
        for (AssemblyPath path : AssemblyPath.values()) {
            Observation exact = invoke(
                    path,
                    List.of(
                            PARTITION_FRAME.substring(0, PARTITION_FRAME.length() / 2),
                            PARTITION_FRAME.substring(PARTITION_FRAME.length() / 2)),
                    PARTITION_FRAME.length());
            List<String> exactFailures = new ArrayList<>();
            collectIdentityFailures(
                    exactFailures,
                    path,
                    path + " exact-cap",
                    PARTITION_FRAME,
                    exact);
            assertTrue(exactFailures.isEmpty(), String.join(
                    System.lineSeparator(), exactFailures));

            assertFailClosedCap(
                    path,
                    PARTITION_FRAME,
                    PARTITION_FRAME.length() - 1,
                    "boundary+1");
            assertFailClosedCap(
                    path,
                    PARTITION_FRAME,
                    PARTITION_FRAME.indexOf("\"content\"") + 5,
                    "mid-frame");
        }
    }

    @Test
    void everyPureProtocolCapPrefixIsPartitionInvariantAndFailsClosed() {
        List<String> proposals = List.of(
                fenced("{\"name\":\"talos.grep\",\"arguments\":{\"pattern\":\"x\"}}"),
                "{\"name\":\"write_file\",\"arguments\":{\"path\":\"x\",\"content\":\"y\"}}");
        for (String proposal : proposals) {
            String overBoundary = proposal + "x";
            for (AssemblyPath path : AssemblyPath.values()) {
                for (int cap = 1; cap <= proposal.length(); cap++) {
                    Observation oneChunk = invoke(path, List.of(overBoundary), cap);
                    Observation splitAtCap = invoke(
                            path,
                            List.of(
                                    overBoundary.substring(0, cap),
                                    overBoundary.substring(cap)),
                            cap);
                    String label = path + " cap=" + cap;

                    assertEquals(oneChunk.text(), splitAtCap.text(), label);
                    assertEquals("", oneChunk.text(), label);
                    assertTrue(ToolCallParser.parse(oneChunk.text()).isEmpty(), label);
                    if (path.streaming()) {
                        assertEquals(oneChunk.text(), oneChunk.streamed(), label);
                        assertEquals(splitAtCap.text(), splitAtCap.streamed(), label);
                    }
                    if (oneChunk.completion() != null) {
                        assertEquals(
                                ProviderResponseCapture.Completion.RUNTIME_SAFE_CAP,
                                oneChunk.completion(),
                                label);
                        assertTrue(oneChunk.truncated(), label);
                        assertTrue(Boolean.TRUE.equals(oneChunk.normalized()), label);
                    }
                }
            }
        }
    }

    @Test
    void aCompletedFirstProposalCannotSurviveASecondCappedProposal() {
        String response = PARTITION_FRAME
                + "\n{\"name\":\"talos.write_file\",\"arguments\":"
                + "{\"path\":\"second.txt\",\"content\":\"unfinished";
        int cap = response.length() - 1;

        for (AssemblyPath path : AssemblyPath.values()) {
            assertFailClosedCap(
                    path,
                    response,
                    cap,
                    "complete-first-incomplete-second");
        }
    }

    @Test
    void providerEosWithAnyUnsettledSecondProposalRejectsTheWholeTextualSet() {
        List<String> secondProposals = List.of(
                fenced("{\"name\":\"talos.write_file\",\"arguments\":"
                        + "{\"path\":\"second.txt\",\"content\":\"Ready\"}}"),
                "{\"name\":\"write_file\",\"arguments\":"
                        + "{\"path\":\"second.txt\",\"content\":\"Ready\"}}");
        List<String> failures = new ArrayList<>();

        for (String second : secondProposals) {
            for (int prefixLength = 1; prefixLength < second.length(); prefixLength++) {
                String response = PARTITION_FRAME + "\n"
                        + second.substring(0, prefixLength);
                for (AssemblyPath path : AssemblyPath.values()) {
                    Observation observed = invoke(path, List.of(response));
                    String label = path + " second-prefix=" + prefixLength
                            + " kind=" + (second.startsWith("```") ? "fenced" : "standalone");

                    if (!observed.text().isEmpty()) {
                        failures.add(label + " leaked text: " + observed.text());
                    }
                    if (!ToolCallParser.parse(observed.text()).isEmpty()) {
                        failures.add(label + " retained an executable first proposal");
                    }
                    if (path.streaming() && !observed.text().equals(observed.streamed())) {
                        failures.add(label + " callback/result mismatch");
                    }
                    if (observed.completion() != null
                            && observed.completion()
                                    != ProviderResponseCapture.Completion.PROVIDER_EOS) {
                        failures.add(label + " completion=" + observed.completion());
                    }
                    if (observed.normalized() != null
                            && !Boolean.TRUE.equals(observed.normalized())) {
                        failures.add(label + " missing normalization evidence");
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), failureSummary(failures));
    }

    @Test
    void leadingMixedJsonSequenceIsExactAcrossPublicPathsAndDisplayFiltering() {
        String leading = " \t\u2003";
        String ordinary = "{\"label\":\"ordinary\"}";
        String intermediate = "[1]true\"value\"null";
        String read = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String response = leading + ordinary + intermediate + read;
        String displayed = leading + ordinary + intermediate;
        assertEquals(List.of(read),
                ToolProtocolText.selectExecutableJsonProposals(response)
                        .executionOrder().stream()
                        .map(span -> span.rootFrom(response))
                        .toList());

        for (int split = 0; split <= response.length(); split++) {
            List<String> chunks = List.of(
                    response.substring(0, split), response.substring(split));
            for (AssemblyPath path : AssemblyPath.values()) {
                Observation observed = invoke(path, chunks);
                assertEquals(response, observed.text(), path + " split=" + split);
                if (path.streaming()) {
                    assertEquals(response, observed.streamed(),
                            path + " callback split=" + split);
                }
            }
            assertEquals(response, invokeWithNullSink(false, chunks),
                    "legacy null split=" + split);
            assertEquals(response, invokeWithNullSink(true, chunks),
                    "full null split=" + split);
        }

        PartitionResolver resolver = new PartitionResolver(
                response.codePoints().mapToObj(Character::toString).toList());
        try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
            StringBuilder visible = new StringBuilder();
            ToolCallStreamFilter filter = new ToolCallStreamFilter(visible::append);
            LlmClient.StreamResult result = client.chatStreamFull(
                    MESSAGES, filter, 5_000L, List.of());
            filter.flush();

            assertEquals(response, result.text());
            assertEquals(1, ToolCallParser.parse(result.text()).size());
            assertEquals(displayed, visible.toString());
        }
    }

    @Test
    void incompleteLeadingAdjacentProposalIsSinkIndependentAtProviderEos() {
        String leading = " \t\u2003";
        String ordinary = "{\"label\":\"ordinary\"}";
        String intermediate = "[1]true\"value\"null";
        String read = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String expected = leading + ordinary;

        for (int prefixLength = 1; prefixLength < read.length(); prefixLength++) {
            String response = leading + ordinary + intermediate
                    + read.substring(0, prefixLength);
            for (AssemblyPath path : AssemblyPath.values()) {
                Observation observed = invoke(path, List.of(response));
                assertEquals(expected, observed.text(),
                        path + " prefix=" + prefixLength);
                if (path.streaming()) {
                    assertEquals(expected, observed.streamed(),
                            path + " callback prefix=" + prefixLength);
                }
            }
            assertEquals(expected, invokeWithNullSink(false, List.of(response)),
                    "legacy null prefix=" + prefixLength);
            assertEquals(expected, invokeWithNullSink(true, List.of(response)),
                    "full null prefix=" + prefixLength);
        }
    }

    @Test
    void completeJsonIsRemovedAtEveryNonAuthoritativeProviderTerminal() {
        List<TerminalCase> cases = List.of(
                new TerminalCase(
                        "stream-without-eos",
                        List.of(TokenChunk.of(PARTITION_FRAME)),
                        ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS),
                new TerminalCase(
                        "transport-eof",
                        List.of(
                                TokenChunk.of(PARTITION_FRAME),
                                TokenChunk.transportEof("stop")),
                        ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS),
                new TerminalCase(
                        "unrecognized-stop",
                        List.of(
                                TokenChunk.of(PARTITION_FRAME),
                                TokenChunk.eos("content_filter")),
                        ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP),
                new TerminalCase(
                        "provider-length",
                        List.of(
                                TokenChunk.of(PARTITION_FRAME),
                                TokenChunk.eos("length")),
                        ProviderResponseCapture.Completion.PROVIDER_LENGTH));

        for (TerminalCase terminalCase : cases) {
            for (AssemblyPath path : AssemblyPath.values()) {
                Observation observed = invokeTerminal(path, terminalCase.chunks());
                String label = terminalCase.label() + " " + path;

                assertEquals("", observed.text(), label);
                assertTrue(ToolCallParser.parse(observed.text()).isEmpty(), label);
                if (path.streaming()) {
                    assertEquals(observed.text(), observed.streamed(), label);
                } else {
                    assertEquals("", observed.streamed(), label);
                }
                if (observed.completion() != null) {
                    assertEquals(
                            terminalCase.completion(),
                            observed.completion(),
                            label);
                    assertTrue(Boolean.TRUE.equals(observed.normalized()), label);
                }
            }
        }
    }

    @Test
    void completeAndIncompleteJsonAreRemovedFromCancelledProviderText() {
        String partial = PARTITION_FRAME
                + "\n{\"name\":\"talos.write_file\",\"arguments\":"
                + "{\"path\":\"second.txt\",\"content\":\"unfinished";

        for (boolean streaming : List.of(false, true)) {
            AtomicBoolean cancelled = new AtomicBoolean();
            CancelAfterTextResolver resolver =
                    new CancelAfterTextResolver(partial, cancelled);
            try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
                client.setCancelSupplier(cancelled::get);
                StringBuilder callback = new StringBuilder();
                LlmClient.StreamResult result = streaming
                        ? client.chatStreamFull(
                                MESSAGES, callback::append, 5_000L, List.of())
                        : client.chatFull(MESSAGES, 5_000L, List.of());
                String label = streaming ? "streaming" : "buffered";

                assertEquals(
                        ProviderResponseCapture.Completion.RUNTIME_ABORT,
                        result.completion(),
                        label);
                assertTrue(result.aborted(), label);
                assertTrue(result.normalized(), label);
                assertTrue(ToolCallParser.parse(result.text()).isEmpty(), label);
                assertFalse(result.text().contains("talos.write_file"), result.text());
                assertFalse(result.text().contains("```"), result.text());
                assertTrue(result.text().contains("[turn aborted:"), result.text());
                if (streaming) {
                    assertEquals("", callback.toString(),
                            "runtime abort marker is appended after provider streaming");
                } else {
                    assertEquals("", callback.toString(), label);
                }
            }
        }
    }

    @Test
    void everyNonemptyDecodedProviderChunkReportsProgressWhileProtocolIsBuffered() {
        AtomicInteger heartbeats = new AtomicInteger();
        List<String> payloads = new ArrayList<>();

        LlmClient.reportDecodedProviderProgress("", value -> {
            heartbeats.incrementAndGet();
            payloads.add(value);
        });
        LlmClient.reportDecodedProviderProgress("{", value -> {
            heartbeats.incrementAndGet();
            payloads.add(value);
        });
        LlmClient.reportDecodedProviderProgress("still pending", value -> {
            heartbeats.incrementAndGet();
            payloads.add(value);
        });

        assertEquals(2, heartbeats.get());
        assertEquals(List.of("{", "still pending"), payloads,
                "decoded bytes must reach the repetition breaker exactly once");
    }

    private static void assertFailClosedCap(
            AssemblyPath path,
            String response,
            int cap,
            String label) {
        Observation control = invoke(path, List.of(response), cap);
        Observation split = invoke(
                path,
                List.of(response.substring(0, cap), response.substring(cap)),
                cap);

        assertEquals(control.text(), split.text(), path + " " + label + " partition");
        assertTrue(ToolCallParser.parse(control.text()).isEmpty(),
                path + " " + label + " must not retain executable protocol");
        assertFalse(control.text().contains("talos.write_file"),
                path + " " + label + " leaked incomplete protocol");
        if (path.streaming()) {
            assertEquals(control.text(), control.streamed(),
                    path + " " + label + " callback/result mismatch");
            assertEquals(split.text(), split.streamed(),
                    path + " " + label + " split callback/result mismatch");
        }
        if (control.completion() != null) {
            assertEquals(
                    ProviderResponseCapture.Completion.RUNTIME_SAFE_CAP,
                    control.completion(),
                    path + " " + label + " completion");
            assertTrue(control.truncated(), path + " " + label + " truncation evidence");
        }
    }

    @Test
    void externalEscapedThinkEnvelopeSuppressesProposalAndSettlesAtProviderEos() {
        String escapedOpen = "\\" + "u003cthink" + "\\" + "u003e";
        String escapedClose = "\\" + "u003c/think" + "\\" + "u003e";
        String response = escapedOpen + "\n" + PARTITION_FRAME + "\n" + escapedClose;
        List<String> chunks = response.codePoints()
                .mapToObj(Character::toString)
                .toList();

        PartitionResolver bufferedResolver = new PartitionResolver(chunks);
        try (LlmClient client = new LlmClient(engineConfig(), bufferedResolver)) {
            LlmClient.StreamResult result = client.chatFull(MESSAGES, 5_000L, List.of());
            assertEquals("", result.text());
            assertTrue(result.normalized());
            assertEquals(
                    ProviderResponseCapture.Completion.PROVIDER_EOS,
                    result.completion());
            assertTrue(ToolCallParser.parse(result.text()).isEmpty());
            assertTrue(bufferedResolver.handoffObserved());
        }

        PartitionResolver streamingResolver = new PartitionResolver(chunks);
        try (LlmClient client = new LlmClient(engineConfig(), streamingResolver)) {
            StringBuilder streamed = new StringBuilder();
            LlmClient.StreamResult result =
                    client.chatStreamFull(MESSAGES, streamed::append, 5_000L, List.of());
            assertEquals("", result.text());
            assertEquals("", streamed.toString());
            assertTrue(result.normalized());
            assertEquals(
                    ProviderResponseCapture.Completion.PROVIDER_EOS,
                    result.completion());
            assertTrue(ToolCallParser.parse(result.text()).isEmpty());
            assertTrue(streamingResolver.handoffObserved());
        }
    }

    private static String invokeWithNullSink(
            boolean full,
            List<String> chunks) {
        PartitionResolver resolver = new PartitionResolver(chunks);
        try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
            if (!full) {
                return client.chatStream(MESSAGES, null);
            }
            return client.chatStreamFull(
                    MESSAGES, null, 5_000L, List.of()).text();
        }
    }

    private static Observation invoke(AssemblyPath path, List<String> chunks) {
        return invoke(path, chunks, 10 * 1024 * 1024);
    }

    private static Observation invoke(
            AssemblyPath path,
            List<String> chunks,
            int responseCap) {
        PartitionResolver resolver = new PartitionResolver(chunks);
        try (LlmClient client = new LlmClient(engineConfig(responseCap), resolver)) {
            StringBuilder streamed = new StringBuilder();
            return switch (path) {
                case LEGACY_BUFFERED -> new Observation(
                        client.chat(MESSAGES),
                        null,
                        "",
                        resolver.handoffObserved(),
                        null,
                        null,
                        resolver.consumedTextChunks,
                        chunks.size());
                case LEGACY_STREAMING -> {
                    String text = client.chatStream(MESSAGES, streamed::append);
                    yield new Observation(
                            text,
                            null,
                            streamed.toString(),
                            resolver.handoffObserved(),
                            null,
                            null,
                            resolver.consumedTextChunks,
                            chunks.size());
                }
                case FULL_BUFFERED -> {
                    LlmClient.StreamResult result = client.chatFull(MESSAGES, 5_000L, List.of());
                    yield new Observation(
                            result.text(),
                            result.normalized(),
                            "",
                            resolver.handoffObserved(),
                            result.completion(),
                            result.truncated(),
                            resolver.consumedTextChunks,
                            chunks.size());
                }
                case FULL_STREAMING -> {
                    LlmClient.StreamResult result =
                            client.chatStreamFull(MESSAGES, streamed::append, 5_000L, List.of());
                    yield new Observation(
                            result.text(),
                            result.normalized(),
                            streamed.toString(),
                            resolver.handoffObserved(),
                            result.completion(),
                            result.truncated(),
                            resolver.consumedTextChunks,
                            chunks.size());
                }
            };
        }
    }

    private static Observation invokeTerminal(
            AssemblyPath path,
            List<TokenChunk> chunks) {
        TerminalResolver resolver = new TerminalResolver(chunks);
        try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
            StringBuilder streamed = new StringBuilder();
            return switch (path) {
                case LEGACY_BUFFERED -> new Observation(
                        client.chat(MESSAGES),
                        null,
                        "",
                        true,
                        null,
                        null,
                        0,
                        0);
                case LEGACY_STREAMING -> new Observation(
                        client.chatStream(MESSAGES, streamed::append),
                        null,
                        streamed.toString(),
                        true,
                        null,
                        null,
                        0,
                        0);
                case FULL_BUFFERED -> {
                    LlmClient.StreamResult result =
                            client.chatFull(MESSAGES, 5_000L, List.of());
                    yield new Observation(
                            result.text(),
                            result.normalized(),
                            "",
                            true,
                            result.completion(),
                            result.truncated(),
                            0,
                            0);
                }
                case FULL_STREAMING -> {
                    LlmClient.StreamResult result = client.chatStreamFull(
                            MESSAGES, streamed::append, 5_000L, List.of());
                    yield new Observation(
                            result.text(),
                            result.normalized(),
                            streamed.toString(),
                            true,
                            result.completion(),
                            result.truncated(),
                            0,
                            0);
                }
            };
        }
    }

    private static void collectIdentityFailures(
            List<String> failures,
            AssemblyPath path,
            String label,
            String expected,
            Observation observed) {
        if (!expected.equals(observed.text())) {
            failures.add(label + " text mismatch: expectedHash=" + sha256(expected)
                    + " actualHash=" + sha256(observed.text())
                    + " expectedChars=" + expected.length()
                    + " actualChars=" + observed.text().length());
            return;
        }
        if (!sha256(expected).equals(sha256(observed.text()))) {
            failures.add(label + " UTF-8 hash mismatch");
        }
        if (Boolean.TRUE.equals(observed.normalized())) {
            failures.add(label + " exact frame was marked normalized");
        }
        if (path.streaming() && !expected.equals(observed.streamed())) {
            failures.add(label + " callback aggregate mismatch: callbackHash="
                    + sha256(observed.streamed()));
        }
        if (!path.streaming() && !observed.streamed().isEmpty()) {
            failures.add(label + " buffered path unexpectedly emitted callback text");
        }
        if (!observed.handoffObserved()) {
            failures.add(label + " did not cross the physical provider handoff seam");
        }
        if (observed.completion() != null
                && observed.completion()
                        != ProviderResponseCapture.Completion.PROVIDER_EOS) {
            failures.add(label + " completion=" + observed.completion()
                    + " expected=PROVIDER_EOS");
        }
        if (Boolean.TRUE.equals(observed.truncated())) {
            failures.add(label + " exact response was marked truncated");
        }
        if (observed.consumedTextChunks() != observed.expectedTextChunks()) {
            failures.add(label + " consumedTextChunks=" + observed.consumedTextChunks()
                    + " expected=" + observed.expectedTextChunks());
        }

        List<ToolCall> expectedCalls = ToolCallParser.parse(expected);
        List<ToolCall> parsed = ToolCallParser.parse(observed.text());
        if (!expectedCalls.equals(parsed)) {
            failures.add(label + " parsed calls differ: expected="
                    + expectedCalls + " actual=" + parsed);
        }
    }

    private static void collectTailProjectionFailures(
            List<String> failures,
            AssemblyPath path,
            String label,
            String selectedRootHash,
            Observation observed) {
        if (observed.text().contains("<script>")
                || observed.text().contains("outside()")
                || observed.text().contains("<think>")
                || observed.text().contains("outside</think>")) {
            failures.add(label + " retained unsafe tail: " + observed.text());
            return;
        }
        List<ToolCall> calls = ToolCallParser.parse(observed.text());
        if (calls.size() != 1
                || !"talos.read_file".equals(calls.getFirst().toolName())
                || !"README.md".equals(calls.getFirst().param("path"))) {
            failures.add(label + " selected calls=" + calls);
            return;
        }
        List<ToolProtocolText.SelectedExecutableJsonSpan> selected =
                ToolProtocolText.recognizedExecutableJsonSpans(observed.text());
        if (selected.size() != 1
                || !selectedRootHash.equals(
                        sha256(selected.getFirst().rootFrom(observed.text())))) {
            failures.add(label + " selected-root identity mismatch");
        }
        if (observed.normalized() != null
                && !Boolean.TRUE.equals(observed.normalized())) {
            failures.add(label + " missing normalization evidence");
        }
        if (path.streaming() && !observed.text().equals(observed.streamed())) {
            failures.add(label + " callback/result mismatch");
        }
        if (!path.streaming() && !observed.streamed().isEmpty()) {
            failures.add(label + " buffered path emitted callback text");
        }
        if (!observed.handoffObserved()) {
            failures.add(label + " missed provider handoff");
        }
        if (observed.completion() != null
                && observed.completion()
                != ProviderResponseCapture.Completion.PROVIDER_EOS) {
            failures.add(label + " completion=" + observed.completion());
        }
        if (Boolean.TRUE.equals(observed.truncated())) {
            failures.add(label + " marked truncated");
        }
        if (observed.consumedTextChunks() != observed.expectedTextChunks()) {
            failures.add(label + " consumedTextChunks="
                    + observed.consumedTextChunks()
                    + " expected=" + observed.expectedTextChunks());
        }
    }

    private static String failureSummary(List<String> failures) {
        int limit = Math.min(40, failures.size());
        String first = String.join(
                System.lineSeparator(),
                failures.subList(0, limit));
        if (failures.size() <= limit) {
            return first;
        }
        return "failures=" + failures.size() + System.lineSeparator()
                + first + System.lineSeparator()
                + "... " + (failures.size() - limit) + " additional failures";
    }

    private static Config engineConfig() {
        return engineConfig(10 * 1024 * 1024);
    }

    private static Config engineConfig(int responseCap) {
        Config config = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "partition-fixture");
        llm.put("model", "partition-fixture");
        config.data.put("llm", llm);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("response_max_chars", responseCap);
        config.data.put("limits", limits);
        return config;
    }

    private static String fenced(String json) {
        return "```json\n" + json.strip() + "\n```";
    }

    private static String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private static String toolJson(
            String nameKey,
            String toolName,
            String parameterKey,
            String wrapper,
            String content) {
        String call = "{\"" + nameKey + "\":" + jsonString(toolName)
                + ",\"" + parameterKey + "\":{"
                + "\"path\":\"scripts.js\",\"content\":" + jsonString(content)
                + "}}";
        return wrapper == null || wrapper.isEmpty()
                ? call
                : "{\"" + wrapper + "\":" + call + "}";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    private enum AssemblyPath {
        LEGACY_BUFFERED,
        LEGACY_STREAMING,
        FULL_BUFFERED,
        FULL_STREAMING;

        private boolean streaming() {
            return this == LEGACY_STREAMING || this == FULL_STREAMING;
        }
    }

    private record Observation(
            String text,
            Boolean normalized,
            String streamed,
            boolean handoffObserved,
            ProviderResponseCapture.Completion completion,
            Boolean truncated,
            int consumedTextChunks,
            int expectedTextChunks) {
    }

    private record TerminalCase(
            String label,
            List<TokenChunk> chunks,
            ProviderResponseCapture.Completion completion) {
    }

    private static final class PartitionResolver implements LlmEngineResolver {
        private final List<String> chunks;
        private boolean handoffObserved;
        private int consumedTextChunks;

        private PartitionResolver(List<String> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        private boolean handoffObserved() {
            return handoffObserved;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768, false);
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return true;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return response();
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            handoffObserved = true;
            return response();
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            handoffObserved = true;
            return response();
        }

        private Stream<TokenChunk> response() {
            return Stream.concat(
                    chunks.stream().map(chunk -> {
                        consumedTextChunks++;
                        return TokenChunk.of(chunk);
                    }),
                    Stream.of(TokenChunk.eos("stop")));
        }

        @Override
        public void close() {
        }
    }

    private static final class TerminalResolver implements LlmEngineResolver {
        private final List<TokenChunk> chunks;

        private TerminalResolver(List<TokenChunk> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768, false);
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return true;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return chunks.stream();
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public void close() {
        }
    }

    private static final class CancelAfterTextResolver implements LlmEngineResolver {
        private final String text;
        private final AtomicBoolean cancelled;

        private CancelAfterTextResolver(String text, AtomicBoolean cancelled) {
            this.text = text;
            this.cancelled = cancelled;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768, false);
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return true;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private int state;

                @Override
                public boolean hasNext() {
                    if (state == 0) return true;
                    if (state == 1) {
                        cancelled.set(true);
                        return true;
                    }
                    return false;
                }

                @Override
                public TokenChunk next() {
                    if (state == 0) {
                        state = 1;
                        return TokenChunk.of(text);
                    }
                    if (state == 1) {
                        state = 2;
                        return TokenChunk.eos("stop");
                    }
                    throw new NoSuchElementException();
                }
            };
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(iterator, 0),
                    false);
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public void close() {
        }
    }
}
