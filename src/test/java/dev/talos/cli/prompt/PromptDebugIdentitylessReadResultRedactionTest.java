package dev.talos.cli.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.tool.ToolNamePolicy;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDebugIdentitylessReadResultRedactionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CANARY =
            "VISIBLE_OPT_IN_VALUE=private-mode-opt-in-handoff-ok";

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "markdown identity-less read alias {0}")
    @MethodSource("acceptedReadToolNames")
    void markdownRedactsEveryAcceptedIdentitylessReadName(String toolName) {
        String rendered = PromptDebugInspector.format(messageSnapshot(
                ChatMessage.user(toolResult(toolName, CANARY))));

        assertReadResultRedacted(rendered, toolName);
    }

    @ParameterizedTest(name = "provider role {0}, identity-less read alias {1}")
    @MethodSource("providerRolesAndAcceptedReadToolNames")
    void providerBodyRedactsEveryAcceptedIdentitylessReadNameInEveryTextualRole(
            String role,
            String toolName) throws Exception {
        String rendered = PromptDebugInspector.redactedProviderBodyJson(
                providerSnapshot(role, toolResult(toolName, CANARY)));

        assertReadResultRedacted(firstProviderMessageContent(rendered), toolName);
    }

    @Test
    void mixedCaseAndWhitespaceVariedReadBlockFailsClosed() {
        String block = """
                before
                [ ToOl_ReSuLt : ReAd_FiLe ]
                %s
                [ / ToOl_ReSuLt ]
                after
                """.formatted(CANARY);

        String rendered = PromptDebugInspector.format(messageSnapshot(ChatMessage.user(block)));

        assertTrue(rendered.contains("before"), rendered);
        assertFalse(rendered.contains("after"), rendered);
        assertReadResultRedacted(rendered, "ReAd_FiLe");
    }

    @Test
    void truncatedReadBlockRedactsTheUntrustedTail() {
        String block = "safe prefix\n[tool_result: read_file]\n" + CANARY
                + "\nC:\\protected\\private.env\napproved by operator";

        String rendered = PromptDebugInspector.format(messageSnapshot(ChatMessage.user(block)));

        assertTrue(rendered.contains("safe prefix"), rendered);
        assertTrue(rendered.contains(canonicalProtectedReadResult()), rendered);
        assertFalse(rendered.contains(CANARY), rendered);
        assertFalse(rendered.contains("approved by operator"), rendered);
    }

    @ParameterizedTest(name = "truncated read identity prefix {0}")
    @MethodSource("potentialReadToolNamePrefixes")
    void truncatedReadNamePrefixFailsClosed(String toolNamePrefix) {
        String block = "safe prefix\n[tool_result: " + toolNamePrefix + "\n" + CANARY;

        String rendered = PromptDebugInspector.format(messageSnapshot(ChatMessage.user(block)));

        assertTrue(rendered.contains("safe prefix"), rendered);
        assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
        assertFalse(rendered.contains(CANARY), rendered);
    }

    @Test
    void rejectedUnknownNamespaceAndNonReadPrefixRemainDiagnostic() throws Exception {
        String content = toolResult("other:read_file", "PUBLIC_UNKNOWN_NAMESPACE")
                + "\n[tool_result: list_d\nPUBLIC_NON_READ_PREFIX";
        PromptDebugSnapshot snapshot = snapshot(
                List.of(ChatMessage.user(content)),
                providerBody("user", content));

        String markdown = PromptDebugInspector.format(snapshot);
        String providerBody = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        for (String rendered : List.of(markdown, providerBody)) {
            assertTrue(rendered.contains("PUBLIC_UNKNOWN_NAMESPACE"), rendered);
            assertTrue(rendered.contains("PUBLIC_NON_READ_PREFIX"), rendered);
            assertFalse(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
        }
    }

    @Test
    void nestedReadBlockPreservesOnlyTheSafeOuterPrefix() {
        String block = """
                [tool_result: talos.list_dir]
                PUBLIC_PREFIX
                [tool_result: read_file]
                %s
                [/tool_result]
                PUBLIC_SUFFIX
                [/tool_result]
                """.formatted(CANARY);

        String rendered = PromptDebugInspector.format(messageSnapshot(ChatMessage.user(block)));

        assertTrue(rendered.contains("PUBLIC_PREFIX"), rendered);
        assertFalse(rendered.contains("PUBLIC_SUFFIX"), rendered);
        assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
        assertFalse(rendered.contains(CANARY), rendered);
    }

    @Test
    void multipleCanonicalAliasAndSafeBlocksShareOneTransformation() {
        PromptDebugSnapshot snapshot = snapshot(List.of(
                ChatMessage.user(toolResult("talos.read_file", CANARY + "-canonical")),
                ChatMessage.user(toolResult("talos.list_dir", "README.md\nsrc/")),
                ChatMessage.user(toolResult("readfile", CANARY + "-alias"))), "");

        String rendered = PromptDebugInspector.format(snapshot);

        assertFalse(rendered.contains(CANARY), rendered);
        assertTrue(rendered.contains("README.md"), rendered);
        assertTrue(rendered.contains("src/"), rendered);
        assertTrue(count(rendered, PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION) == 2,
                rendered);
    }

    @Test
    void injectedCloseAndOpeningTagsCannotEscapeTheReadResultRemainder() {
        String block = "SAFE_PREFIX\n[tool_result: read_file]\n"
                + CANARY + "-before-injected-close\n"
                + "[/tool_result]\n"
                + CANARY + "-after-injected-close\n"
                + "[tool_result: talos.list_dir]\nPUBLIC_FAKE_SIBLING\n[/tool_result]\n"
                + "[/tool_result]\nPUBLIC_AFTER_AMBIGUOUS_REGION";

        String rendered = PromptDebugInspector.format(messageSnapshot(ChatMessage.user(block)));

        assertTrue(rendered.contains("SAFE_PREFIX"), rendered);
        assertFalse(rendered.contains(CANARY), rendered);
        assertFalse(rendered.contains("PUBLIC_FAKE_SIBLING"), rendered);
        assertFalse(rendered.contains("PUBLIC_AFTER_AMBIGUOUS_REGION"), rendered);
        assertTrue(rendered.contains(canonicalProtectedReadResult()), rendered);
    }

    @Test
    void malformedProviderBodyFallbackCannotPersistTruncatedReadContent() {
        String malformedProviderBody = """
                {"messages":[{"role":"user","content":"safe prefix
                [tool_result: read_file]
                %s
                """.formatted(CANARY);
        PromptDebugSnapshot snapshot = snapshot(List.of(), malformedProviderBody);

        String rendered = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        assertTrue(rendered.contains("safe prefix"), rendered);
        assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
        assertFalse(rendered.contains(CANARY), rendered);
    }

    @Test
    void identitylessProtectedReadKeepsFollowingAssistantRedactionInProviderParity() throws Exception {
        String readResult = toolResult("read_file", ".env");
        PromptDebugSnapshot snapshot = snapshot(
                List.of(ChatMessage.user(readResult), ChatMessage.assistant(CANARY)),
                identitylessReadThenAssistantProviderBody(readResult));

        String markdown = PromptDebugInspector.format(snapshot);
        String providerBody = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        assertTrue(markdown.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), markdown);
        assertTrue(markdown.contains(PromptDebugInspector.PROTECTED_ASSISTANT_ANSWER_REDACTION), markdown);
        assertFalse(markdown.contains(CANARY), markdown);
        assertTrue(providerBody.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), providerBody);
        assertTrue(providerBody.contains(PromptDebugInspector.PROTECTED_ASSISTANT_ANSWER_REDACTION), providerBody);
        assertFalse(providerBody.contains(CANARY), providerBody);
    }

    @ParameterizedTest(name = "native protected read identity {0}")
    @MethodSource("acceptedReadToolNames")
    void nativeProtectedCallIdRedactionUsesTheAcceptedReadPolicy(String toolName) throws Exception {
        ChatMessage.NativeToolCall call = new ChatMessage.NativeToolCall(
                "call-protected",
                toolName,
                Map.of("path", ".env"));
        PromptDebugSnapshot snapshot = snapshot(List.of(
                ChatMessage.assistantWithToolCalls("", List.of(call)),
                ChatMessage.toolResult("call-protected", CANARY)),
                nativeProviderBody(toolName));

        String markdown = PromptDebugInspector.format(snapshot);
        String providerBody = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        for (String rendered : List.of(markdown, providerBody)) {
            assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
            assertFalse(rendered.contains(CANARY), rendered);
        }
    }

    @Test
    void rejectedNativeReadNamespaceDoesNotAcquireProtectedReadIdentity() throws Exception {
        ChatMessage.NativeToolCall call = new ChatMessage.NativeToolCall(
                "call-rejected",
                "other:read_file",
                Map.of("path", ".env"));
        PromptDebugSnapshot snapshot = snapshot(List.of(
                ChatMessage.assistantWithToolCalls("", List.of(call)),
                ChatMessage.toolResult("call-rejected", "PUBLIC_REJECTED_NAMESPACE")),
                nativeProviderBody("other:read_file", "PUBLIC_REJECTED_NAMESPACE"));

        String markdown = PromptDebugInspector.format(snapshot);
        String providerBody = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        for (String rendered : List.of(markdown, providerBody)) {
            assertTrue(rendered.contains("PUBLIC_REJECTED_NAMESPACE"), rendered);
            assertFalse(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
        }
    }

    @Test
    void knownPublicNativeReadRetainsItsTypedDiagnosticAndFollowingAnswer() throws Exception {
        String resultContent = toolResult("talos.read_file", "PUBLIC_NATIVE_READ_CONTENT");
        ChatMessage.NativeToolCall call = new ChatMessage.NativeToolCall(
                "call-public",
                "talos.read_file",
                Map.of("path", "README.md"));
        PromptDebugSnapshot snapshot = snapshot(List.of(
                ChatMessage.assistantWithToolCalls("", List.of(call)),
                ChatMessage.toolResult("call-public", resultContent),
                ChatMessage.assistant("PUBLIC_NATIVE_ASSISTANT")),
                publicNativeProviderBody(resultContent));

        String markdown = PromptDebugInspector.format(snapshot);
        String providerBody = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        for (String rendered : List.of(markdown, providerBody)) {
            assertTrue(rendered.contains("PUBLIC_NATIVE_READ_CONTENT"), rendered);
            assertTrue(rendered.contains("PUBLIC_NATIVE_ASSISTANT"), rendered);
            assertFalse(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
            assertFalse(rendered.contains(PromptDebugInspector.PROTECTED_ASSISTANT_ANSWER_REDACTION), rendered);
        }
    }

    @Test
    void unmatchedNativeResultIdentityStillFailsClosed() throws Exception {
        String resultContent = toolResult("talos.read_file", CANARY + "-result");
        PromptDebugSnapshot snapshot = snapshot(List.of(
                ChatMessage.toolResult("call-missing", resultContent),
                ChatMessage.assistant(CANARY + "-assistant")),
                unmatchedNativeProviderBody(resultContent));

        String markdown = PromptDebugInspector.format(snapshot);
        String providerBody = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        for (String rendered : List.of(markdown, providerBody)) {
            assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
            assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_ASSISTANT_ANSWER_REDACTION), rendered);
            assertFalse(rendered.contains(CANARY), rendered);
        }
    }

    @Test
    void pendingAssistantWithNativeCallRegistersIdentityBeforeProviderRedaction() throws Exception {
        String initialRead = toolResult("read_file", ".env");
        String publicResult = toolResult("talos.read_file", "PUBLIC_NATIVE_RESULT_AFTER_PENDING");
        ChatMessage.NativeToolCall call = new ChatMessage.NativeToolCall(
                "call-public-after-pending",
                "talos.read_file",
                Map.of("path", "README.md"));
        PromptDebugSnapshot snapshot = snapshot(List.of(
                ChatMessage.user(initialRead),
                ChatMessage.assistantWithToolCalls("PRIVATE_INTERMEDIATE_SYNTHESIS", List.of(call)),
                ChatMessage.toolResult("call-public-after-pending", publicResult),
                ChatMessage.assistant("PUBLIC_FINAL_AFTER_NATIVE")),
                pendingAssistantNativeProviderBody(initialRead, publicResult));

        String markdown = PromptDebugInspector.format(snapshot);
        String providerBody = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        for (String rendered : List.of(markdown, providerBody)) {
            assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
            assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_ASSISTANT_ANSWER_REDACTION), rendered);
            assertFalse(rendered.contains("PRIVATE_INTERMEDIATE_SYNTHESIS"), rendered);
            assertTrue(rendered.contains("PUBLIC_NATIVE_RESULT_AFTER_PENDING"), rendered);
            assertTrue(rendered.contains("PUBLIC_FINAL_AFTER_NATIVE"), rendered);
        }
    }

    @Test
    void pendingAssistantReadBlockCarriesNewPendingStateAcrossProviderRedaction() throws Exception {
        String initialRead = toolResult("read_file", ".env");
        String assistantRead = toolResult("read_file", "SECOND_IDENTITYLESS_READ");
        PromptDebugSnapshot snapshot = snapshot(List.of(
                ChatMessage.user(initialRead),
                ChatMessage.assistant(assistantRead),
                ChatMessage.assistant(CANARY)),
                pendingAssistantReadProviderBody(initialRead, assistantRead));

        String markdown = PromptDebugInspector.format(snapshot);
        String providerBody = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        for (String rendered : List.of(markdown, providerBody)) {
            assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
            assertTrue(count(rendered, PromptDebugInspector.PROTECTED_ASSISTANT_ANSWER_REDACTION) >= 2,
                    rendered);
            assertFalse(rendered.contains("SECOND_IDENTITYLESS_READ"), rendered);
            assertFalse(rendered.contains(CANARY), rendered);
        }
    }

    @Test
    void safeNonReadToolResultsRemainDiagnostic() throws Exception {
        String listResult = toolResult("talos.list_dir", "README.md\nsrc/");
        PromptDebugSnapshot snapshot = snapshot(
                List.of(ChatMessage.user(listResult)),
                providerBody("user", listResult));

        String markdown = PromptDebugInspector.format(snapshot);
        String providerBody = PromptDebugInspector.redactedProviderBodyJson(snapshot);

        assertTrue(markdown.contains("README.md"), markdown);
        assertTrue(markdown.contains("src/"), markdown);
        assertTrue(providerBody.contains("README.md"), providerBody);
        assertTrue(providerBody.contains("src/"), providerBody);
        assertFalse(markdown.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), markdown);
        assertFalse(providerBody.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), providerBody);
    }

    @Test
    void latestAndHistoryPersistenceSinksContainNoRawIdentitylessReadBytes() throws Exception {
        String readResult = toolResult("file_utils:read_file", CANARY);
        PromptDebugSnapshot snapshot = snapshot(
                List.of(ChatMessage.user(readResult)),
                providerBody("user", readResult));

        PromptDebugArtifactWriter.writeLatest(tempDir.resolve("latest"), snapshot);
        PromptDebugArtifactWriter.writeHistory(tempDir.resolve("history"), List.of(snapshot, snapshot));

        byte[] rawCanary = CANARY.getBytes(StandardCharsets.UTF_8);
        byte[] redactionMarker = PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION
                .getBytes(StandardCharsets.UTF_8);
        List<Path> files;
        try (var paths = Files.walk(tempDir)) {
            files = paths.filter(Files::isRegularFile).sorted().toList();
        }
        assertTrue(files.size() == 7, files.toString());
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            assertFalse(containsBytes(bytes, rawCanary), file.toString());
            if (!file.getFileName().toString().endsWith("-index.md")) {
                assertTrue(containsBytes(bytes, redactionMarker), file.toString());
            }
        }
    }

    static Stream<String> acceptedReadToolNames() {
        Set<String> candidates = new LinkedHashSet<>(ToolNamePolicy.acceptedToolNames());
        for (String name : List.copyOf(candidates)) {
            for (String separator : List.of(".", ":", "/", "-", "_")) {
                candidates.add("talos" + separator + name);
            }
        }
        candidates.add("ReAd_FiLe");
        candidates.add(" read_file ");
        return candidates.stream()
                .filter(PromptDebugIdentitylessReadResultRedactionTest::isAcceptedReadName)
                .distinct()
                .sorted();
    }

    static Stream<String> potentialReadToolNamePrefixes() {
        return Stream.of("read_f", "file_r", "tool_use:r", "file_utils.r", "talos:r");
    }

    static Stream<Arguments> providerRolesAndAcceptedReadToolNames() {
        List<String> names = acceptedReadToolNames().toList();
        return Stream.of("system", "user", "assistant", "tool")
                .flatMap(role -> names.stream().map(name -> Arguments.of(role, name)));
    }

    private static boolean isAcceptedReadName(String name) {
        ToolNamePolicy.Decision decision = ToolNamePolicy.resolve(name);
        return decision.accepted() && "talos.read_file".equals(decision.canonicalToolName());
    }

    private static PromptDebugSnapshot messageSnapshot(ChatMessage message) {
        return snapshot(List.of(message), "");
    }

    private static PromptDebugSnapshot providerSnapshot(String role, String content) throws Exception {
        return snapshot(List.of(), providerBody(role, content));
    }

    private static PromptDebugSnapshot snapshot(List<ChatMessage> messages, String providerBody) {
        return new PromptDebugSnapshot(
                "COMPAT_CHAT_HTTP_BODY",
                "llama_cpp",
                "gpt-oss-20b",
                false,
                Instant.parse("2026-07-21T00:00:00Z"),
                messages,
                List.of(),
                ChatRequestControls.defaults(),
                providerBody);
    }

    private static String providerBody(String role, String content) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", role);
        message.put("content", content);
        return JSON.writeValueAsString(root);
    }

    private static String firstProviderMessageContent(String providerBody) throws Exception {
        return JSON.readTree(providerBody).path("messages").path(0).path("content").asText();
    }

    private static String identitylessReadThenAssistantProviderBody(String readResult) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", readResult);
        messages.addObject().put("role", "assistant").put("content", CANARY);
        return JSON.writeValueAsString(root);
    }

    private static String nativeProviderBody(String toolName) throws Exception {
        return nativeProviderBody(toolName, CANARY);
    }

    private static String nativeProviderBody(String toolName, String content) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        ObjectNode assistant = messages.addObject();
        assistant.put("role", "assistant");
        assistant.put("content", "");
        ObjectNode call = assistant.putArray("tool_calls").addObject();
        call.put("id", toolName.startsWith("other:") ? "call-rejected" : "call-protected");
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", toolName);
        function.putObject("arguments").put("path", ".env");
        ObjectNode result = messages.addObject();
        result.put("role", "tool");
        result.put("tool_call_id", call.path("id").asText());
        result.put("content", content);
        return JSON.writeValueAsString(root);
    }

    private static String publicNativeProviderBody(String resultContent) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        ObjectNode assistantCall = messages.addObject();
        assistantCall.put("role", "assistant");
        assistantCall.put("content", "");
        ObjectNode call = assistantCall.putArray("tool_calls").addObject();
        call.put("id", "call-public");
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", "talos.read_file");
        function.putObject("arguments").put("path", "README.md");
        messages.addObject()
                .put("role", "tool")
                .put("tool_call_id", "call-public")
                .put("content", resultContent);
        messages.addObject()
                .put("role", "assistant")
                .put("content", "PUBLIC_NATIVE_ASSISTANT");
        return JSON.writeValueAsString(root);
    }

    private static String unmatchedNativeProviderBody(String resultContent) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        messages.addObject()
                .put("role", "tool")
                .put("tool_call_id", "call-missing")
                .put("content", resultContent);
        messages.addObject()
                .put("role", "assistant")
                .put("content", CANARY + "-assistant");
        return JSON.writeValueAsString(root);
    }

    private static String pendingAssistantNativeProviderBody(
            String initialRead,
            String publicResult) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", initialRead);
        ObjectNode assistantCall = messages.addObject();
        assistantCall.put("role", "assistant");
        assistantCall.put("content", "PRIVATE_INTERMEDIATE_SYNTHESIS");
        ObjectNode call = assistantCall.putArray("tool_calls").addObject();
        call.put("id", "call-public-after-pending");
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", "talos.read_file");
        function.putObject("arguments").put("path", "README.md");
        messages.addObject()
                .put("role", "tool")
                .put("tool_call_id", "call-public-after-pending")
                .put("content", publicResult);
        messages.addObject()
                .put("role", "assistant")
                .put("content", "PUBLIC_FINAL_AFTER_NATIVE");
        return JSON.writeValueAsString(root);
    }

    private static String pendingAssistantReadProviderBody(
            String initialRead,
            String assistantRead) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", initialRead);
        messages.addObject().put("role", "assistant").put("content", assistantRead);
        messages.addObject().put("role", "assistant").put("content", CANARY);
        return JSON.writeValueAsString(root);
    }

    private static String toolResult(String toolName, String body) {
        return "[tool_result: " + toolName + "]\n" + body + "\n[/tool_result]";
    }

    private static void assertReadResultRedacted(String rendered, String originalToolName) {
        assertTrue(rendered.contains(PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION), rendered);
        assertTrue(rendered.contains(canonicalProtectedReadResult()), rendered);
        assertFalse(rendered.contains(CANARY), rendered);
        if (!"talos.read_file".equals(originalToolName)) {
            assertFalse(rendered.contains("[tool_result: " + originalToolName + "]"), rendered);
        }
    }

    private static String canonicalProtectedReadResult() {
        return "[tool_result: talos.read_file]\n"
                + PromptDebugInspector.PROTECTED_TOOL_RESULT_REDACTION
                + "\n[/tool_result]";
    }

    private static int count(String value, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return true;
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) return true;
        }
        return false;
    }
}
