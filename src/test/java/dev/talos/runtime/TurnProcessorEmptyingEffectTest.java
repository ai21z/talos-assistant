package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Risk follows the effect. A change that leaves an existing non-empty file empty is destructive,
 * so remembered session approval for writes never covers it, and the prompt says what it does
 * (live session 2026-10-27, turns 15 and 16: "delete this page" emptied index.html without a prompt).
 */
class TurnProcessorEmptyingEffectTest {

    @TempDir
    Path temporary;

    /** Bound by its real path, as the product binds a workspace at startup. */
    private Path workspace;

    private static final String PAGE = "<!DOCTYPE html>\n<html><body><h1>Dead Synth</h1></body></html>\n";

    @BeforeEach
    void seed() throws Exception {
        workspace = temporary.toRealPath();
        Files.writeString(workspace.resolve("index.html"), PAGE, StandardCharsets.UTF_8);
        TurnUserRequestCapture.set("rewrite index.html");
    }

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    /** Records what the user would see, then denies, so a destructive effect never lands in the test. */
    static final class RecordingGate implements ApprovalGate {
        final List<String> descriptions = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        @Override
        public boolean approve(String desc, String detail) {
            descriptions.add(desc);
            details.add(detail);
            return false;
        }
    }

    private ToolResult run(RecordingGate gate, ToolCall call) {
        return run(gate, call, new Config());
    }

    private ToolResult run(RecordingGate gate, ToolCall call, Config config) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        SessionApprovalPolicy policy = new SessionApprovalPolicy();
        policy.rememberApproval(workspace, new ToolCall("talos.write_file", Map.of(
                "path", workspace.resolve("other.html").toString(), "content", "x")), ToolRiskLevel.WRITE);
        assertTrue(policy.rememberInWorkspaceWritesEnabled());
        TurnProcessor processor = new TurnProcessor(ModeController.defaultController(), gate, registry, policy);
        return processor.executeTool(new Session(workspace, config), call,
                Context.builder(config).sandbox(new dev.talos.core.security.Sandbox(workspace, Map.of())).build());
    }

    private String page() throws Exception {
        return Files.readString(workspace.resolve("index.html"), StandardCharsets.UTF_8);
    }

    private static void assertDestructivePrompt(RecordingGate gate) {
        assertEquals(1, gate.descriptions.size(), "the emptying change must reach the approval prompt");
        assertTrue(gate.descriptions.getFirst().startsWith("destructive"), gate.descriptions.getFirst());
        assertTrue(gate.details.getFirst().contains("leaves index.html empty"), gate.details.getFirst());
    }

    @Test
    void anEmptyOverwriteOfAPageAsksAsDestructiveDespiteRememberedWrites() throws Exception {
        RecordingGate gate = new RecordingGate();
        ToolResult result = run(gate, new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "")));

        assertDestructivePrompt(gate);
        assertFalse(result.success());
        assertEquals(PAGE, page());
    }

    @Test
    void aWhitespaceOnlyOverwriteIsTheSameEffect() throws Exception {
        RecordingGate gate = new RecordingGate();
        run(gate, new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "  \n\t\n")));

        assertDestructivePrompt(gate);
        assertEquals(PAGE, page());
    }

    @Test
    void anEditThatRemovesTheWholeContentIsTheSameEffect() throws Exception {
        RecordingGate gate = new RecordingGate();
        run(gate, new ToolCall("talos.edit_file", Map.of("path", "index.html", "old_string", PAGE, "new_string", "")));

        assertDestructivePrompt(gate);
        assertEquals(PAGE, page());
    }

    @Test
    void aConfiguredAllowRuleStillShowsTheErasingEffect() throws Exception {
        Config config = new Config();
        config.data.put("permissions", Map.of("rules", List.of(Map.of(
                "effect", "allow",
                "tools", List.of("talos.write_file"),
                "reason", "owner allows writes"))));
        RecordingGate gate = new RecordingGate();
        run(gate, new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "")), config);

        assertDestructivePrompt(gate);
        assertEquals(PAGE, page());
    }

    @Test
    void aDenyRuleScopedToWritesStillDeniesAnEmptyingWrite() throws Exception {
        Config config = new Config();
        config.data.put("permissions", Map.of("rules", List.of(Map.of(
                "effect", "deny",
                "tools", List.of("talos.write_file"),
                "risks", List.of("write"),
                "reason", "owner denies writes here"))));
        RecordingGate gate = new RecordingGate();
        ToolResult result = run(gate, new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "")), config);

        assertTrue(gate.descriptions.isEmpty(), "raising the risk must not turn a deny into a prompt");
        assertFalse(result.success());
        assertEquals(PAGE, page());
    }

    @Test
    void anEditLeavingOnlyAByteOrderMarkIsTheSameEffect() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "﻿" + PAGE, StandardCharsets.UTF_8);
        RecordingGate gate = new RecordingGate();
        run(gate, new ToolCall("talos.edit_file", Map.of("path", "index.html", "old_string", PAGE, "new_string", "")));

        assertDestructivePrompt(gate);
        assertEquals("﻿" + PAGE, page());
    }

    @Test
    void aWhitespaceWriteToAMarkedFileIsTheSameEffect() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "﻿" + PAGE, StandardCharsets.UTF_8);
        RecordingGate gate = new RecordingGate();
        run(gate, new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "\n")));

        assertDestructivePrompt(gate);
        assertEquals("﻿" + PAGE, page());
    }

    @Test
    void answeringApproveForSessionOnAnErasingPromptIsNotRemembered() throws Exception {
        java.util.concurrent.atomic.AtomicInteger prompts = new java.util.concurrent.atomic.AtomicInteger();
        ApprovalGate rememberingGate = new ApprovalGate() {
            @Override
            public boolean approve(String desc, String detail) {
                return approveFull(desc, detail).isApproved();
            }

            @Override
            public ApprovalResponse approveFull(String desc, String detail) {
                prompts.incrementAndGet();
                return ApprovalResponse.APPROVED_REMEMBER;
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(ModeController.defaultController(), rememberingGate, registry,
                new SessionApprovalPolicy());
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new dev.talos.core.security.Sandbox(workspace, Map.of())).build();

        processor.executeTool(session, new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "")), context);
        Files.writeString(workspace.resolve("index.html"), PAGE, StandardCharsets.UTF_8);
        processor.executeTool(session, new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "")), context);

        assertEquals(2, prompts.get(), "every erasing change is asked again, whatever the earlier answer");
    }

    @Test
    void aConfiguredAllowRuleKeepsItsAuthorityOverAnOrdinaryWrite() throws Exception {
        Config config = new Config();
        config.data.put("permissions", Map.of("rules", List.of(Map.of(
                "effect", "allow",
                "tools", List.of("talos.write_file"),
                "reason", "owner allows writes"))));
        RecordingGate gate = new RecordingGate();
        ToolResult result = run(gate, new ToolCall("talos.write_file", Map.of(
                "path", "index.html", "content", "<html><body>new</body></html>\n")), config);

        assertTrue(gate.descriptions.isEmpty(), "the configured rule allows an ordinary write");
        assertTrue(result.success(), result.output() + " " + result.error());
    }

    @Test
    void anOrdinaryOverwriteStaysCoveredByRememberedWrites() throws Exception {
        RecordingGate gate = new RecordingGate();
        ToolResult result = run(gate, new ToolCall("talos.write_file", Map.of(
                "path", "index.html", "content", "<html><body>new</body></html>\n")));

        assertTrue(gate.descriptions.isEmpty(), "an ordinary overwrite keeps the remembered approval");
        assertTrue(result.success(), result.output() + " " + result.error());
    }

    @Test
    void aPartialEditStaysCoveredByRememberedWrites() throws Exception {
        RecordingGate gate = new RecordingGate();
        ToolResult result = run(gate, new ToolCall("talos.edit_file", Map.of(
                "path", "index.html", "old_string", "<h1>Dead Synth</h1>", "new_string", "")));

        assertTrue(gate.descriptions.isEmpty(), "removing part of a page is an ordinary edit");
        assertTrue(result.success(), result.output() + " " + result.error());
    }

    @Test
    void creatingAnEmptyNewFileIsNotDestructive() throws Exception {
        TurnUserRequestCapture.set("create an empty empty.txt");
        RecordingGate gate = new RecordingGate();
        ToolResult result = run(gate, new ToolCall("talos.write_file", Map.of("path", "empty.txt", "content", "")));

        assertTrue(gate.descriptions.isEmpty(), "nothing existing is lost");
        assertTrue(result.success(), result.output() + " " + result.error());
    }

    @Test
    void emptyingAnAlreadyEmptyFileIsNotDestructive() throws Exception {
        Files.writeString(workspace.resolve("blank.txt"), "", StandardCharsets.UTF_8);
        TurnUserRequestCapture.set("rewrite blank.txt");
        RecordingGate gate = new RecordingGate();
        ToolResult result = run(gate, new ToolCall("talos.write_file", Map.of("path", "blank.txt", "content", "")));

        assertTrue(gate.descriptions.isEmpty(), "nothing existing is lost");
        assertTrue(result.success(), result.output() + " " + result.error());
    }
}
