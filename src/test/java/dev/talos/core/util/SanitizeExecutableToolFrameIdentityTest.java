package dev.talos.core.util;

import dev.talos.core.tool.ToolNamePolicy;
import dev.talos.runtime.ToolCallParser;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SanitizeExecutableToolFrameIdentityTest {

    private static final String READY_SCRIPT =
            "result.textContent = 'Ready'; result.textContent = `BMI ${value}`;";
    private static final String READY_FRAME_SHA256 =
            "6cb4218708d5126ec2090cc67b47d6f29f5cc0d9c59ab1d4743de66985700b9a";
    private static final String LITERAL_THINK_FRAME_SHA256 =
            "79019553c28ab3dab985ddb48dd7224a9bd0f88eb812f9b750187e1a34ea9203";

    @Test
    void completeRecognizedFenceWithTemplateLiteralRemainsByteIdentical() {
        String input = fenced("""
                {"name":"talos.write_file","parameters":{"path":"scripts.js","content":"result.textContent = 'Ready'; result.textContent = `BMI ${value}`;"}}
                """);

        assertEquals(READY_FRAME_SHA256, sha256(input), "fixture identity changed");
        assertExactExecutableFrame(input, "talos.write_file", READY_SCRIPT);
    }

    @Test
    void everyAcceptedFenceLineBreakKeepsTheSelectedRootExactAndSanitizesControls() {
        String root = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        for (String lineBreak : List.of(
                "\r\n", "\n", "\r", "\u000B", "\f",
                "\u0085", "\u2028", "\u2029")) {
            String input = "```json" + lineBreak + root + "```";

            String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

            assertEquals(1, ToolCallParser.parse(output).size(), output);
            assertEquals(root,
                    dev.talos.core.tool.ToolProtocolText
                            .recognizedExecutableJsonSpans(output)
                            .getFirst()
                            .rootFrom(output));
            if (lineBreak.equals("\u000B") || lineBreak.equals("\f")) {
                assertFalse(output.contains(lineBreak), output);
                assertEquals("```json\n" + root + "```", output);
            } else {
                assertEquals(input, output,
                        "line break U+"
                                + Integer.toHexString(lineBreak.codePointAt(0)));
            }
        }
    }

    @Test
    void controlRemovalCannotManufactureRecognizedXmlCompatibilityProtocol() {
        String input = "<tool\u001b[31m_call>"
                + "{\"name\":\"talos.write_file\",\"parameters\":{"
                + "\"path\":\"x.html\","
                + "\"content\":\"<script>bad()</script>\"}}"
                + "</tool_call>";
        assertFalse(dev.talos.core.tool.ToolProtocolText
                .containsRecognizedXmlToolCall(input));

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertFalse(dev.talos.core.tool.ToolProtocolText
                .containsRecognizedXmlToolCall(output), output);
        assertFalse(output.contains("bad()"), output);
        assertFalse(output.contains("<script>"), output);
    }

    @Test
    void manufacturedXmlCannotBorrowIdentityFromALaterIdenticalSourceBlock() {
        String block = "<tool_call>"
                + "{\"name\":\"talos.write_file\",\"parameters\":{"
                + "\"path\":\"x.txt\",\"content\":\"safe\"}}"
                + "</tool_call>";
        String input = block.replace("<tool_call>", "<tool\u001b[31m_call>")
                + block;

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertFalse(dev.talos.core.tool.ToolProtocolText
                .containsRecognizedXmlToolCall(output), output);
    }

    @Test
    void alreadyRecognizedXmlRetainsItsLegacyControlNormalization() {
        String input = "<tool_call>"
                + "{\"name\":\"talos.read_file\",\"parameters\":{"
                + "\"path\":\"A\u001b[31mB\"}}"
                + "</tool_call>";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals(input.replace("\u001b[31m", ""), output);
        assertTrue(dev.talos.core.tool.ToolProtocolText
                .containsRecognizedXmlToolCall(output), output);
    }

    @Test
    void selectedJsonRootRemainsOpaqueInsideCrossingLegacyXml() {
        String root = "{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\","
                + "\"content\":\"A\u001b[31mB\"}}";
        String input = "<tool_call>{\"name\":\"talos.read_file\"}\n"
                + root
                + "\n</tool_call>";

        dev.talos.core.tool.ToolProtocolText.JsonProposalSelection selection =
                dev.talos.core.tool.ToolProtocolText
                        .selectExecutableJsonProposals(input);
        assertEquals(1, selection.executionOrder().size());
        assertEquals(root, selection.executionOrder().getFirst().rootFrom(input));
        assertTrue(dev.talos.core.tool.ToolProtocolText
                .containsRecognizedXmlToolCall(input));

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals(input, output);
        assertEquals(root, dev.talos.core.tool.ToolProtocolText
                .selectExecutableJsonProposals(output)
                .executionOrder().getFirst().rootFrom(output));
    }

    @Test
    void crossingLegacyXmlUsesTheSameGapSanitizationAsJsonAssembly() {
        String root = "{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"safe\"}}";
        String script = "<script>outside()</script>";
        String input = "<tool_call>{\"name\":\"talos.read_file\"}"
                + script + "\n" + root + "\n</tool_call>";
        String expected = input.replace(script, "");

        assertEquals(1, dev.talos.core.tool.ToolProtocolText
                .selectExecutableJsonProposals(input)
                .executionOrder().size());
        assertTrue(dev.talos.core.tool.ToolProtocolText
                .containsRecognizedXmlToolCall(input));

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals(expected, output);
        assertFalse(output.contains("outside()"), output);
        assertEquals(root, dev.talos.core.tool.ToolProtocolText
                .selectExecutableJsonProposals(output)
                .executionOrder().getFirst().rootFrom(output));
    }

    @Test
    void crossingLegacyXmlUsesTheSelectedFenceFrameTransformForEveryAcceptedLineBreak() {
        String root = "{\"name\":\"talos.write_file\","
                + "\"arguments\":{\"path\":\"x\",\"content\":\"ok\"}}";
        for (String lineBreak : List.of(
                "\r\n", "\n", "\r", "\u000B", "\f",
                "\u0085", "\u2028", "\u2029")) {
            String input = "<tool_call>```json" + lineBreak
                    + root + lineBreak + "```</tool_call>";
            String normalizedLineBreak = lineBreak.equals("\u000B")
                            || lineBreak.equals("\f")
                    ? "\n"
                    : lineBreak;
            String expected = "<tool_call>```json" + normalizedLineBreak
                    + root + normalizedLineBreak + "```</tool_call>";

            assertTrue(dev.talos.core.tool.ToolProtocolText
                    .containsRecognizedXmlToolCall(input),
                    "raw XML precondition U+"
                            + Integer.toHexString(lineBreak.codePointAt(0)));
            assertEquals(1, dev.talos.core.tool.ToolProtocolText
                    .selectExecutableJsonProposals(input)
                    .executionOrder().size(),
                    "raw JSON precondition U+"
                            + Integer.toHexString(lineBreak.codePointAt(0)));

            String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

            assertEquals(expected, output,
                    "line break U+"
                            + Integer.toHexString(lineBreak.codePointAt(0)));
            assertEquals(root, dev.talos.core.tool.ToolProtocolText
                    .selectExecutableJsonProposals(output)
                    .executionOrder().getFirst().rootFrom(output));
            assertTrue(dev.talos.core.tool.ToolProtocolText
                    .containsRecognizedXmlToolCall(output), output);
        }
    }

    @Test
    void manyCrossingXmlFramesUseAMonotonicProjectionCursor() {
        int blockCount = 64_000;
        StringBuilder source = new StringBuilder(6_000_000);
        for (int i = 0; i < blockCount; i++) {
            source.append("<tool_call>```json\n")
                    .append("{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"")
                    .append(i)
                    .append("\"}}\n```</tool_call>\n");
        }
        String input = source.toString();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(3),
                () -> assertEquals(
                        input,
                        Sanitize.sanitizeForOutputPreservingToolCalls(input)));
    }

    @Test
    void unmatchedXmlOpenerFamiliesAreScannedWithinABoundedPass() {
        String input = "<tool_call>".repeat(8_000);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2),
                () -> assertEquals(
                        input,
                        Sanitize.sanitizeForOutputPreservingToolCalls(input)));
    }

    @Test
    void repeatedCompleteLegacyXmlBlocksUseAMonotonicTokenScan() {
        String block = "<tool_call>"
                + "{\"name\":\"talos.read_file\",\"arguments\":{}}"
                + "</tool_call>";
        String input = block.repeat(16_000);
        Sanitize.sanitizeForOutputPreservingToolCalls(block.repeat(32));

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2),
                () -> assertEquals(
                        input,
                        Sanitize.sanitizeForOutputPreservingToolCalls(input)));
    }

    @Test
    void manufacturedThinkBlocksAndSelectedRootsUseAMonotonicOverlapSweep() {
        int count = 30_000;
        StringBuilder source = new StringBuilder(3_500_000);
        StringBuilder expected = new StringBuilder(1_500_000);
        for (int index = 0; index < count; index++) {
            source.append("<thi\u001b[31mnk>x</thi\u001b[31mnk>\n");
            expected.append('\n');
        }
        for (int index = 0; index < count; index++) {
            String root = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"" + index + "\"}}";
            source.append(root).append('\n');
            expected.append(root).append('\n');
        }

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(4),
                () -> assertEquals(
                        expected.toString(),
                        Sanitize.sanitizeForOutputPreservingToolCalls(
                                source.toString())));
    }

    @Test
    void openingTagTerminatorsOutsideSelectedRootGapsUseBoundedSearch() {
        int count = 70_000;
        StringBuilder source = new StringBuilder(2_500_000);
        java.util.List<String> expectedRoots = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String root = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"" + index + "\"}}";
            source.append("<script\n").append(root).append('\n');
            expectedRoots.add(root);
        }
        source.append('>');
        String input = source.toString();

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(4),
                () -> {
                    String output = Sanitize
                            .sanitizeForOutputPreservingToolCalls(input);
                    assertEquals(input, output);
                    var selected = dev.talos.core.tool.ToolProtocolText
                            .selectExecutableJsonProposals(output)
                            .sourceOrder();
                    assertEquals(count, selected.size());
                    for (int index = 0; index < count; index++) {
                        assertEquals(
                                expectedRoots.get(index),
                                selected.get(index).rootFrom(output));
                    }
                });
    }

    @Test
    void closingTagsAcrossManySelectedRootGapsUseBoundedSearch() {
        int count = 50_000;
        StringBuilder source = new StringBuilder(2_500_000);
        java.util.List<String> expectedRoots = new java.util.ArrayList<>(count);
        source.append("<script>outside-before\n");
        for (int index = 0; index < count; index++) {
            String root = "{\"name\":\"talos.read_file\","
                    + "\"arguments\":{\"path\":\"" + index + "\"}}";
            source.append(root).append('\n');
            expectedRoots.add(root);
        }
        source.append("outside-after</script>");
        String expectedOutput = String.join("", expectedRoots);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(4),
                () -> {
                    String output = Sanitize.sanitizeForOutputPreservingToolCalls(
                            source.toString());
                    assertEquals(expectedOutput, output);
                    assertFalse(output.contains("outside-before"), output);
                    assertFalse(output.contains("outside-after"), output);
                    assertFalse(output.contains("<script>"), output);
                    assertFalse(output.contains("</script>"), output);
                    var selected = dev.talos.core.tool.ToolProtocolText
                            .selectExecutableJsonProposals(output)
                            .sourceOrder();
                    assertEquals(count, selected.size());
                    for (int index = 0; index < count; index++) {
                        assertEquals(
                                expectedRoots.get(index),
                                selected.get(index).rootFrom(output));
                    }
                });
    }

    @Test
    void failClosedXmlCutCannotReturnAnEarlierManufacturedScriptPrefix() {
        String xml = "<tool\u001b[31m_call>"
                + "{\"name\":\"talos.write_file\",\"parameters\":{"
                + "\"path\":\"x.txt\",\"content\":\"safe\"}}"
                + "</tool_call>";
        String input = "<scr<think>x</think>ipt>bad()</script>\n" + xml;

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertFalse(output.contains("bad()"), output);
        assertFalse(output.toLowerCase(java.util.Locale.ROOT)
                .contains("<script"), output);
        assertFalse(dev.talos.core.tool.ToolProtocolText
                .containsRecognizedXmlToolCall(output), output);
    }

    @Test
    void failClosedRootOverlapCannotReturnAnEarlierManufacturedThinkPrefix() {
        String root = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String rejectedRead =
                "```json\n{\"name\":\"talos.read_file\",}\n```";
        String rejectedList =
                "```json\n{\"name\":\"talos.list_dir\",}\n```";
        String input = "<thi" + rejectedRead
                + "nk>secret</thi" + rejectedList
                + "nk>\n<thi\u001b[31mnk>before\n"
                + root + "\nafter</think>";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertFalse(output.contains("secret"), output);
        assertFalse(output.toLowerCase(java.util.Locale.ROOT)
                .contains("<think"), output);
        assertTrue(ToolCallParser.parse(output).isEmpty(), output);
    }

    @Test
    void completeRecognizedBareJsonRemainsByteIdentical() {
        String input = """
                {"name":"talos.write_file","parameters":{"path":"scripts.js","content":"result.textContent = 'Ready';"}}
                """.stripTrailing();

        assertExactExecutableFrame(input, "talos.write_file", "result.textContent = 'Ready';");
    }

    @Test
    void strippedControlBoundaryNeverInheritsStandaloneRootProtection() {
        String root = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        for (String control : List.of("\u000B", "\f")) {
            String output = Sanitize.sanitizeForOutputPreservingToolCalls(
                    control + root);

            assertEquals(root, output);
            assertEquals(sha256(root), sha256(output));
            assertEquals(1, ToolCallParser.parse(output).size(), output);
            assertFalse(output.contains(control), output);
        }
    }

    @TestFactory
    Stream<DynamicTest> recognizedNameKeysRemainByteIdentical() {
        return Stream.of("name", "function", "function_name", "tool_name", "tool")
                .map(nameKey -> DynamicTest.dynamicTest(nameKey, () -> {
                    String input = fenced("""
                            {"%s":"talos.write_file","parameters":{"path":"scripts.js","content":"result.textContent = 'Ready';"}}
                            """.formatted(nameKey));
                    assertExactExecutableFrame(
                            input,
                            "talos.write_file",
                            "result.textContent = 'Ready';");
                }));
    }

    @TestFactory
    Stream<DynamicTest> everyConfiguredToolNameRemainsByteIdentical() {
        return ToolNamePolicy.acceptedToolNames().stream().sorted()
                .map(toolName -> DynamicTest.dynamicTest(toolName, () -> {
                    String input = fenced("""
                            {"name":"%s","parameters":{"path":"scripts.js","content":"result.textContent = 'Ready';"}}
                            """.formatted(toolName));
                    assertExactExecutableFrame(
                            input,
                            toolName,
                            "result.textContent = 'Ready';");
                }));
    }

    @TestFactory
    Stream<DynamicTest> everyConfiguredToolNameRemainsByteIdenticalAsBareJson() {
        return ToolNamePolicy.acceptedToolNames().stream().sorted()
                .map(toolName -> DynamicTest.dynamicTest(toolName, () -> {
                    String input = """
                            {"name":"%s","parameters":{"path":"scripts.js","content":"result.textContent = 'Ready';"}}
                            """.formatted(toolName).stripTrailing();
                    assertExactExecutableFrame(
                            input,
                            toolName,
                            "result.textContent = 'Ready';");
                }));
    }

    @TestFactory
    Stream<DynamicTest> recognizedParameterKeysRemainByteIdentical() {
        return Stream.of("parameters", "arguments", "args", "params")
                .map(parameterKey -> DynamicTest.dynamicTest(parameterKey, () -> {
                    String input = fenced("""
                            {"name":"talos.write_file","%s":{"path":"scripts.js","content":"result.textContent = 'Ready';"}}
                            """.formatted(parameterKey));
                    assertExactExecutableFrame(
                            input,
                            "talos.write_file",
                            "result.textContent = 'Ready';");
                }));
    }

    @TestFactory
    Stream<DynamicTest> recognizedWrappersRemainByteIdentical() {
        return Stream.of("tool_call", "function_call")
                .map(wrapper -> DynamicTest.dynamicTest(wrapper, () -> {
                    String input = fenced("""
                            {"%s":{"name":"talos.write_file","parameters":{"path":"scripts.js","content":"result.textContent = 'Ready';"}}}
                            """.formatted(wrapper));
                    assertExactExecutableFrame(
                            input,
                            "talos.write_file",
                            "result.textContent = 'Ready';");
                }));
    }

    @Test
    void literalThinkElementInsideRecognizedArgumentRemainsByteIdentical() {
        String content = "before <think>literal file text</think> after";
        String input = fenced("""
                {"name":"talos.write_file","parameters":{"path":"notes.txt","content":"before <think>literal file text</think> after"}}
                """);

        assertEquals(LITERAL_THINK_FRAME_SHA256, sha256(input), "fixture identity changed");
        assertExactExecutableFrame(input, "talos.write_file", content);
    }

    @Test
    void escapedThinkElementInsideRecognizedArgumentRemainsByteIdentical() {
        String escapedOpen = "\\" + "u003cthink" + "\\" + "u003e";
        String escapedClose = "\\" + "u003c/think" + "\\" + "u003e";
        String input = fenced(
                "{\"name\":\"talos.write_file\",\"parameters\":{"
                        + "\"path\":\"notes.txt\",\"content\":\"before "
                        + escapedOpen
                        + "literal file text"
                        + escapedClose
                        + " after\"}}");

        assertExactExecutableFrame(
                input,
                "talos.write_file",
                "before <think>literal file text</think> after");
    }

    @Test
    void externalThinkBlockSuppressesEnclosedJsonProposal() {
        String enclosed = fenced("""
                {"name":"talos.write_file","parameters":{"path":"notes.txt","content":"must not execute"}}
                """);
        String input = "<think>\n" + enclosed + "\n</think>";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertTrue(ToolCallParser.parse(output).isEmpty(), output);
        assertFalse(output.contains("talos.write_file"), output);
        assertFalse(output.contains("must not execute"), output);
    }

    @Test
    void escapedExternalThinkBlockSuppressesEnclosedJsonProposal() {
        String escapedOpen = "\\" + "u003cthink" + "\\" + "u003e";
        String escapedClose = "\\" + "u003c/think" + "\\" + "u003e";
        String enclosed = fenced("""
                {"name":"talos.write_file","parameters":{"path":"notes.txt","content":"must not execute"}}
                """);
        String input = escapedOpen + "\n" + enclosed + "\n" + escapedClose;

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertTrue(ToolCallParser.parse(output).isEmpty(), output);
        assertFalse(output.contains("talos.write_file"), output);
        assertFalse(output.contains("must not execute"), output);
    }

    @Test
    void hiddenDuplicateCannotConsumeVisibleProposalSelection() {
        String root = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String frame = fenced(root);
        for (String input : List.of(
                "<think>\n" + frame + "\n</think>\n" + frame,
                "\\u003cthink\\u003e\n" + frame
                        + "\n\\u003c/think\\u003e\n" + frame)) {
            String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

            assertEquals(1, ToolCallParser.parse(output).size(), output);
            assertEquals("talos.read_file",
                    ToolCallParser.parse(output).getFirst().toolName());
            assertEquals(1,
                    dev.talos.core.tool.ToolProtocolText
                            .recognizedExecutableJsonSpans(output).size(),
                    output);
            assertTrue(output.contains(frame), output);
            assertFalse(output.contains("<think>"), output);
            assertFalse(output.contains("\\u003cthink\\u003e"), output);
        }
    }

    @Test
    void unclosedExternalThinkSuppressesItsEntireProposalTail() {
        String frame = fenced("""
                {"name":"talos.read_file","arguments":{"path":"README.md"}}
                """);
        for (String input : List.of(
                "safe-before\n<think>\n" + frame,
                "safe-before\n\\u003cthink\\u003e\n" + frame)) {
            String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

            assertEquals("safe-before\n", output);
            assertTrue(ToolCallParser.parse(output).isEmpty(), output);
            assertFalse(output.contains("talos.read_file"), output);
        }
    }

    @Test
    void nestedExternalThinkCannotReleaseHiddenSelectionEarly() {
        String hidden = fenced("""
                {"name":"talos.read_file","arguments":{"path":"hidden.txt"}}
                """);
        String visible = fenced("""
                {"name":"talos.read_file","arguments":{"path":"visible.txt"}}
                """);
        for (String input : List.of(
                "<think><think>noise</think>\n" + hidden
                        + "\n</think>\n" + visible,
                "\\u003cthink\\u003e\\u003cthink\\u003enoise"
                        + "\\u003c/think\\u003e\n" + hidden
                        + "\n\\u003c/think\\u003e\n" + visible)) {
            String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);
            List<ToolCall> calls = ToolCallParser.parse(output);

            assertEquals(1, calls.size(), output);
            assertEquals("visible.txt", calls.getFirst().param("path"));
            assertFalse(output.contains("hidden.txt"), output);
            assertTrue(output.contains(visible), output);
        }
    }

    @Test
    void mixedLiteralAndEscapedThinkNestingUsesOneExternalDepth() {
        String hidden = fenced("""
                {"name":"talos.write_file","arguments":{"path":"hidden.txt","content":"no"}}
                """);
        String visible = fenced("""
                {"name":"talos.read_file","arguments":{"path":"visible.txt"}}
                """);
        for (String input : List.of(
                "<think>\\u003cthink\\u003enoise</think>\n"
                        + hidden + "\n\\u003c/think\\u003e\n" + visible,
                "\\u003cthink\\u003e<think>noise\\u003c/think\\u003e\n"
                        + hidden + "\n</think>\n" + visible)) {
            String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);
            List<ToolCall> calls = ToolCallParser.parse(output);

            assertEquals(1, calls.size(), output);
            assertEquals("visible.txt", calls.getFirst().param("path"));
            assertFalse(output.contains("hidden.txt"), output);
        }
    }

    @Test
    void hiddenFenceIsExcludedBeforeVisibleBareSelectionPriority() {
        String hidden = fenced("""
                {"name":"talos.write_file","arguments":{"path":"hidden.txt","content":"no"}}
                """);
        String visible = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"visible.txt\"}}";
        String input = "<think>\n" + hidden + "\n</think>\n" + visible;

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);
        List<ToolCall> calls = ToolCallParser.parse(output);

        assertEquals(1, calls.size(), output);
        assertEquals("visible.txt", calls.getFirst().param("path"));
        assertFalse(output.contains("hidden.txt"), output);
        assertTrue(output.contains(visible), output);
    }

    @Test
    void thoughtContainingTheOnlyProposalYieldsNoExecutableSelection() {
        String hidden = fenced("""
                {"name":"talos.read_file","arguments":{"path":"hidden.txt"}}
                """);

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(
                "<think>\n" + hidden + "\n</think>");

        assertTrue(output.isEmpty(), output);
        assertTrue(ToolCallParser.parse(output).isEmpty(), output);
    }

    @Test
    void adjacentStandaloneToolJsonObjectsRemainByteIdentical() {
        String input = """
                {"name":"write_file","args":{"path":"first.js","content":"result.textContent = 'Ready';"}}
                {"tool_name":"file_write","params":{"path":"second.txt","content":"before <think>literal</think> after"}}
                """.stripTrailing();
        List<ToolCall> precondition = ToolCallParser.parse(input);
        assertEquals(2, precondition.size(), "fixture must contain two executable proposals");

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals(input, output, "adjacent executable frames changed");
        assertEquals(sha256(input), sha256(output), "adjacent frame hash changed");
        List<ToolCall> parsed = ToolCallParser.parse(output);
        assertEquals(2, parsed.size(), output);
        assertEquals("write_file", parsed.get(0).toolName());
        assertEquals("result.textContent = 'Ready';", parsed.get(0).param("content"));
        assertEquals("file_write", parsed.get(1).toolName());
        assertEquals("before <think>literal</think> after", parsed.get(1).param("content"));
    }

    @Test
    void outerDisplayOnlyPotentialCannotEraseNestedExecutableProposal() {
        String input = "{\n"
                + "  \"example\":\n"
                + "  {\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}\n"
                + "}";
        List<ToolCall> precondition = ToolCallParser.parse(input);
        assertEquals(1, precondition.size(), "nested proposal must be executable");

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals(input, output, "outer display-only range erased nested proposal");
        List<ToolCall> parsed = ToolCallParser.parse(output);
        assertEquals(1, parsed.size(), output);
        assertEquals("talos.read_file", parsed.getFirst().toolName());
        assertEquals("README.md", parsed.getFirst().param("path"));
    }

    @Test
    void thinkFragmentsCannotPairAcrossAdjacentExecutableProposals() {
        String first = "{\"name\":\"talos.write_file\",\"arguments\":{"
                + "\"path\":\"first.txt\",\"content\":\"literal <think>open\"}}";
        String second = "{\"name\":\"talos.write_file\",\"arguments\":{"
                + "\"path\":\"second.txt\",\"content\":\"close</think> literal\"}}";
        String input = first + "\n" + second;
        assertEquals(2, ToolCallParser.parse(input).size(),
                "fixture must contain two executable proposals");

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals(input, output, "think fragments crossed executable boundaries");
        List<ToolCall> parsed = ToolCallParser.parse(output);
        assertEquals(2, parsed.size(), output);
        assertEquals("literal <think>open", parsed.get(0).param("content"));
        assertEquals("close</think> literal", parsed.get(1).param("content"));
    }

    @Test
    void escapedThinkFragmentsCannotPairAcrossAdjacentExecutableProposals() {
        String escapedOpen = "\\" + "u003cthink" + "\\" + "u003e";
        String escapedClose = "\\" + "u003c/think" + "\\" + "u003e";
        String first = "{\"name\":\"talos.write_file\",\"arguments\":{"
                + "\"path\":\"first.txt\",\"content\":\"literal "
                + escapedOpen
                + "open\"}}";
        String second = "{\"name\":\"talos.write_file\",\"arguments\":{"
                + "\"path\":\"second.txt\",\"content\":\"close"
                + escapedClose
                + " literal\"}}";
        String input = first + "\n" + second;
        assertEquals(2, ToolCallParser.parse(input).size(),
                "fixture must contain two executable proposals");

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals(input, output, "escaped think fragments crossed executable boundaries");
        List<ToolCall> parsed = ToolCallParser.parse(output);
        assertEquals(2, parsed.size(), output);
        assertEquals("literal <think>open", parsed.get(0).param("content"));
        assertEquals("close</think> literal", parsed.get(1).param("content"));
    }

    @Test
    void unsafeGapsAreSanitizedWithoutChangingRecognizedFrame() {
        String frame = fenced("""
                {"name":"talos.write_file","parameters":{"path":"index.html","content":"<script>inside()</script>"}}
                """);
        String input = "before <script>outsideBefore()</script> onload=\"before()\"\n"
                + frame
                + "\nafter <script>outsideAfter()</script> onclick=\"after()\"";
        String expected = "before  \n" + frame + "\nafter  ";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals(expected, output);
        assertEquals(sha256(expected), sha256(output));
        assertTrue(output.contains("<script>inside()</script>"), output);
        assertFalse(output.contains("outsideBefore"), output);
        assertFalse(output.contains("outsideAfter"), output);
        assertFalse(output.contains("onload="), output);
        assertFalse(output.contains("onclick="), output);
    }

    @Test
    void externalScriptWrapperCannotCrossASelectedFence() {
        String frame = fenced("""
                {"name":"talos.write_file","arguments":{"path":"x.js","content":"<script>inside()</script>"}}
                """);
        String input = "safe-before\n<script>outside-before\n"
                + frame
                + "\noutside-after</script>\nsafe-after";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertTrue(output.contains("safe-before"), output);
        assertTrue(output.contains("safe-after"), output);
        assertFalse(output.contains("outside-before"), output);
        assertFalse(output.contains("outside-after"), output);
        assertTrue(output.contains(frame), output);
        assertEquals(1, ToolCallParser.parse(output).size(), output);
        assertEquals("<script>inside()</script>",
                ToolCallParser.parse(output).getFirst().param("content"));
    }

    @Test
    void unmatchedDifferentTagCannotMaskCrossingStyleWrapper() {
        String frame = fenced("""
                {"name":"talos.read_file","arguments":{"path":"README.md"}}
                """);
        String input = "<script><style>outside-before\n"
                + frame
                + "\noutside-after</style>";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertFalse(output.contains("<style>"), output);
        assertFalse(output.contains("outside-before"), output);
        assertFalse(output.contains("outside-after"), output);
        assertTrue(output.contains(frame), output);
        assertEquals(1, ToolCallParser.parse(output).size(), output);
    }

    @TestFactory
    Stream<DynamicTest> everyPairedSuspiciousTagCannotCrossSelectedRoots() {
        return Stream.of(
                        "script", "style", "iframe", "object", "embed", "meta",
                        "link", "svg", "form", "input", "textarea", "button")
                .map(tag -> DynamicTest.dynamicTest(tag, () -> {
                    String first = fenced("""
                            {"name":"talos.read_file","arguments":{"path":"one.txt"}}
                            """);
                    String second = fenced("""
                            {"name":"talos.read_file","arguments":{"path":"two.txt"}}
                            """);
                    String input = "<" + tag + ">outside-before\n"
                            + first
                            + "\noutside-between\n"
                            + second
                            + "\noutside-after</" + tag + ">";

                    String output = Sanitize
                            .sanitizeForOutputPreservingToolCalls(input);

                    assertFalse(output.contains("outside-before"), output);
                    assertFalse(output.contains("outside-between"), output);
                    assertFalse(output.contains("outside-after"), output);
                    assertTrue(output.contains(first), output);
                    assertTrue(output.contains(second), output);
                    assertEquals(2, ToolCallParser.parse(output).size(), output);
                }));
    }

    @Test
    void adversarialCrossRegionOpenersRemainBounded() {
        String frame = fenced("""
                {"name":"talos.read_file","arguments":{"path":"README.md"}}
                """);
        for (String prefix : List.of(
                "<script ".repeat(4_096),
                "on".repeat(16_384))) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(2),
                    () -> {
                        String output = Sanitize
                                .sanitizeForOutputPreservingToolCalls(
                                        prefix + "\n" + frame);
                        assertTrue(output.contains(frame));
                        assertEquals(1, ToolCallParser.parse(output).size());
                    });
        }
    }

    @Test
    void externalScriptWrapperCannotEraseStandaloneSelectionBoundary() {
        String root = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String input = "safe-before\n<script>outside-before\n"
                + root
                + "\noutside-after</script>\nsafe-after";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertTrue(output.contains("safe-before"), output);
        assertTrue(output.contains("safe-after"), output);
        assertFalse(output.contains("outside-before"), output);
        assertFalse(output.contains("outside-after"), output);
        assertTrue(output.contains(root), output);
        assertEquals(1, ToolCallParser.parse(output).size(), output);
        assertEquals("README.md",
                ToolCallParser.parse(output).getFirst().param("path"));
    }

    @Test
    void externalEventAttributeCannotCrossASelectedFence() {
        String frame = fenced("""
                {"name":"talos.read_file","arguments":{"path":"README.md"}}
                """);
        String input = "safe-before onclick=\"outside-before "
                + frame
                + " outside-after\" safe-after";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertTrue(output.contains("safe-before"), output);
        assertTrue(output.contains("safe-after"), output);
        assertFalse(output.contains("onclick="), output);
        assertFalse(output.contains("outside-before"), output);
        assertFalse(output.contains("outside-after"), output);
        assertTrue(output.contains(frame), output);
        assertEquals(1, ToolCallParser.parse(output).size(), output);
    }

    @Test
    void delimiterLikeUnsafeTailNeverInheritsSelectedRootProtection() {
        String root = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String input = "```json\n" + root
                + "\n\"literal ``` marker\" <script>outside()</script>"
                + "<think>outside</think>\n```";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertFalse(output.contains("outside()"), output);
        assertFalse(output.contains("<think>"), output);
        assertEquals(1, ToolCallParser.parse(output).size(), output);
        assertEquals(root,
                dev.talos.core.tool.ToolProtocolText
                        .recognizedExecutableJsonSpans(output)
                        .getFirst()
                        .rootFrom(output));
    }

    @Test
    void failClosedProjectionNeverLeavesAnOpeningFencePrefix() {
        String root = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String input = "safe\n```json\n" + root + "<think>```</think>";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals("", output);
        assertFalse(output.contains("```"), output);
        assertFalse(output.contains("talos.read_file"), output);
        assertTrue(ToolCallParser.parse(output).isEmpty(), output);
    }

    @Test
    void unsafeGapsBeforeBetweenAndAfterTwoProposalsAreSanitizedIndependently() {
        String first = fenced("""
                {"name":"talos.write_file","parameters":{"path":"first.html","content":"<script>insideFirst()</script>"}}
                """);
        String second = fenced("""
                {"name":"talos.write_file","parameters":{"path":"second.html","content":"<script>insideSecond()</script>"}}
                """);
        String input = "before <script>outsideBefore()</script> onload=\"before()\"\n"
                + first
                + "\nbetween <script>outsideBetween()</script> onfocus=\"between()\"\n"
                + second
                + "\nafter <script>outsideAfter()</script> onclick=\"after()\"";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertTrue(output.contains(first), output);
        assertTrue(output.contains(second), output);
        assertEquals(2, ToolCallParser.parse(output).size(), output);
        assertTrue(output.contains("insideFirst()"), output);
        assertTrue(output.contains("insideSecond()"), output);
        assertFalse(output.contains("outsideBefore"), output);
        assertFalse(output.contains("outsideBetween"), output);
        assertFalse(output.contains("outsideAfter"), output);
        assertFalse(output.contains("onload="), output);
        assertFalse(output.contains("onfocus="), output);
        assertFalse(output.contains("onclick="), output);
    }

    @Test
    void ordinaryAndForeignFencesDoNotGainProtectedOrExecutableStatus() {
        List<String> inputs = List.of(
                fenced("""
                        {"name":"ordinary","content":"<script>ordinaryUnsafe()</script>"}
                        """),
                fenced("""
                        {"name":"other.write_file","parameters":{"content":"<script>foreignUnsafe()</script>"}}
                        """));

        for (String input : inputs) {
            String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);
            assertTrue(ToolCallParser.parse(output).isEmpty(), output);
            assertFalse(output.contains("<script>"), output);
            assertFalse(output.contains("Unsafe()"), output);
        }
    }

    @Test
    void malformedCandidateDoesNotGainProtectedStatus() {
        assertRejectedCandidateIsSanitized(fenced("""
                {"name":"talos.write_file","parameters":{"path":"x.html","content":"<script>malformedUnsafe()</script>",}}
                """));
    }

    @Test
    void sanitationCannotManufactureExecutableNameKeyOrStructure() {
        String validTail =
                "\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}";
        for (String removable : List.of(
                "<think>hidden</think>",
                "<script>hidden</script>")) {
            for (String input : List.of(
                    "```json\n{\"na" + removable + "me" + validTail + "\n```",
                    "{\"na" + removable + "me" + validTail,
                    "{\"name\":\"talos.read_file\",\"arguments\":"
                            + "{\"path\":\"README.md\"}"
                            + removable
                            + "}")) {
                assertTrue(ToolCallParser.parse(input).isEmpty(), input);

                String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

                assertTrue(
                        ToolCallParser.parse(output).isEmpty(),
                        () -> "sanitation manufactured executable JSON: " + output);
            }
        }
    }

    @Test
    void deletedThinkBlockCannotManufactureSuspiciousMarkupOrAttributeText() {
        for (String input : List.of(
                "<scr<think>x</think>ipt>bad()</script>",
                "oncl<think>x</think>ick=\"bad()\"")) {
            String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

            assertFalse(output.contains("bad()"), output);
            assertFalse(output.toLowerCase(java.util.Locale.ROOT)
                    .contains("<script"), output);
            assertFalse(output.toLowerCase(java.util.Locale.ROOT)
                    .contains("onclick="), output);
        }
    }

    @Test
    void normalizedExternalFragmentsCannotManufactureAWrapperAcrossSelectedRoot() {
        String root = "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String input = "<scr\u001b[31mipt>outside-before\n"
                + root
                + "\noutside-after</script>";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertFalse(output.contains("outside-before"), output);
        assertFalse(output.contains("outside-after"), output);
        assertFalse(output.toLowerCase(java.util.Locale.ROOT)
                .contains("<script"), output);
        assertEquals(1, ToolCallParser.parse(output).size(), output);
        assertEquals(root,
                dev.talos.core.tool.ToolProtocolText
                        .recognizedExecutableJsonSpans(output)
                        .getFirst()
                        .rootFrom(output));
    }

    @Test
    void sanitationCannotManufactureASecondCandidateHiddenByDeduplication() {
        String root = "{\"name\":\"read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";
        String input = root + "\n"
                + "{\"name\":\"read_<think>x</think>file\","
                + "\"arguments\":{\"path\":\"README.md\"}}";

        assertEquals(1,
                dev.talos.core.tool.ToolProtocolText
                        .selectExecutableJsonProposals(input)
                        .recognizedCandidates()
                        .size(),
                "fixture must begin with exactly one recognized candidate");

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);
        dev.talos.core.tool.ToolProtocolText.JsonProposalSelection projected =
                dev.talos.core.tool.ToolProtocolText
                        .selectExecutableJsonProposals(output);

        assertTrue(projected.recognizedCandidates().size() <= 1, output);
        assertTrue(projected.executionOrder().size() <= 1, output);
        if (!projected.executionOrder().isEmpty()) {
            assertEquals(root,
                    projected.executionOrder().getFirst().rootFrom(output));
        }
        assertFalse(output.contains("read_<think>"), output);
        assertEquals(-1, output.indexOf("\"read_file\"", root.length()), output);
    }

    @Test
    void removablePrefixCannotMoveStandaloneJsonIntoExecutablePosition() {
        for (String removable : List.of(
                "<think>hidden</think>",
                "<script>hidden</script>")) {
            for (String toolName : List.of("write_file", "talos.write_file")) {
                String input = removable
                        + "{\"name\":\""
                        + toolName
                        + "\",\"arguments\":{\"path\":\"x\",\"content\":\"y\"}}";
                assertTrue(ToolCallParser.parse(input).isEmpty(), input);

                String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

                assertTrue(
                        ToolCallParser.parse(output).isEmpty(),
                        () -> "sanitation moved JSON into executable position: " + output);
            }
        }
    }

    @Test
    void externalThinkCrossingRecognizedBoundaryDropsTheWholeProposal() {
        String input = "<think>hidden "
                + "{\"name\":\"talos.read_file\","
                + "\"arguments\":{\"path\":\"README.md\"}}"
                + "</think>";

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertTrue(ToolCallParser.parse(output).isEmpty(), output);
        assertFalse(output.contains("talos.read_file"), output);
    }

    @Test
    void unclosedCandidateDoesNotGainProtectedStatus() {
        assertRejectedCandidateIsSanitized(
                "```json\n"
                        + "{\"name\":\"talos.write_file\",\"parameters\":"
                        + "{\"path\":\"x.html\",\"content\":\"<script>unclosedUnsafe()</script>\"");
    }

    private static void assertRejectedCandidateIsSanitized(String input) {
        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);
        assertTrue(ToolCallParser.parse(output).isEmpty(), output);
        assertFalse(output.contains("<script>"), output);
        assertFalse(output.contains("Unsafe()"), output);
    }

    private static void assertExactExecutableFrame(
            String input,
            String expectedToolName,
            String expectedContent) {
        List<ToolCall> precondition = ToolCallParser.parse(input);
        assertEquals(1, precondition.size(), "fixture must be executable before sanitization");
        assertEquals(expectedToolName, precondition.getFirst().toolName());
        assertEquals(expectedContent, precondition.getFirst().param("content"));

        String output = Sanitize.sanitizeForOutputPreservingToolCalls(input);

        assertEquals(input, output, "recognized executable frame changed");
        assertEquals(sha256(input), sha256(output), "recognized executable frame hash changed");

        List<ToolCall> parsed = ToolCallParser.parse(output);
        assertEquals(1, parsed.size(), output);
        assertEquals(expectedToolName, parsed.getFirst().toolName());
        assertEquals(expectedContent, parsed.getFirst().param("content"));
    }

    private static String fenced(String json) {
        return "```json\n" + json.strip() + "\n```";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }
}
