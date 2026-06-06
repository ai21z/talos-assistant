package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for approval-gated tool execution in {@link TurnProcessor}.
 * Verifies that READ_ONLY tools bypass the gate, WRITE/DESTRUCTIVE tools
 * require approval, and denied operations return a DENIED error.
 */
class ApprovalGatedToolTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @Test
    void readOnlyToolBypassesApprovalGate() {
        // Gate that always denies — should not matter for READ_ONLY
        var registry = new ToolRegistry();
        registry.register(readOnlyTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> false, // always deny
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_read", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertTrue(result.success(), "READ_ONLY tool should bypass approval gate");
        assertEquals("read-ok", result.output());
    }

    @Test
    void writeToolApprovedExecutes() {
        var registry = new ToolRegistry();
        registry.register(writeTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> true, // always approve
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_write", Map.of("path", "foo.txt"));

        ToolResult result = processor.executeTool(session, call, ctx);
        assertTrue(result.success(), "Approved WRITE tool should execute");
        assertEquals("write-ok", result.output());
    }

    @Test
    void writeToolDeniedReturnsDeniedError() {
        var registry = new ToolRegistry();
        registry.register(writeTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> false, // always deny
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_write", Map.of("path", "foo.txt"));

        ToolResult result = processor.executeTool(session, call, ctx);
        assertFalse(result.success(), "Denied WRITE tool should fail");
        assertNotNull(result.error());
        assertEquals(ToolError.DENIED, result.error().code());
    }

    @Test
    void destructiveToolDeniedReturnsDeniedError() {
        var registry = new ToolRegistry();
        registry.register(destructiveTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> false,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_destroy", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code());
    }

    @Test
    void destructiveToolApprovedExecutes() {
        var registry = new ToolRegistry();
        registry.register(destructiveTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> true,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_destroy", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertTrue(result.success());
        assertEquals("destroy-ok", result.output());
    }

    @Test
    void unknownToolReturnsNotFound() {
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                new NoOpApprovalGate(),
                new ToolRegistry());

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("nonexistent", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertFalse(result.success());
        assertEquals(ToolError.NOT_FOUND, result.error().code());
    }

    @Test
    void approvalGateReceivesToolNameInDescription() {
        var registry = new ToolRegistry();
        registry.register(writeTool());

        final String[] captured = {null, null};
        ApprovalGate gate = (desc, detail) -> {
            captured[0] = desc;
            captured[1] = detail;
            return true;
        };

        var processor = new TurnProcessor(
                ModeController.defaultController(), gate, registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_write", Map.of("path", "src/Main.java"));

        processor.executeTool(session, call, ctx);

        assertNotNull(captured[0]);
        assertTrue(captured[0].contains("talos.test_write"),
                "Approval description should contain tool name");
        assertNotNull(captured[1]);
        assertTrue(captured[1].contains("src/Main.java"),
                "Approval detail should contain target path");
    }

    @Test
    void protectedReadWithAccidentalLeadingWhitespaceAsksForCanonicalPathAndSucceeds(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve(".env"), "SAFE_AUDIT_SECRET=allowed-after-approval\n");
        var registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        final String[] captured = {null, null};
        ApprovalGate gate = (desc, detail) -> {
            captured[0] = desc;
            captured[1] = detail;
            return true;
        };
        Config config = new Config(null);
        var processor = new TurnProcessor(ModeController.defaultController(), gate, registry);
        var session = new Session(workspace, config);
        var ctx = Context.builder(config)
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
        var call = new ToolCall("talos.read_file", Map.of("path", " .env"));

        LocalTurnTraceCapture.begin(
                "trc-path-normalized",
                "sid",
                1,
                "2026-05-06T00:00:00Z",
                "workspace-hash",
                "test",
                "scripted",
                "test-model",
                "Read .env");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(result.success(), result.errorMessage());
            assertTrue(result.output().contains("SAFE_AUDIT_SECRET=allowed-after-approval"), result.output());
            assertNotNull(captured[1]);
            assertTrue(captured[1].contains(".env"), captured[1]);
            assertTrue(trace.events().stream().anyMatch(event ->
                    "TOOL_PATH_ARGUMENT_NORMALIZED".equals(event.type())
                            && " .env".equals(event.data().get("rawPath"))
                            && ".env".equals(event.data().get("normalizedPath"))),
                    trace.events().toString());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void protectedReadWithAccidentalLeadingWhitespaceDeniedWithoutLeakingContent(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve(".env"), "SAFE_AUDIT_SECRET=must-not-leak\n");
        var registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        var processor = new TurnProcessor(ModeController.defaultController(), (desc, detail) -> false, registry);
        Config config = new Config(null);
        var session = new Session(workspace, config);
        var ctx = Context.builder(config)
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
        var call = new ToolCall("talos.read_file", Map.of("path", " .env"));

        ToolResult result = processor.executeTool(session, call, ctx);

        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code());
        assertTrue(result.errorMessage().contains(".env"), result.errorMessage());
        assertFalse(result.errorMessage().contains("must-not-leak"), result.errorMessage());
    }

    @Test
    void noOpGateAllowsWriteTools() {
        // Default behavior: NoOpApprovalGate always approves
        var registry = new ToolRegistry();
        registry.register(writeTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                new NoOpApprovalGate(),
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_write", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertTrue(result.success(), "NoOpApprovalGate should approve everything");
    }

    @Test
    void readOnlyPromptBlocksEditFileBeforeApproval() {
        var registry = new ToolRegistry();
        registry.register(editFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.edit_file", Map.of(
                "path", "index.html",
                "old_string", "<title>Night Drive</title>",
                "new_string", "<title>Changed</title>"));

        TurnUserRequestCapture.set("hey can you tell me what is in this workspace?");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertFalse(result.success(), "read-only prompt must reject edit_file");
            assertEquals(ToolError.DENIED, result.error().code());
            assertTrue(result.errorMessage().contains("did not ask to modify files on this turn"));
            assertEquals(0, gateCalls[0], "mutation-intent guard must fire before approval");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void readOnlyPromptBlocksWriteFileBeforeApproval() {
        var registry = new ToolRegistry();
        registry.register(writeFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.write_file", Map.of(
                "path", "index.html",
                "content", "<h1>changed</h1>"));

        TurnUserRequestCapture.set("what is this project?");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertFalse(result.success(), "read-only prompt must reject write_file");
            assertEquals(ToolError.DENIED, result.error().code());
            assertTrue(result.errorMessage().contains("did not ask to modify files on this turn"));
            assertEquals(0, gateCalls[0], "mutation-intent guard must fire before approval");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void metaQuestionAboutEditToolStillBlocksMutationBeforeApproval() {
        var registry = new ToolRegistry();
        registry.register(editFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.edit_file", Map.of(
                "path", "index.html",
                "old_string", "old",
                "new_string", "new"));

        TurnUserRequestCapture.set("Why didn't you call the edit tool?");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertFalse(result.success(), "meta-question must remain read-only");
            assertEquals(ToolError.DENIED, result.error().code());
            assertEquals(0, gateCalls[0], "contract guard must fire before approval");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void explicitEditRequestStillReachesApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "old\n");
        var registry = new ToolRegistry();
        registry.register(editFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
        var session = new Session(workspace, new Config());
        var call = new ToolCall("talos.edit_file", Map.of(
                "path", "index.html",
                "old_string", "old",
                "new_string", "new"));

        TurnUserRequestCapture.set("edit the title in index.html");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertTrue(result.success(), "explicit edit request should keep approval path: " + result.errorMessage());
            assertEquals(1, gateCalls[0], "approval should still be consulted");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void editFileWithEmptyOldStringFailsBeforeApproval() {
        var registry = new ToolRegistry();
        registry.register(editFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.edit_file", Map.of(
                "path", "index.html",
                "old_string", "",
                "new_string", ""));

        TurnUserRequestCapture.set("edit index.html to add the CTA class");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertFalse(result.success(), "invalid edit_file args must fail before approval");
            assertEquals(ToolError.INVALID_PARAMS, result.error().code());
            assertTrue(result.errorMessage().contains("old_string"));
            assertTrue(result.errorMessage().contains("No approval was requested"));
            assertEquals(0, gateCalls[0], "invalid edit_file args must not ask approval");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void editFileNoOpFailsBeforeApproval() {
        var registry = new ToolRegistry();
        registry.register(editFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.edit_file", Map.of(
                "path", "index.html",
                "old_string", "Horror Synth",
                "new_string", "Horror Synth"));

        TurnUserRequestCapture.set("edit the title in index.html");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertFalse(result.success(), "no-op edit_file calls must fail before approval");
            assertEquals(ToolError.INVALID_PARAMS, result.error().code());
            assertTrue(result.errorMessage().contains("identical"));
            assertEquals(0, gateCalls[0], "no-op edit_file calls must not ask approval");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void editFileDeletionStillReachesApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<div class=\"unused\"></div>\n");
        var registry = new ToolRegistry();
        registry.register(editFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
        var session = new Session(workspace, new Config());
        var call = new ToolCall("talos.edit_file", Map.of(
                "path", "index.html",
                "old_string", "<div class=\"unused\"></div>",
                "new_string", ""));

        TurnUserRequestCapture.set("remove the unused div from index.html");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertTrue(result.success(), "empty new_string is valid deletion and should reach approval: "
                    + result.errorMessage());
            assertEquals(1, gateCalls[0], "valid deletion should still ask approval");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void editFileMissingPathFailsBeforeApproval() {
        var registry = new ToolRegistry();
        registry.register(editFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.edit_file", Map.of(
                "old_string", "old",
                "new_string", "new"));

        TurnUserRequestCapture.set("edit the file");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertFalse(result.success(), "missing path must fail before approval");
            assertEquals(ToolError.INVALID_PARAMS, result.error().code());
            assertTrue(result.errorMessage().contains("path"));
            assertEquals(0, gateCalls[0], "missing path must not ask approval");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void writeFileEscapingWorkspaceFailsBeforeApproval(@TempDir Path workspace) {
        var registry = new ToolRegistry();
        registry.register(writeFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
        var session = new Session(workspace, new Config());
        var call = new ToolCall("talos.write_file", Map.of(
                "path", "../outside-talos-qa.txt",
                "content", "hello from Talos"));

        TurnUserRequestCapture.set("Create a file at ../outside-talos-qa.txt with the text hello from Talos.");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertFalse(result.success(), "escaping write_file path must fail before approval");
            assertEquals(ToolError.INVALID_PARAMS, result.error().code());
            assertTrue(result.errorMessage().contains("Path not allowed before approval"));
            assertTrue(result.errorMessage().contains("path escapes workspace"));
            assertTrue(result.errorMessage().contains("No approval was requested"));
            assertEquals(0, gateCalls[0], "escaping write_file path must not ask approval");
            assertFalse(Files.exists(workspace.getParent().resolve("outside-talos-qa.txt")),
                    "outside path must not be created");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void editFileEscapingWorkspaceFailsBeforeApproval(@TempDir Path workspace) {
        var registry = new ToolRegistry();
        registry.register(editFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
        var session = new Session(workspace, new Config());
        var call = new ToolCall("talos.edit_file", Map.of(
                "path", "../outside-talos-qa.txt",
                "old_string", "hello",
                "new_string", "goodbye"));

        TurnUserRequestCapture.set("Edit ../outside-talos-qa.txt so hello becomes goodbye.");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertFalse(result.success(), "escaping edit_file path must fail before approval");
            assertEquals(ToolError.INVALID_PARAMS, result.error().code());
            assertTrue(result.errorMessage().contains("Path not allowed before approval"));
            assertTrue(result.errorMessage().contains("path escapes workspace"));
            assertEquals(0, gateCalls[0], "escaping edit_file path must not ask approval");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void explicitWriteRequestStillReachesApproval() {
        var registry = new ToolRegistry();
        registry.register(writeFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.write_file", Map.of(
                "path", "README.md",
                "content", "# hi"));

        TurnUserRequestCapture.set("create a README.md file with a short project description");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertTrue(result.success(), "explicit write request should keep approval path");
            assertEquals(1, gateCalls[0], "approval should still be consulted");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void directImperativeEditRequestStillReachesApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("greeting.txt"), "Hello world\n");
        var registry = new ToolRegistry();
        registry.register(editFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
        var session = new Session(workspace, new Config());
        var call = new ToolCall("talos.edit_file", Map.of(
                "path", "greeting.txt",
                "old_string", "Hello world",
                "new_string", "Hello Talos"));

        TurnUserRequestCapture.set("Edit greeting.txt so Hello world becomes Hello Talos.");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertTrue(result.success(), "direct imperative edit request should keep approval path: "
                    + result.errorMessage());
            assertEquals(1, gateCalls[0], "approval should still be consulted");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void directImperativeWriteRequestStillReachesApproval() {
        var registry = new ToolRegistry();
        registry.register(writeFileTool());

        final int[] gateCalls = {0};
        ApprovalGate gate = (desc, detail) -> {
            gateCalls[0]++;
            return true;
        };
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.write_file", Map.of(
                "path", "index.html",
                "content", "<h1>after</h1>"));

        TurnUserRequestCapture.set("Replace index.html with after.");
        try {
            ToolResult result = processor.executeTool(session, call, ctx);
            assertTrue(result.success(), "direct imperative write request should keep approval path");
            assertEquals(1, gateCalls[0], "approval should still be consulted");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    // ── Stub tools ──────────────────────────────────────────────────

    private static TalosTool readOnlyTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.test_read"; }
            @Override public String description() { return "Read-only test tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.test_read", "Read-only test", null, ToolRiskLevel.READ_ONLY);
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("read-ok"); }
        };
    }

    private static TalosTool writeTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.test_write"; }
            @Override public String description() { return "Write test tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.test_write", "Write test", null, ToolRiskLevel.WRITE);
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("write-ok"); }
        };
    }

    private static TalosTool destructiveTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.test_destroy"; }
            @Override public String description() { return "Destructive test tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.test_destroy", "Destructive test", null, ToolRiskLevel.DESTRUCTIVE);
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("destroy-ok"); }
        };
    }

    private static TalosTool writeFileTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.write_file"; }
            @Override public String description() { return "Write file test tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.write_file", "Write file test", null, ToolRiskLevel.WRITE);
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("write-file-ok"); }
        };
    }

    private static TalosTool editFileTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.edit_file"; }
            @Override public String description() { return "Edit file test tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.edit_file", "Edit file test", null, ToolRiskLevel.WRITE);
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("edit-file-ok"); }
        };
    }
}

