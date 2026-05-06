package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
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
}
