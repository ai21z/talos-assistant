package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.spi.types.ToolChoiceMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDebugCommandTest {

    private final Context ctx = Context.builder(new Config()).build();

    @AfterEach
    void clearCapture() {
        PromptDebugCapture.clear();
        System.clearProperty("talos.promptDebugDir");
    }

    @Test
    void commandIsHiddenAndHasInternalHelp() throws Exception {
        PromptDebugCommand command = new PromptDebugCommand();

        assertTrue(command.spec().hidden());

        Result result = command.execute("help", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        assertTrue(info.text.contains("/prompt-debug last"), info.text);
        assertTrue(info.text.contains("internal"), info.text.toLowerCase());
    }

    @Test
    void lastReportsMissingCapture() throws Exception {
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("last", ctx);

        Result.Info info = assertInstanceOf(Result.Info.class, result);
        assertTrue(info.text.contains("No prompt debug capture"), info.text);
    }

    @Test
    void lastExplainsRuntimeOwnedTurnWhenNoProviderPromptWasSent() throws Exception {
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "llama_cpp",
                        "qwen2.5-coder-14b",
                        "",
                        "",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(ChatMessage.user("Previous provider turn")),
                        List.of()),
                false,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"Previous provider turn\"}]}"));
        var directCtx = Context.builder(new Config())
                .llm(LlmClient.scripted("this should not be used"))
                .build();
        AssistantTurnExecutor.execute(
                new java.util.ArrayList<>(List.of(
                        ChatMessage.system("system"),
                        ChatMessage.user("What can you do in this workspace? Answer briefly."))),
                Path.of(".").toAbsolutePath().normalize(),
                directCtx,
                new AssistantTurnExecutor.Options());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("last", ctx);

        Result.Info info = assertInstanceOf(Result.Info.class, result);
        assertTrue(info.text.contains("No provider prompt was sent for the last turn"), info.text);
        assertFalse(info.text.contains("No prompt debug capture has been recorded"), info.text);
    }

    @Test
    void saveAllExplainsRuntimeOwnedTurnWhenNoProviderPromptWasSent() throws Exception {
        var directCtx = Context.builder(new Config())
                .llm(LlmClient.scripted("this should not be used"))
                .build();
        AssistantTurnExecutor.execute(
                new java.util.ArrayList<>(List.of(
                        ChatMessage.system("system"),
                        ChatMessage.user("What can you do in this workspace? Answer briefly."))),
                Path.of(".").toAbsolutePath().normalize(),
                directCtx,
                new AssistantTurnExecutor.Options());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save-all", ctx);

        Result.Info info = assertInstanceOf(Result.Info.class, result);
        assertTrue(info.text.contains("No provider prompt was sent for the last turn"), info.text);
        assertFalse(info.text.contains("No prompt debug capture has been recorded"), info.text);
    }

    @Test
    void lastRendersPromptDiagnosticsAndExpectedTargetCoverage() throws Exception {
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "ollama",
                        "qwen2.5-coder:14b",
                        "",
                        "",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(
                                ChatMessage.system("main system"),
                                ChatMessage.system("[CurrentTurnCapability]\n[TaskContract]\ntype: FILE_CREATE"),
                                ChatMessage.user("Create index.html, styles.css, and scripts.js")),
                        List.of(new ToolSpec("talos.write_file", "Write", "{}")),
                        new ChatRequestControls(
                                ToolChoiceMode.REQUIRED,
                                "",
                                ResponseFormatMode.JSON_OBJECT,
                                "",
                                List.of("expected-target-repair"))),
                false,
                "{\"model\":\"qwen2.5-coder:14b\",\"system\":\"main system\\n\\n[CurrentTurnCapability]\",\"messages\":[{\"role\":\"user\",\"content\":\"Create index.html, styles.css, and scripts.js\"}]}"));
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("last", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        assertTrue(info.text.contains("# Talos Prompt Debug"), info.text);
        assertTrue(info.text.contains("Stage: OLLAMA_HTTP_BODY"), info.text);
        assertTrue(info.text.contains("Ollama merges system messages"), info.text);
        assertTrue(info.text.contains("Tool choice: REQUIRED"), info.text);
        assertTrue(info.text.contains("Response format: JSON_OBJECT"), info.text);
        assertTrue(info.text.contains("Debug tags: expected-target-repair"), info.text);
        assertTrue(info.text.contains("Expected-target coverage: MISSING"), info.text);
        assertTrue(info.text.contains("Expected targets:"), info.text);
        assertTrue(info.text.contains("index.html"), info.text);
        assertTrue(info.text.contains("styles.css"), info.text);
        assertTrue(info.text.contains("scripts.js"), info.text);
        assertFalse(info.text.contains("SECRET_VALUE"), info.text);
    }

    @Test
    void readOnlyPromptDebugDoesNotReportMissingMutationTargetCoverage() throws Exception {
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "llama_cpp",
                        "qwen2.5-coder-14b",
                        "",
                        "",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(
                                ChatMessage.system("main system"),
                                ChatMessage.system("""
                                        [CurrentTurnCapability]
                                        [TaskContract]
                                        type: DIAGNOSE_ONLY
                                        mutationAllowed: false
                                        verificationRequired: false
                                        phase: INSPECT
                                        """),
                                ChatMessage.user("Review index.html, styles.css, and script.js and say whether the static page works. Do not edit files.")),
                        List.of(new ToolSpec("talos.read_file", "Read", "{}"))),
                false,
                "{\"model\":\"qwen2.5-coder-14b\",\"messages\":[{\"role\":\"user\",\"content\":\"Review index.html, styles.css, and script.js\"}]}"));
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("last", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        assertTrue(info.text.contains("mutationAllowed=false"),
                info.text);
        assertTrue(info.text.contains("Evidence target hints:"), info.text);
        assertTrue(info.text.contains("Evidence-target frame coverage: N/A (read-only task)"),
                info.text);
        assertFalse(info.text.contains("Expected-target coverage: MISSING"), info.text);
    }

    @Test
    void lastRedactsProtectedToolResultsAndKeepsPublicToolResults() throws Exception {
        PromptDebugCapture.record(protectedToolResultSnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("last", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        assertTrue(info.text.contains("[protected tool result redacted by prompt-debug policy]"), info.text);
        assertFalse(info.text.contains("SECRET=manual-test"), info.text);
        assertFalse(info.text.contains("MODE=dev"), info.text);
        assertTrue(info.text.contains("Public project notes."), info.text);
    }

    @Test
    void saveWritesRedactedProviderBodyJsonByDefault() throws Exception {
        PromptDebugCapture.record(protectedToolResultSnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        Path providerBody = savedPath(info.text, "Saved provider body JSON to: ");
        Path render = savedPath(info.text, "Saved prompt debug render to: ");
        try {
            String savedJson = Files.readString(providerBody);
            assertTrue(savedJson.contains("[protected tool result redacted by prompt-debug policy]"), savedJson);
            assertFalse(savedJson.contains("SECRET=manual-test"), savedJson);
            assertFalse(savedJson.contains("MODE=dev"), savedJson);
            assertTrue(savedJson.contains("Public project notes."), savedJson);
        } finally {
            Files.deleteIfExists(providerBody);
            Files.deleteIfExists(render);
        }
    }

    @Test
    void saveDelegatesArtifactWritingToPromptDebugArtifactWriter() throws Exception {
        Path commandPath = Path.of("src/main/java/dev/talos/cli/repl/slash/PromptDebugCommand.java");
        Path writerPath = Path.of("src/main/java/dev/talos/cli/prompt/PromptDebugArtifactWriter.java");

        assertTrue(Files.exists(writerPath),
                "PromptDebugArtifactWriter should own prompt-debug artifact file naming and writes");

        String command = Files.readString(commandPath);
        String writer = Files.readString(writerPath);

        assertTrue(command.contains("PromptDebugArtifactWriter.writeLatest("), command);
        assertTrue(command.contains("PromptDebugArtifactWriter.writeHistory("), command);
        assertFalse(command.contains("Files.writeString("), command);
        assertFalse(command.contains("DateTimeFormatter"), command);
        assertTrue(writer.contains("Files.writeString("), writer);
        assertTrue(writer.contains("public record LatestArtifact"), writer);
        assertTrue(writer.contains("public record HistoryArtifact"), writer);
        assertFalse(writer.contains("dev.talos.runtime.Result"), writer);
    }

    @Test
    void saveUsesConfiguredDirectoryInsteadOfWorkspaceLocalPrompts(@TempDir Path tempDir) throws Exception {
        Path configuredDir = tempDir.resolve("prompt-debug-artifacts");
        System.setProperty("talos.promptDebugDir", configuredDir.toString());
        PromptDebugCapture.record(protectedToolResultSnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        Path providerBody = savedPath(info.text, "Saved provider body JSON to: ");
        Path render = savedPath(info.text, "Saved prompt debug render to: ");
        Path oldWorkspaceDefault = Path.of("local", "prompts").toAbsolutePath().normalize();
        assertTrue(providerBody.startsWith(configuredDir.toAbsolutePath().normalize()), info.text);
        assertTrue(render.startsWith(configuredDir.toAbsolutePath().normalize()), info.text);
        assertFalse(providerBody.startsWith(oldWorkspaceDefault), info.text);
        assertFalse(render.startsWith(oldWorkspaceDefault), info.text);
    }

    @Test
    void saveSupportsUnquotedAbsoluteDestination(@TempDir Path tempDir) throws Exception {
        Path explicitDir = tempDir.resolve("explicit-prompt-debug");
        PromptDebugCapture.record(protectedToolResultSnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save " + explicitDir, ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        Path providerBody = savedPath(info.text, "Saved provider body JSON to: ");
        Path render = savedPath(info.text, "Saved prompt debug render to: ");
        assertTrue(providerBody.startsWith(explicitDir.toAbsolutePath().normalize()), info.text);
        assertTrue(render.startsWith(explicitDir.toAbsolutePath().normalize()), info.text);
        assertTrue(Files.exists(providerBody), info.text);
        assertTrue(Files.exists(render), info.text);
    }

    @Test
    void saveSupportsQuotedAbsoluteDestination(@TempDir Path tempDir) throws Exception {
        Path explicitDir = tempDir.resolve("explicit prompt-debug");
        PromptDebugCapture.record(protectedToolResultSnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save \"" + explicitDir + "\"", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        Path providerBody = savedPath(info.text, "Saved provider body JSON to: ");
        Path render = savedPath(info.text, "Saved prompt debug render to: ");
        assertTrue(providerBody.startsWith(explicitDir.toAbsolutePath().normalize()), info.text);
        assertTrue(render.startsWith(explicitDir.toAbsolutePath().normalize()), info.text);
        assertTrue(Files.exists(providerBody), info.text);
        assertTrue(Files.exists(render), info.text);
    }

    @Test
    void saveAllSupportsExplicitDestination(@TempDir Path tempDir) throws Exception {
        Path explicitDir = tempDir.resolve("explicit-prompt-debug");
        PromptDebugCapture.record(protectedToolResultSnapshot());
        PromptDebugCapture.record(secretAssistantHistorySnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save-all " + explicitDir, ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        for (Path path : savedPaths(info.text, "Saved prompt debug render to: ")) {
            assertTrue(path.startsWith(explicitDir.toAbsolutePath().normalize()), info.text);
        }
        for (Path path : savedPaths(info.text, "Saved provider body JSON to: ")) {
            assertTrue(path.startsWith(explicitDir.toAbsolutePath().normalize()), info.text);
        }
        assertTrue(savedPath(info.text, "Saved prompt debug history index to: ")
                .startsWith(explicitDir.toAbsolutePath().normalize()), info.text);
    }

    @Test
    void saveRedactsProtectedToolResultWhenCompatArgumentsAreJsonString() throws Exception {
        PromptDebugCapture.record(protectedCompatJsonStringToolResultSnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        Path providerBody = savedPath(info.text, "Saved provider body JSON to: ");
        Path render = savedPath(info.text, "Saved prompt debug render to: ");
        try {
            String savedJson = Files.readString(providerBody);
            assertTrue(savedJson.contains("[protected tool result redacted by prompt-debug policy]"), savedJson);
            assertFalse(savedJson.contains("TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak"), savedJson);
            assertFalse(savedJson.contains("must-not-leak"), savedJson);
            assertTrue(savedJson.contains("Public project notes."), savedJson);
        } finally {
            Files.deleteIfExists(providerBody);
            Files.deleteIfExists(render);
        }
    }

    @Test
    void saveRedactsSecretLikeAssistantHistoryInProviderBody() throws Exception {
        PromptDebugCapture.record(secretAssistantHistorySnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        Path providerBody = savedPath(info.text, "Saved provider body JSON to: ");
        Path render = savedPath(info.text, "Saved prompt debug render to: ");
        try {
            String savedJson = Files.readString(providerBody);
            assertFalse(savedJson.contains("TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak"), savedJson);
            assertFalse(savedJson.contains("must-not-leak"), savedJson);
            assertTrue(savedJson.contains("TALOS_T61E_LLAMA_CPP_SECRET=[redacted]"), savedJson);
        } finally {
            Files.deleteIfExists(providerBody);
            Files.deleteIfExists(render);
        }
    }

    @Test
    void prompt_debug_does_not_save_raw_canary_after_grep() throws Exception {
        PromptDebugCapture.record(grepCanaryToolResultSnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        Path providerBody = savedPath(info.text, "Saved provider body JSON to: ");
        Path render = savedPath(info.text, "Saved prompt debug render to: ");
        try {
            String savedJson = Files.readString(providerBody);
            String savedRender = Files.readString(render);
            assertFalse(savedJson.contains("DO_NOT_LEAK_T267_PROVIDER_BODY"));
            assertFalse(savedRender.contains("DO_NOT_LEAK_T267_PROVIDER_BODY"));
            assertTrue(savedJson.contains("[protected tool result redacted by prompt-debug policy]")
                    || savedJson.contains("PRIVATE_MARKER=[redacted]"));
        } finally {
            Files.deleteIfExists(providerBody);
            Files.deleteIfExists(render);
        }
    }

    @Test
    void saveRedactsStandaloneProtectedAssistantAnswerInProviderBody() throws Exception {
        PromptDebugCapture.record(standaloneProtectedAssistantAnswerSnapshot());
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        Path providerBody = savedPath(info.text, "Saved provider body JSON to: ");
        Path render = savedPath(info.text, "Saved prompt debug render to: ");
        try {
            String savedJson = Files.readString(providerBody);
            assertFalse(savedJson.contains("must-not-leak"), savedJson);
            assertTrue(savedJson.contains("[protected assistant answer redacted by prompt-debug policy]"), savedJson);
        } finally {
            Files.deleteIfExists(providerBody);
            Files.deleteIfExists(render);
        }
    }

    @Test
    void lastAndSaveUseUserFacingCaptureAfterBackgroundMaintenanceCapture() throws Exception {
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "llama_cpp",
                        "qwen2.5-coder:14b",
                        "",
                        "",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(ChatMessage.user("Audited user prompt")),
                        List.of()),
                false,
                "{\"messages\":[{\"role\":\"user\",\"content\":\"Audited user prompt\"}]}",
                "COMPAT_CHAT_HTTP_BODY"));
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "llama_cpp",
                        "qwen2.5-coder:14b",
                        "You are a conversation summarizer for a developer CLI tool.",
                        "Recent conversation turns to incorporate:",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(),
                        List.of(),
                        new ChatRequestControls(
                                ToolChoiceMode.AUTO,
                                "",
                                ResponseFormatMode.TEXT,
                                "",
                                List.of(PromptDebugCapture.BACKGROUND_MAINTENANCE_TAG))),
                false,
                "{\"system\":\"You are a conversation summarizer for a developer CLI tool.\"}",
                "COMPAT_CHAT_HTTP_BODY"));
        PromptDebugCommand command = new PromptDebugCommand();

        Result lastResult = command.execute("last", ctx);

        Result.TrustedInfo lastInfo = assertInstanceOf(Result.TrustedInfo.class, lastResult);
        assertTrue(lastInfo.text.contains("Audited user prompt"), lastInfo.text);
        assertFalse(lastInfo.text.contains("conversation summarizer"), lastInfo.text);

        Result saveResult = command.execute("save", ctx);

        Result.TrustedInfo saveInfo = assertInstanceOf(Result.TrustedInfo.class, saveResult);
        Path providerBody = savedPath(saveInfo.text, "Saved provider body JSON to: ");
        Path render = savedPath(saveInfo.text, "Saved prompt debug render to: ");
        try {
            String savedJson = Files.readString(providerBody);
            assertTrue(savedJson.contains("Audited user prompt"), savedJson);
            assertFalse(savedJson.contains("conversation summarizer"), savedJson);
        } finally {
            Files.deleteIfExists(providerBody);
            Files.deleteIfExists(render);
        }
    }

    @Test
    void saveAllWritesUserFacingCaptureHistoryInOrderAndSkipsBackground() throws Exception {
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "llama_cpp",
                        "gpt-oss-20b",
                        "",
                        "",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(ChatMessage.user("Run the approved Gradle test command profile.")),
                        List.of(new ToolSpec("talos.run_command", "Run command", "{}")),
                        new ChatRequestControls(
                                ToolChoiceMode.REQUIRED,
                                "",
                                ResponseFormatMode.TEXT,
                                "",
                                List.of("required-tool:talos.run_command"))),
                true,
                "{\"tool_choice\":\"required\",\"messages\":[{\"role\":\"user\",\"content\":\"Run the approved Gradle test command profile.\"}]}",
                "COMPAT_CHAT_HTTP_BODY"));
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "llama_cpp",
                        "gpt-oss-20b",
                        "You are a conversation summarizer for a developer CLI tool.",
                        "Recent conversation turns to incorporate:",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(),
                        List.of(),
                        new ChatRequestControls(
                                ToolChoiceMode.AUTO,
                                "",
                                ResponseFormatMode.TEXT,
                                "",
                                List.of(PromptDebugCapture.BACKGROUND_MAINTENANCE_TAG))),
                false,
                "{\"system\":\"You are a conversation summarizer for a developer CLI tool.\"}",
                "COMPAT_CHAT_HTTP_BODY"));
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "llama_cpp",
                        "gpt-oss-20b",
                        "",
                        "",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(
                                ChatMessage.toolResult("call-command",
                                        "[tool_result: talos.run_command]\n[error] command failed\n[/tool_result]"),
                                ChatMessage.system("[Current task - stay focused on this] Run the approved Gradle test command profile.")),
                        List.of(new ToolSpec("talos.run_command", "Run command", "{}")),
                        ChatRequestControls.defaults()),
                true,
                "{\"messages\":[{\"role\":\"tool\",\"content\":\"[tool_result: talos.run_command]\\n[error] command failed\\n[/tool_result]\"}]}",
                "COMPAT_CHAT_HTTP_BODY"));
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("save-all", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        List<Path> renders = savedPaths(info.text, "Saved prompt debug render to: ");
        List<Path> providerBodies = savedPaths(info.text, "Saved provider body JSON to: ");
        Path index = savedPath(info.text, "Saved prompt debug history index to: ");
        try {
            assertTrue(info.text.contains("Saved 2 prompt debug capture(s)."), info.text);
            assertTrue(renders.size() == 2, info.text);
            assertTrue(providerBodies.size() == 2, info.text);
            String firstRender = Files.readString(renders.get(0));
            String secondRender = Files.readString(renders.get(1));
            String firstJson = Files.readString(providerBodies.get(0));
            String indexText = Files.readString(index);
            assertTrue(firstRender.contains("Tool choice: REQUIRED"), firstRender);
            assertTrue(firstRender.contains("required-tool:talos.run_command"), firstRender);
            assertTrue(secondRender.contains("Tool choice: AUTO"), secondRender);
            assertTrue(firstJson.contains("\"tool_choice\" : \"required\""), firstJson);
            assertFalse(indexText.contains("conversation summarizer"), indexText);
        } finally {
            for (Path path : renders) Files.deleteIfExists(path);
            for (Path path : providerBodies) Files.deleteIfExists(path);
            Files.deleteIfExists(index);
        }
    }

    private static PromptDebugSnapshot protectedToolResultSnapshot() {
        var envCall = new ChatMessage.NativeToolCall(
                "call-env",
                "talos.read_file",
                Map.of("path", ".env"));
        var readmeCall = new ChatMessage.NativeToolCall(
                "call-readme",
                "talos.read_file",
                Map.of("path", "README.md"));
        String providerBody = """
                {"model":"qwen2.5-coder:14b","messages":[
                  {"role":"assistant","content":"","tool_calls":[
                    {"id":"call-env","function":{"name":"talos.read_file","arguments":{"path":".env"}}},
                    {"id":"call-readme","function":{"name":"talos.read_file","arguments":{"path":"README.md"}}}
                  ]},
                  {"role":"tool","tool_call_id":"call-env","content":"1 | SECRET=manual-test\\n2 | MODE=dev\\n"},
                  {"role":"tool","tool_call_id":"call-readme","content":"1 | Public project notes.\\n"}
                ]}
                """;
        return new PromptDebugSnapshot(
                "OLLAMA_HTTP_BODY",
                "ollama",
                "qwen2.5-coder:14b",
                false,
                null,
                List.of(
                        ChatMessage.assistantWithToolCalls("", List.of(envCall, readmeCall)),
                        ChatMessage.toolResult("call-env", "1 | SECRET=manual-test\n2 | MODE=dev\n"),
                        ChatMessage.toolResult("call-readme", "1 | Public project notes.\n")),
                List.of(new ToolSpec("talos.read_file", "Read", "{}")),
                ChatRequestControls.defaults(),
                providerBody);
    }

    private static PromptDebugSnapshot protectedCompatJsonStringToolResultSnapshot() {
        String providerBody = """
                {"model":"gpt-oss-20b","messages":[
                  {"role":"assistant","content":"","tool_calls":[
                    {"id":"call-env","type":"function","function":{"name":"talos.read_file","arguments":"{\\\"path\\\":\\\".env\\\"}"}},
                    {"id":"call-readme","type":"function","function":{"name":"talos.read_file","arguments":"{\\\"path\\\":\\\"README.md\\\"}"}}
                  ]},
                  {"role":"tool","tool_call_id":"call-env","content":"[tool_result: talos.read_file]\\n1 | TALOS_T61E_LLAMA_CPP_SECRET=must-not-leak\\n\\n[/tool_result]"},
                  {"role":"tool","tool_call_id":"call-readme","content":"1 | Public project notes.\\n"}
                ]}
                """;
        return new PromptDebugSnapshot(
                "COMPAT_CHAT_HTTP_BODY",
                "llama_cpp",
                "gpt-oss-20b",
                false,
                null,
                List.of(),
                List.of(new ToolSpec("talos.read_file", "Read", "{}")),
                ChatRequestControls.defaults(),
                providerBody);
    }

    private static PromptDebugSnapshot secretAssistantHistorySnapshot() {
        String providerBody = """
                {"model":"gpt-oss-20b","messages":[
                  {"role":"assistant","content":"The `.env` file contains:\\n\\n```\\nTALOS_T61E_LLAMA_CPP_SECRET=must-not-leak\\n```\\n\\nThis indicates that the environment variable `TALOS_T61E_LLAMA_CPP_SECRET` is set to `must-not-leak`."},
                  {"role":"user","content":"Please review README.md and do not inspect protected files."}
                ]}
                """;
        return new PromptDebugSnapshot(
                "COMPAT_CHAT_HTTP_BODY",
                "llama_cpp",
                "gpt-oss-20b",
                false,
                null,
                List.of(),
                List.of(),
                ChatRequestControls.defaults(),
                providerBody);
    }

    private static PromptDebugSnapshot grepCanaryToolResultSnapshot() {
        var grepCall = new ChatMessage.NativeToolCall(
                "call-grep",
                "talos.grep",
                Map.of("pattern", "DO_NOT_LEAK"));
        String providerBody = """
                {"model":"gpt-oss-20b","messages":[
                  {"role":"assistant","content":"","tool_calls":[
                    {"id":"call-grep","type":"function","function":{"name":"talos.grep","arguments":{"pattern":"DO_NOT_LEAK"}}}
                  ]},
                  {"role":"tool","tool_call_id":"call-grep","content":"[tool_result: talos.grep]\\nnotes.md:1 | PRIVATE_MARKER = DO_NOT_LEAK_T267_PROVIDER_BODY\\n[/tool_result]"}
                ]}
                """;
        return new PromptDebugSnapshot(
                "COMPAT_CHAT_HTTP_BODY",
                "llama_cpp",
                "gpt-oss-20b",
                false,
                null,
                List.of(
                        ChatMessage.assistantWithToolCalls("", List.of(grepCall)),
                        ChatMessage.toolResult("call-grep",
                                "[tool_result: talos.grep]\nnotes.md:1 | PRIVATE_MARKER = DO_NOT_LEAK_T267_PROVIDER_BODY\n[/tool_result]")),
                List.of(new ToolSpec("talos.grep", "Search", "{}")),
                ChatRequestControls.defaults(),
                providerBody);
    }

    private static PromptDebugSnapshot standaloneProtectedAssistantAnswerSnapshot() {
        String providerBody = """
                {"model":"gpt-oss-20b","messages":[
                  {"role":"user","content":"Read .env and tell me the value inside."},
                  {"role":"assistant","content":"The value is: `must-not-leak`."},
                  {"role":"user","content":"Please review README.md and do not inspect protected files."}
                ]}
                """;
        return new PromptDebugSnapshot(
                "COMPAT_CHAT_HTTP_BODY",
                "llama_cpp",
                "gpt-oss-20b",
                false,
                null,
                List.of(),
                List.of(),
                ChatRequestControls.defaults(),
                providerBody);
    }

    private static Path savedPath(String text, String prefix) {
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) {
                return Path.of(line.substring(prefix.length()).strip());
            }
        }
        throw new AssertionError("Missing saved path line: " + prefix + "\n" + text);
    }

    private static List<Path> savedPaths(String text, String prefix) {
        return text.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> Path.of(line.substring(prefix.length()).strip()))
                .toList();
    }
}
