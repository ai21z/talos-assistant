package dev.talos.cli.modes;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.context.ContentDraftProposal;
import dev.talos.runtime.context.PendingContentDraft;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted provider output is exercised through real routing, memory, approval and filesystem tools. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicaContentDraftBoundaryTest {
    private static final String REQUEST = "I'd like a short story about Lina, a clockmaker in a seaside town. "
            + "Her clock is always seven minutes slow. Please write it and save it as a text file, not notes.txt. "
            + "I'll give you the filename in a moment.";
    private static final String NAMED_REQUEST = "Please save story.txt now. Write a short story about Lina, a clockmaker "
            + "in a seaside town whose clock is always seven minutes slow. Do not create notes.txt.";
    private static final String STORY = "# Lina's clock\n\n```text\nSeven minutes slow.\n```\n"
            + "Lina waited beside the sea until her clock caught the tide.\n";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private Path workspace;
    private String priorHome;

    @BeforeEach
    void isolate() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        priorHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(temp.resolve("home")).toString());
    }

    @AfterEach
    void closeCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTraceCapture.clear();
        ProviderResponseCapture.clear();
        dev.talos.spi.types.PromptDebugCapture.clear();
        dev.talos.cli.prompt.LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        if (priorHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", priorHome);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void draftReminderAndFilenameSaveTheShownBytesWithoutAnotherProviderCall(boolean schemaSupported) throws Exception {
        try (var conversation = new Conversation(schemaSupported, ApprovalResponse.APPROVED)) {
            Run proposed = conversation.run(REQUEST, proposal("draft", STORY));
            assertGeneration(proposed, REQUEST, schemaSupported);
            PendingContentDraft original = requireDraft(conversation);
            assertEquals(STORY, original.content());
            assertEquals("", original.destination());
            assertEquals(original.originTurn() + 5, original.expiresAfterTurn());
            assertTrue(proposed.answer().contains("> # Lina's clock\n> \n> ```text\n> Seven minutes slow.\n> ```"), proposed.answer());
            assertTrue(proposed.answer().contains("reply with the filename only"), proposed.answer());
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(proposed.answer(), REQUEST),
                    conversation.memory.getTurns().getLast().content());
            assertNoWrite(conversation);

            Run reminder = conversation.run("What was the clockmaker's name?", "The clockmaker's name was Lina.");
            assertEquals(1, reminder.requests().size());
            assertTrue(reminder.answer().contains("Lina"), reminder.answer());
            assertSame(original, conversation.memory.pendingContentDraft());
            assertEquals(original.expiresAfterTurn(), requireDraft(conversation).expiresAfterTurn());

            // The filename completes the original save request for the draft already shown in full.
            Run saved = conversation.run("story.txt");
            assertTrue(saved.requests().isEmpty(), "Selected content must be written without provider regeneration");
            assertEquals(1, conversation.approvals.get());
            assertEquals(1, saved.turn().audit().approvalsGranted());
            assertNull(conversation.memory.pendingContentDraft());
            assertArrayEquals(STORY.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(workspace.resolve("story.txt")));
            assertTrue(conversation.approvalDetails.getFirst().contains("story.txt"));
            assertFalse(Files.exists(workspace.resolve("notes.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void denialConsumesSelectionAndOnlyAFreshDraftCanReceiveFreshApproval(boolean schemaSupported) throws Exception {
        try (var conversation = new Conversation(schemaSupported, ApprovalResponse.DENIED, ApprovalResponse.APPROVED)) {
            assertGeneration(conversation.run(NAMED_REQUEST, proposal("draft", STORY)), NAMED_REQUEST, schemaSupported);
            String deniedId = requireDraft(conversation).id();
            Run denied = conversation.run("Save draft " + deniedId);
            assertTrue(denied.requests().isEmpty());
            assertEquals(1, denied.turn().audit().approvalsDenied());
            assertNull(conversation.memory.pendingContentDraft());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
            Run stale = conversation.run("Save draft " + deniedId);
            assertTrue(stale.requests().isEmpty());
            assertTrue(stale.answer().contains("No matching live draft"), stale.answer());
            assertEquals(1, conversation.approvals.get());

            conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            String freshId = requireDraft(conversation).id();
            assertNotEquals(deniedId, freshId);
            Run saved = conversation.run("Save draft " + freshId);
            assertTrue(saved.requests().isEmpty());
            assertEquals(1, saved.turn().audit().approvalsGranted());
            assertEquals(2, conversation.approvals.get());
            assertEquals(STORY, Files.readString(workspace.resolve("story.txt")));
        }
    }

    @Test
    void selectedBytesAreNotRewrittenToThePreviousFilesBomOrNewlines() throws Exception {
        Files.writeString(workspace.resolve("story.txt"), "\uFEFFPrevious story.\r\n");
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            var saved = conversation.run("Save draft " + requireDraft(conversation).id());
            assertTrue(saved.requests().isEmpty());
            assertEquals(1, conversation.approvals.get());
            assertArrayEquals(STORY.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(workspace.resolve("story.txt")));
            assertEquals("ADVISORY_ONLY", saved.turn().audit().localTrace().outcome().status());
            assertTrue(saved.answer().contains("coverage unassessed"), saved.answer());
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(saved.answer(), "Save draft"),
                    conversation.memory.getTurns().getLast().content());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "path", "extra"})
    void changedSelectedProposalsAreRejectedBeforeApproval(String change) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            var pending = requireDraft(conversation);
            String request = "Save draft " + pending.id();
            var decision = dev.talos.runtime.context.ContentDraftContinuation.evaluate(
                    dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(request), pending,
                    workspace, pending.originTurn() + 1, CapabilityPosture.AGENT, ContentRestrictions.UNRESTRICTED);
            LocalTurnTraceCapture.begin("changed-draft", "offline", 2, "2026-10-10T00:00:00Z",
                    "workspace", "agent", "fixture", "fixture", request);
            TurnTaskContractCapture.set(decision.resolution());
            TurnUserRequestCapture.set(request);
            var parameters = new java.util.LinkedHashMap<String, String>(Map.of("path", "story.txt", "content", STORY));
            if (change.equals("extra")) parameters.put("append", "true");
            else parameters.put(change, change.equals("path") ? "other.txt" : "Changed bytes");
            var result = conversation.processor.executeTool(conversation.session,
                    new dev.talos.tools.ToolCall("talos.write_file", parameters),
                    Context.builder(conversation.config).memory(conversation.memory).toolRegistry(conversation.registry)
                            .sandbox(new Sandbox(workspace, Map.of())).build());
            assertFalse(result.success());
            assertTrue(result.toString().contains("selected draft or destination changed"), result.toString());
            assertNoWrite(conversation);
            assertFalse(Files.exists(workspace.resolve("other.txt")));
        }
    }

    @Test
    void genericYesCannotSelectADraftOrGrantWritePermission() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            PendingContentDraft draft = requireDraft(conversation);
            Run yes = conversation.run("yes", "Please select the exact draft identifier.");
            assertSame(draft, conversation.memory.pendingContentDraft());
            assertEquals(0, yes.turn().audit().approvalsRequired());
            assertNoWrite(conversation);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"clear", "cancel", "workspace", "expiry"})
    void expiredClearedCancelledAndWorkspaceInvalidatedIdsCannotBeReplayed(String invalidation) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            PendingContentDraft draft = requireDraft(conversation);
            switch (invalidation) {
                case "clear" -> conversation.memory.clear();
                case "cancel" -> conversation.run("Cancel the earlier save request.");
                case "workspace" -> conversation.run("Switch workspace to another folder.", "Workspace switching is unavailable.");
                case "expiry" -> {
                    for (int i = 0; i < 5; i++) {
                        conversation.run("What was the clockmaker's name?", "Lina.");
                        assertSame(draft, conversation.memory.pendingContentDraft());
                        assertEquals(draft.expiresAfterTurn(), requireDraft(conversation).expiresAfterTurn());
                    }
                }
                default -> throw new AssertionError(invalidation);
            }
            Run stale = conversation.run("Save draft " + draft.id());
            assertTrue(stale.requests().isEmpty());
            assertTrue(stale.answer().contains("No matching live draft"), stale.answer());
            assertNull(conversation.memory.pendingContentDraft());
            assertNoWrite(conversation);
        }
    }

    @Test
    void workspaceIdentityChangeRejectsDraftAtTheRealRoutingBoundary() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            String id = requireDraft(conversation).id();
            Path otherWorkspace = Files.createDirectory(temp.resolve("other-workspace"));
            try (var otherSession = new Session(otherWorkspace, conversation.config, conversation.memory)) {
                Run stale = conversation.runIn(otherSession, "Save draft " + id, List.of());
                assertTrue(stale.requests().isEmpty());
                assertNull(conversation.memory.pendingContentDraft());
                assertFalse(Files.exists(otherWorkspace.resolve("story.txt")));
            }
            assertNoWrite(conversation);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASK_READ_ONLY", "PLAN_READ_ONLY", "restricted"})
    void readOnlyPostureAndRestrictedContentRemoveLiveDraftDuringRealPreparation(String restriction) throws Exception {
        try (var conversation = new Conversation()) {
            conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            assertNotNull(conversation.memory.pendingContentDraft());
            var input = new ArrayList<>(List.of(ChatMessage.system("You are Talos."), ChatMessage.user("Tell me a joke.")));
            CapabilityPosture posture = CapabilityPosture.AGENT;
            if (restriction.equals("restricted")) {
                input.set(1, input.get(1).withContentRestrictions(new ContentRestrictions(true, false, true, true)));
            } else posture = CapabilityPosture.valueOf(restriction);
            AssistantTurnPreparation.prepare(input, workspace,
                    Context.builder(conversation.config).memory(conversation.memory).toolRegistry(conversation.registry).build(),
                    false, posture);
            assertNull(conversation.memory.pendingContentDraft());
            assertNoWrite(conversation);
        }
    }

    @Test
    void forbiddenFilenameCannotBindOrSelectTheGeneratedContent() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST, proposal("draft", STORY));
            PendingContentDraft draft = requireDraft(conversation);
            Run forbidden = conversation.run("notes.txt");
            assertTrue(forbidden.requests().isEmpty());
            assertSame(draft, conversation.memory.pendingContentDraft());
            assertEquals("", requireDraft(conversation).destination());
            Run premature = conversation.run("Save draft " + draft.id());
            assertTrue(premature.requests().isEmpty());
            assertNoWrite(conversation);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Save a short story and a poem.",
            "Save a short story and run tests.",
            "Save \"unfinished content.",
            "Save \"alpha\" and save a short story.",
            "Read source.txt and save a short story based on it."
    })
    void compoundSourceDependentAndMalformedContentRemainOnOriginalClarificationPath(String request) throws Exception {
        try (var conversation = new Conversation()) {
            Run result = conversation.run(request);
            assertTrue(result.requests().isEmpty(), "Unsafe unresolved requests must not generate a draft");
            assertNull(conversation.memory.pendingContentDraft());
            assertEquals(request.equals("Save a short story and a poem.") || request.equals("Save a short story and run tests."),
                    conversation.memory.pendingContentSave() != null, result.answer());
            assertNoWrite(conversation);
        }
    }

    @Test
    void clearLiteralSaveRetainsTheExistingProviderToolFlow() throws Exception {
        String literal = "Lina repaired the clock.";
        String request = "Create story.txt containing exactly \"" + literal + "\"";
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.presentationAvailable = false;
            Run saved = conversation.runResponses(request, List.of(
                    new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("write-literal", "talos.write_file",
                            Map.of("path", "story.txt", "content", literal)))),
                    new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("read-literal", "talos.read_file",
                            Map.of("path", "story.txt")))),
                    new LlmClient.StreamResult("Saved story.txt.", List.of())));
            assertFalse(saved.requests().isEmpty());
            assertNull(conversation.memory.pendingContentDraft());
            assertEquals(1, conversation.approvals.get());
            assertEquals(literal, Files.readString(workspace.resolve("story.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lossyAsciiDisplayCannotPublishADraftWithAnUnshowableCharacter(boolean streaming) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.streaming = streaming;
            conversation.renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                    conversation.display, false, false, 96, new Redactor());
            Run rejected = conversation.run(NAMED_REQUEST, proposal("draft", "Lina\u2019s clock stood by the \u6d77."));
            assertGeneration(rejected, NAMED_REQUEST);
            assertNull(conversation.memory.pendingContentDraft());
            assertFalse(rejected.answer().contains("Save draft "), rejected.answer());
            assertTrue(rejected.answer().contains("U+6D77"), rejected.answer());
            assertFalse(conversation.display.toString(StandardCharsets.UTF_8).contains("Save draft "));
            Run stale = conversation.run("Save draft 0123456789ab");
            assertTrue(stale.requests().isEmpty());
            assertNoWrite(conversation);
        }
    }

    static Stream<Arguments> typographicDisplays() {
        // A plain display cannot show any typographic punctuation, a windows-1252 display lacks U+2011.
        return Stream.of(false, true).flatMap(streaming -> Stream.of(
                Arguments.of(streaming, false, StandardCharsets.UTF_8, "Lina's well-kept clock - seven minutes slow..."),
                Arguments.of(streaming, true, java.nio.charset.Charset.forName("windows-1252"),
                        "Lina\u2019s well-kept clock \u2014 seven minutes slow\u2026")));
    }

    @ParameterizedTest
    @MethodSource("typographicDisplays")
    void unshowableTypographicPunctuationIsShownPlainAndSavedAsShown(boolean streaming, boolean unicodeSafe,
            java.nio.charset.Charset charset, String shownText) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.streaming = streaming;
            conversation.renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                    conversation.display, unicodeSafe, false, 96, new Redactor(), charset);
            Run proposed = conversation.run(REQUEST, proposal("draft",
                    "Lina\u2019s well\u2011kept clock \u2014 seven minutes slow\u2026"));
            assertGeneration(proposed, REQUEST);
            assertEquals(shownText, requireDraft(conversation).content());
            assertTrue(proposed.answer().contains("> " + shownText), proposed.answer());
            assertTrue(proposed.answer().contains("Typographic quotes, dashes, special spaces or ellipses"), proposed.answer());
            assertNoWrite(conversation);

            Run saved = conversation.run("story.txt");
            assertTrue(saved.requests().isEmpty());
            assertEquals(1, conversation.approvals.get());
            assertNull(conversation.memory.pendingContentDraft());
            assertArrayEquals(shownText.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(workspace.resolve("story.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"show me notes.md", "call it story.txt", "What is in notes.txt", "make it shorter for version 2.0",
            "v2.0", "3.14", "www.example.com", "example.org"})
    void repliesThatAreNotFilenamesDoNotSelectTheShownDraft(String reply) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST, proposal("draft", STORY));
            requireDraft(conversation);
            Run other = conversation.run(reply, "No file was written.");
            assertEquals(0, conversation.approvals.get(), other.answer());
            try (var entries = Files.list(workspace)) {
                assertEquals(List.of(), entries.toList(), other.answer());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lina story.txt", "Lina's story (1).txt", "notes@2026+draft.txt"})
    void quotedFilenameSelectsTheShownDraft(String name) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST, proposal("draft", STORY));
            Run saved = conversation.run("\"" + name + "\"");
            assertTrue(saved.requests().isEmpty());
            assertEquals(1, conversation.approvals.get(), saved.answer());
            assertArrayEquals(STORY.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(workspace.resolve(name)));
        }
    }

    @Test
    void hostnameShapedReplyKeepsTheDraftAndQuotingUsesItAsAFilename() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST, proposal("draft", STORY));
            PendingContentDraft draft = requireDraft(conversation);
            Run address = conversation.run("notes.org");
            assertTrue(address.requests().isEmpty());
            assertTrue(address.answer().contains("web address"), address.answer());
            assertSame(draft, conversation.memory.pendingContentDraft());
            Run saved = conversation.run("\"notes.org\"");
            assertEquals(1, conversation.approvals.get(), saved.answer());
            assertEquals(STORY, Files.readString(workspace.resolve("notes.org")));
        }
    }

    @Test
    void aDifferentFilenameAfterAnExistingOneSavesElsewhere() throws Exception {
        Files.writeString(workspace.resolve("story.txt"), "Previous story.\n");
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST, proposal("draft", STORY));
            Run exists = conversation.run("story.txt");
            assertTrue(exists.answer().contains("different filename"), exists.answer());
            Run saved = conversation.run("story2.txt");
            assertEquals(1, conversation.approvals.get(), saved.answer());
            assertEquals(STORY, Files.readString(workspace.resolve("story2.txt")));
            assertEquals("Previous story.\n", Files.readString(workspace.resolve("story.txt")));
        }
    }

    @Test
    void aRefusedLaterNameClearsTheEarlierExistingDestination() throws Exception {
        Files.writeString(workspace.resolve("story.txt"), "Previous story.\n");
        Files.createDirectory(workspace.resolve("archive.old"));
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST, proposal("draft", STORY));
            conversation.run("story.txt");
            String id = requireDraft(conversation).id();
            Run refused = conversation.run("archive.old");
            assertEquals("", requireDraft(conversation).destination(), refused.answer());
            Run stale = conversation.run("Save draft " + id);
            assertTrue(stale.answer().contains("No matching live draft"), stale.answer());
            assertEquals(0, conversation.approvals.get());
            assertEquals("Previous story.\n", Files.readString(workspace.resolve("story.txt")));
        }
    }

    @Test
    void aDirectoryNameKeepsTheDraftUnbound() throws Exception {
        Files.createDirectory(workspace.resolve("archive.old"));
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST, proposal("draft", STORY));
            Run directory = conversation.run("archive.old");
            assertTrue(directory.answer().contains("existing directory"), directory.answer());
            assertEquals("", requireDraft(conversation).destination());
            assertFalse(directory.answer().contains("Save draft"), directory.answer());
            assertEquals(0, conversation.approvals.get());
        }
    }

    @Test
    void filenameOfAnExistingFileRequiresTheExplicitSelection() throws Exception {
        Files.writeString(workspace.resolve("story.txt"), "Previous story.\n");
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST, proposal("draft", STORY));
            Run bound = conversation.run("story.txt");
            assertTrue(bound.requests().isEmpty());
            assertEquals(0, conversation.approvals.get(), bound.answer());
            assertEquals("Previous story.\n", Files.readString(workspace.resolve("story.txt")));
            PendingContentDraft draft = requireDraft(conversation);
            assertEquals("story.txt", draft.destination());
            assertTrue(bound.answer().contains("already exists"), bound.answer());
            assertTrue(bound.answer().contains("Save draft " + draft.id()), bound.answer());
            Run saved = conversation.run("Save draft " + draft.id());
            assertEquals(1, conversation.approvals.get(), saved.answer());
            assertEquals(STORY, Files.readString(workspace.resolve("story.txt")));
        }
    }

    @Test
    void refusedDraftIsRecordedInTheTraceForDiagnosis() throws Exception {
        String content = "Lina’s clock stood by the 海 with api_key=abcdef1234567890abcdef1234567890.";
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                    conversation.display, false, false, 96, new Redactor());
            Run rejected = conversation.run(NAMED_REQUEST, proposal("draft", content));
            assertNull(conversation.memory.pendingContentDraft());
            var warning = rejected.turn().audit().localTrace().warnings().stream()
                    .filter(w -> w.code().equals("CONTENT_DRAFT_REJECTED")).findFirst().orElseThrow();
            assertTrue(warning.message().contains("U+6D77"), warning.message());
            assertTrue(warning.message().contains("Lina"), warning.message());
            assertTrue(warning.message().contains("sha256:"), warning.message());
            assertFalse(warning.message().contains("abcdef1234567890"), warning.message());
            assertFalse(rejected.answer().contains("sha256:"), rejected.answer());
        }
    }

    @Test
    void filenameTheDisplayCannotShowKeepsTheDraft() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                    conversation.display, false, false, 96, new Redactor());
            conversation.run(REQUEST, proposal("draft", STORY));
            PendingContentDraft draft = requireDraft(conversation);
            Run unshowable = conversation.run("café.txt");
            assertTrue(unshowable.requests().isEmpty());
            assertEquals(0, conversation.approvals.get());
            assertTrue(unshowable.answer().contains("filename cannot be shown"), unshowable.answer());
            assertSame(draft, conversation.memory.pendingContentDraft());
            Run saved = conversation.run("story.txt");
            assertEquals(1, conversation.approvals.get(), saved.answer());
            assertArrayEquals(STORY.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(workspace.resolve("story.txt")));
        }
    }

    @Test
    void filenameSelectionKeepsTheShownDraftIdentity() throws Exception {
        var resolution = dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(REQUEST);
        var draft = new PendingContentDraft(resolution,
                dev.talos.runtime.context.ContentDraftContinuation.unresolvedOperation(resolution),
                workspace.toAbsolutePath().normalize().toString(), 1, 6, "abcdef123456", "", STORY);
        var decision = dev.talos.runtime.context.ContentDraftContinuation.evaluate(
                dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest("story.txt"), draft, workspace, 2,
                CapabilityPosture.AGENT, ContentRestrictions.UNRESTRICTED);
        var selected = decision.resolution().intent().contentSaveRequest().draftSelection();
        assertNotNull(selected);
        assertEquals("abcdef123456", selected.id());
        assertEquals("story.txt", selected.destination());
    }

    @Test
    void clarificationQuestionWithTypographicPunctuationIsShownOnAPlainDisplay() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                    conversation.display, false, false, 96, new Redactor());
            Run asked = conversation.run(REQUEST, proposal("question", "Which season should Lina’s story use?"));
            assertTrue(asked.answer().contains("Which season should Lina's story use?"), asked.answer());
            assertFalse(asked.answer().contains("could not be presented"), asked.answer());
            assertNull(conversation.memory.pendingContentDraft());
            assertNoWrite(conversation);
        }
    }

    @Test
    void unshowableClarificationQuestionIsRefusedAsAQuestion() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                    conversation.display, false, false, 96, new Redactor());
            Run asked = conversation.run(REQUEST, proposal("question", "Should the story mention the 海?"));
            assertTrue(asked.answer().contains("clarification question could not be shown"), asked.answer());
            assertTrue(asked.answer().contains("U+6D77"), asked.answer());
            assertFalse(asked.answer().contains("complete draft"), asked.answer());
            assertNoWrite(conversation);
        }
    }

    @Test
    void namedDestinationDraftStillNeedsAnExplicitSelection() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            Run proposed = conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            PendingContentDraft draft = requireDraft(conversation);
            assertTrue(proposed.answer().contains("Save draft " + draft.id()), proposed.answer());
            Run filename = conversation.run("story.txt", "No file was written.");
            assertEquals(0, conversation.approvals.get(), filename.answer());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admittedUnicodeDisplayAndSelectedBytesAgree(boolean streaming) throws Exception {
        String content = "Lina\u2019s clock.\nTwo  spaces.\nA tab\there.\nCaf\u00e9 beside the sea.\n";
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.streaming = streaming;
            conversation.run(NAMED_REQUEST, proposal("draft", content));
            PendingContentDraft draft = requireDraft(conversation);
            String shown = conversation.display.toString(StandardCharsets.UTF_8);
            for (String line : content.split("\n")) assertTrue(shown.contains(line), shown);
            assertFalse(shown.contains("Typographic quotes"), shown);
            Run saved = conversation.run("Save draft " + draft.id());
            assertTrue(saved.requests().isEmpty());
            assertEquals(1, conversation.approvals.get());
            assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(workspace.resolve("story.txt")));
            assertNull(conversation.memory.pendingContentDraft());
        }
    }

    @Test
    void missingDisplayAdmissionCannotRetainGeneratedContent() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.presentationAvailable = false;
            conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            assertNull(conversation.memory.pendingContentDraft());
            assertNoWrite(conversation);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void promptDebugAndDraftUseSeparateRealDisplayBoundaries(boolean styled) throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.streaming = true;
            conversation.renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                    conversation.display, true, styled, 96, new Redactor());
            conversation.uiSession = new dev.talos.cli.repl.SessionState() {
                public int getK() { return 4; }
                public void setK(int value) {}
                public boolean isDebug() { return true; }
                public void setDebug(boolean enabled) {}
                public dev.talos.cli.repl.DebugLevel getDebugLevel() { return dev.talos.cli.repl.DebugLevel.PROMPT; }
            };
            conversation.run(REQUEST, proposal("draft", STORY));
            String promptAudit = dev.talos.spi.types.PromptDebugCapture.latest().orElseThrow()
                    .diagnostics().get("promptAudit");
            assertNotNull(promptAudit);
            assertTrue(promptAudit.contains("Prompt Audit"), promptAudit);
            assertEquals(STORY, requireDraft(conversation).content());
            Run saved = conversation.run("story.txt");
            String shown = conversation.display.toString(StandardCharsets.UTF_8).replaceAll("\\u001B\\[[;\\d]*m", "");
            assertFalse(shown.contains("Prompt Audit"), "Prompt diagnostics must remain outside the answer pane: " + shown);
            assertTrue(shown.contains("reply with the filename only"), shown);
            assertTrue(shown.contains("Seven minutes slow."), shown);
            assertFalse(shown.contains("display omitted"), shown);
            assertTrue(saved.requests().isEmpty());
            assertEquals(1, conversation.approvals.get());
            assertArrayEquals(STORY.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(workspace.resolve("story.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "width", "missing", "encoding"})
    void changedDisplayIsRecheckedBeforeApprovalAndConsumesSelection(String change) throws Exception {
        String content = change.equals("width") ? "x".repeat(70)
                : change.equals("encoding") ? "Lina saw \u6d77\u8fb9." : "Lina\u2019s clock stayed slow.";
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(NAMED_REQUEST, proposal("draft", content));
            String id = requireDraft(conversation).id();
            if (change.equals("missing")) conversation.presentationAvailable = false;
            else conversation.renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                    conversation.display, !change.equals("ascii"), false,
                    change.equals("width") ? 48 : 96, new Redactor(),
                    change.equals("encoding") ? java.nio.charset.Charset.forName("windows-1252") : StandardCharsets.UTF_8);
            Run blocked = conversation.run("Save draft " + id);
            assertTrue(blocked.requests().isEmpty());
            assertTrue(blocked.answer().contains("No write was attempted"), blocked.answer());
            assertNotEquals("COMPLETE", blocked.turn().audit().localTrace().outcome().status());
            assertNull(conversation.memory.pendingContentDraft());
            assertNoWrite(conversation);
            Run stale = conversation.run("Save draft " + id);
            assertTrue(stale.requests().isEmpty());
            assertTrue(stale.answer().contains("No matching live draft"), stale.answer());
            assertNoWrite(conversation);
        }
    }

    @Test
    void filenameSaveIsBlockedAfterDisplayBecomesLossy() throws Exception {
        try (var conversation = new Conversation(ApprovalResponse.APPROVED)) {
            conversation.run(REQUEST, proposal("draft", "Lina\u2019s clock stayed slow."));
            assertEquals("Lina\u2019s clock stayed slow.", requireDraft(conversation).content());
            conversation.renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                    conversation.display, false, false, 96, new Redactor());
            Run bound = conversation.run("story.txt");
            assertTrue(bound.requests().isEmpty());
            assertNull(conversation.memory.pendingContentDraft());
            assertFalse(bound.answer().contains("Save draft "), bound.answer());
            assertTrue(bound.answer().contains("No write was attempted"), bound.answer());
            assertNoWrite(conversation);
        }
    }

    @ParameterizedTest
    @MethodSource("rejectedDraftOutputs")
    void invalidTruncatedToolBearingRedactedQuestionAndClippedOutputCannotBeRetained(boolean schemaSupported, String kind) throws Exception {
        try (var conversation = new Conversation(schemaSupported, ApprovalResponse.APPROVED)) {
            String json = proposal("draft", STORY);
            LlmClient.StreamResult response = switch (kind) {
                case "invalid" -> new LlmClient.StreamResult("{\"kind\":\"draft\",\"text\":\"incomplete", List.of());
                case "fenced" -> new LlmClient.StreamResult("```json\n" + json + "\n```", List.of());
                case "length" -> new LlmClient.StreamResult(json, List.of(), "length");
                case "native" -> new LlmClient.StreamResult(json, List.of(new ChatMessage.NativeToolCall("bad-write",
                        "talos.write_file", Map.of("path", "story.txt", "content", STORY))));
                case "text-tool" -> new LlmClient.StreamResult(JSON.writeValueAsString(Map.of(
                        "name", "talos.write_file", "arguments", Map.of("path", "story.txt", "content", STORY))), List.of());
                case "oversized-characters" -> new LlmClient.StreamResult(proposal("draft", "x".repeat(8193)), List.of());
                case "oversized-bytes" -> new LlmClient.StreamResult(proposal("draft", "\u4E2D".repeat(5462)), List.of());
                case "redacted" -> {
                    String content = "api_key=abcdef1234567890abcdef1234567890";
                    assertNotEquals(content, new Redactor().redactBlock(content), "The fixture must exercise actual redaction");
                    yield new LlmClient.StreamResult(proposal("draft", content), List.of());
                }
                case "question" -> new LlmClient.StreamResult(proposal("question", "Which season should be used?"), List.of());
                case "presentation" -> {
                    conversation.config.data.put("limits", Map.of("response_max_chars", 100));
                    yield new LlmClient.StreamResult(json, List.of());
                }
                default -> throw new AssertionError(kind);
            };
            Run rejected = conversation.runResponses(NAMED_REQUEST, List.of(response));
            assertGeneration(rejected, NAMED_REQUEST, schemaSupported);
            assertNull(conversation.memory.pendingContentDraft());
            assertNoWrite(conversation);
        }
    }

    static Stream<Arguments> rejectedDraftOutputs() {
        return Stream.of(true, false).flatMap(schemaSupported -> Stream.of(
                "invalid", "fenced", "length", "native", "text-tool", "oversized-characters", "oversized-bytes",
                "redacted", "question", "presentation").map(kind -> Arguments.of(schemaSupported, kind)));
    }

    private static void assertGeneration(Run run, String originalRequest) {
        assertGeneration(run, originalRequest, true);
    }

    private static void assertGeneration(Run run, String originalRequest, boolean schemaSupported) {
        assertTrue(dev.talos.runtime.context.ContentDraftContinuation.unresolvedOperation(
                dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(originalRequest)) >= 0,
                "The fixture must reach unresolved draft admission through the actual resolver");
        assertEquals(1, run.requests().size());
        ChatRequest sent = run.requests().getFirst();
        assertTrue(sent.tools.isEmpty());
        assertEquals(ToolChoiceMode.NONE, sent.controls.toolChoice());
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, sent.controls.toolAuthorityMode());
        assertTrue(sent.controls.singleAttempt());
        assertEquals(SamplingControls.none(), sent.controls.sampling());
        assertEquals(0, sent.controls.maxOutputTokens());
        assertEquals(schemaSupported ? ResponseFormatMode.JSON_SCHEMA : ResponseFormatMode.TEXT, sent.controls.responseFormat());
        if (schemaSupported) {
            assertEquals(ContentDraftProposal.SCHEMA, sent.controls.jsonSchema());
            var schema = assertDoesNotThrow(() -> JSON.readTree(sent.controls.jsonSchema()));
            assertFalse(schema.path("properties").path("text").has("maxLength"),
                    "Decoder size bounds must not expand the provider generation grammar");
        } else {
            assertTrue(sent.controls.jsonSchema().isEmpty());
            assertTrue(sent.messages.getFirst().content().contains(ContentDraftProposal.SCHEMA),
                    "The schema-unsupported branch must receive the strict JSON shape in its system prompt");
        }
        assertEquals(List.of("system", "user"), sent.messages.stream().map(ChatMessage::role).toList());
        assertEquals(originalRequest, sent.messages.getLast().content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Do not save it.", "Cancel it and read notes.txt.", "Read notes.txt and cancel it.", "Read notes.txt. Cancel the pending save."})
    void revocationClearsTheDraftWithoutDiscardingIndependentReads(String request) throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "Existing notes.");
        try (var conversation = new Conversation()) {
            conversation.run(NAMED_REQUEST, proposal("draft", STORY));
            String id = requireDraft(conversation).id();
            conversation.runResponses(request, List.of(new LlmClient.StreamResult("No file was written.", List.of())));
            assertNull(conversation.memory.pendingContentDraft());
            var stale = conversation.run("Save draft " + id);
            assertTrue(stale.requests().isEmpty());
            assertEquals(0, conversation.approvals.get());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
            assertEquals("Existing notes.", Files.readString(workspace.resolve("notes.txt")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"If needed, cancel it.", "The guide says, cancel it.", "Cancel it, if needed.", "Explain the words \"cancel it\"."})
    void conditionalReportedAndQuotedCancellationCannotBecomeDirectCommands(String request) throws Exception {
        var resolution = dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(NAMED_REQUEST);
        var draft = new PendingContentDraft(resolution,
                dev.talos.runtime.context.ContentDraftContinuation.unresolvedOperation(resolution),
                workspace.toAbsolutePath().normalize().toString(), 1, 6, "abcdef123456", "story.txt", STORY);
        var decision = dev.talos.runtime.context.ContentDraftContinuation.evaluate(
                dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(request), draft, workspace, 2,
                CapabilityPosture.AGENT, ContentRestrictions.UNRESTRICTED);
        assertSame(draft, decision.next());
        assertFalse(decision.handled());
    }

    @Test
    void workspaceMismatchAloneInvalidatesAnOtherwiseLiveSelection() throws Exception {
        var resolution = dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest(NAMED_REQUEST);
        var draft = new PendingContentDraft(resolution,
                dev.talos.runtime.context.ContentDraftContinuation.unresolvedOperation(resolution),
                workspace.toAbsolutePath().normalize().toString(), 1, 6, "abcdef123456", "story.txt", STORY);
        Path other = Files.createDirectory(temp.resolve("other"));
        var selection = dev.talos.runtime.task.TaskContractResolver.resolveFromUserRequest("Save draft " + draft.id());
        var decision = dev.talos.runtime.context.ContentDraftContinuation.evaluate(selection, draft, other, 2,
                CapabilityPosture.AGENT, ContentRestrictions.UNRESTRICTED);
        assertNull(decision.next());
        assertTrue(decision.directAnswer().contains("No matching live draft"));
        assertNull(decision.resolution().intent().contentSaveRequest().draftSelection());
    }

    private PendingContentDraft requireDraft(Conversation conversation) {
        PendingContentDraft draft = conversation.memory.pendingContentDraft();
        assertNotNull(draft);
        return draft;
    }

    private void assertNoWrite(Conversation conversation) {
        assertEquals(0, conversation.approvals.get());
        assertFalse(Files.exists(workspace.resolve("story.txt")));
        assertFalse(Files.exists(workspace.resolve("notes.txt")));
    }

    private static String proposal(String kind, String text) throws Exception {
        return JSON.writeValueAsString(Map.of("kind", kind, "text", text));
    }

    private record Run(TurnResult turn, String answer, List<ChatRequest> requests) {}

    private final class Conversation implements AutoCloseable {
        private final java.io.ByteArrayOutputStream display = new java.io.ByteArrayOutputStream();
        private dev.talos.cli.repl.RenderEngine renderer = dev.talos.cli.repl.ExactAnswerRenderFixture.create(
                display, true, false, 96, new Redactor());
        private boolean presentationAvailable = true;
        private boolean streaming;
        private dev.talos.cli.repl.SessionState uiSession;
        private final Config config = new Config(null);
        private final SessionMemory memory = new SessionMemory();
        private final ToolRegistry registry = new ToolRegistry();
        private final AtomicInteger approvals = new AtomicInteger();
        private final List<String> approvalDetails = new ArrayList<>();
        private final List<ApprovalResponse> decisions;
        private final boolean schemaSupported;
        private final Session session;
        private final TurnProcessor processor;

        private Conversation(ApprovalResponse... decisions) {
            this(true, decisions);
        }

        private Conversation(boolean schemaSupported, ApprovalResponse... decisions) {
            this.schemaSupported = schemaSupported;
            this.decisions = List.of(decisions);
            registry.register(new FileWriteTool());
            registry.register(new ReadFileTool());
            var gate = new ApprovalGate() {
                @Override public boolean approve(String description, String detail) { return approveFull(description, detail).isApproved(); }
                @Override public ApprovalResponse approveFull(String description, String detail) {
                    approvalDetails.add(detail);
                    int index = approvals.getAndIncrement();
                    return index < Conversation.this.decisions.size() ? Conversation.this.decisions.get(index) : ApprovalResponse.DENIED;
                }
            };
            var mode = new UnifiedAssistantMode();
            processor = new TurnProcessor((request, root, context) -> mode.handle(request, root, (Context) context),
                    gate, registry, ApprovalPolicy.ALWAYS_ASK);
            processor.addListener(new MemoryUpdateListener(new ConversationManager(memory, TokenBudget.fromConfig(config)), null, memory));
            processor.addListener(new dev.talos.cli.repl.ActiveTaskContextUpdateListener(memory));
            session = new Session(workspace, config, memory);
        }

        private Run run(String request, String... responses) throws Exception {
            return runResponses(request, Arrays.stream(responses).map(response -> new LlmClient.StreamResult(response, List.of())).toList());
        }

        private Run runResponses(String request, List<LlmClient.StreamResult> responses) throws Exception {
            return runIn(session, request, responses);
        }

        private Run runIn(Session currentSession, String request, List<LlmClient.StreamResult> responses) throws Exception {
            List<LlmClient.StreamResult> scripted = responses.isEmpty()
                    ? List.of(new LlmClient.StreamResult("Unused provider response.", List.of())) : responses;
            var fixture = schemaSupported
                    ? ScriptedNativeLlmClient.recordingWithJsonSchemaCapability(scripted, 16_384, true)
                    : ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(scripted, 16_384);
            try (var client = fixture.client()) {
                assertEquals(schemaSupported, client.supportsJsonSchemaResponse(),
                        "The fixture must exercise the declared provider capability branch");
                client.setToolSpecs(registry.descriptors().stream()
                        .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.parametersSchema())).toList());
                var context = Context.builder(config).llm(client).memory(memory).toolRegistry(registry)
                        .session(uiSession)
                        .sandbox(new Sandbox(currentSession.workspace(), Map.of()))
                        .exactAnswerPresentation(presentationAvailable ? renderer.exactAnswerPresentation() : null)
                        .streamSink(streaming ? new ToolCallStreamFilter(renderer.answerStreamSink(null)) : null)
                        .toolCallLoop(new ToolCallLoop(processor, 4)).build();
                var turn = processor.process(currentSession, request, context);
                renderer.render(turn.result());
                String answer = turn.result() instanceof Result.Ok ok ? ok.text
                        : turn.result() instanceof Result.Streamed streamed ? streamed.fullText : turn.result().toString();
                return new Run(turn, answer, List.copyOf(fixture.requests()));
            }
        }

        @Override public void close() { session.close(); }
    }
}
