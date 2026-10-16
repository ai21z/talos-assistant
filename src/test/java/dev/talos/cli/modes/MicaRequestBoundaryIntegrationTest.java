package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.ActiveTaskContextUpdateListener;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.RenderEngine;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.MemoryUpdateListener;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnTraceCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.intent.RequestedResultInventory.EvidenceBasis;
import dev.talos.runtime.intent.RequestedResultInventory.Kind;
import dev.talos.runtime.intent.RequestedResultInventory.RequestedResult;
import dev.talos.runtime.intent.RequestedResultInventory.SourceRegion;
import dev.talos.runtime.observation.TurnRequestFrame;
import dev.talos.runtime.observation.TurnRequestFrame.Action;
import dev.talos.runtime.observation.TurnRequestFrame.Meaning;
import dev.talos.runtime.observation.TurnRequestFrame.Publication;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolError;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Authored meanings and provider responses are exercised through real process and tool boundaries. */
@Timeout(30)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicaRequestBoundaryIntegrationTest {
    private static final Set<Action> DIRECTORY_ACCESS = Set.of(Action.METADATA, Action.ENUMERATE);
    private static final String LIST = "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\",\"max_depth\":1}}";
    private static final String STORY = "Mara crossed the medieval bridge.\n";
    private static final String WRITE = "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"story.txt\","
            + "\"content\":\"Mara crossed the medieval bridge.\\n\"}}";
    private static final String READ = "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"story.txt\"}}";

    @TempDir Path temp;
    private Path workspace;
    private String previousHome;

    @BeforeEach
    void isolate() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(temp.resolve("home")).toString());
    }

    @AfterEach
    void clear() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTraceCapture.clear();
        ProviderResponseCapture.clear();
        PromptDebugCapture.clear();
        LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void locationWithPinnedTokenRequiresNoWorkspaceAcquisition() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "unadmitted-readme-content-7c12\n");
        String unrelatedPath = temp.resolve("unadmitted-private-location-94c2").toString();
        try (var conversation = new Conversation((frame, task) -> meaning(frame, Set.of(),
                Publication.NO_NAMES, true, false, Kind.WORKSPACE_LOCATION))) {
            Run run = conversation.run("Where is the workspace? Do not inspect files, including @README.md.",
                    "unadmitted-provider-output-9d21 " + unrelatedPath);
            assertTrue(run.answer().contains(workspace.toString()), run.answer());
            assertTrue(run.terminalAnswer().contains(workspace.toString()), run.terminalAnswer());
            assertNoPhysicalObservation(conversation.lastFrame());
            assertEquals(0, successes(run, "talos.read_file"));
            assertAbsent(run, "README.md", "unadmitted-readme-content-7c12", "unadmitted-provider-output-9d21", unrelatedPath);
            assertParity(run);
        }
    }

    @Test
    void settledRestrictionsPreserveDirectoryResultsBesideUnsupportedMeaning() throws Exception {
        Files.writeString(workspace.resolve("public.txt"), "unrequested body");
        try (var conversation = new Conversation((frame, task) -> {
            var selected = meaning(frame, DIRECTORY_ACCESS, Publication.ALL_NAMES, true, false,
                    Kind.WORKSPACE_LOCATION, Kind.DIRECTORY_CONTENTS);
            int start = frame.originalRequest().indexOf("Also");
            return new Meaning(new RequestedResultInventory(frame.originalRequest(), selected.inventory().results(),
                    List.of(new SourceRegion(start, frame.originalRequest().length()))),
                    selected.acquisitions(), selected.publication(), true, false);
        })) {
            Run run = conversation.run("Where is this workspace and what is in this folder? Also assess licensing.",
                    LIST, "An invented project description.");
            assertEquals(1, successes(run, "talos.list_dir"), run.answer());
            assertTrue(run.answer().contains("public.txt"), run.answer());
            assertTrue(run.terminalAnswer().contains(workspace.toString()), run.terminalAnswer());
            assertTrue(run.answer().toLowerCase().contains("unresolved"), run.answer());
            assertFalse(run.answer().contains("An invented project description"), run.answer());
            assertEquals(0, successes(run, "talos.read_file"));
            assertParity(run);
        }
    }

    @Test
    void admittedLocationSurvivesMissingProjectPurposeWithoutAcquiringSources() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "unadmitted-purpose-body-82");
        try (var conversation = new Conversation((frame, task) -> {
            var region = new SourceRegion(0, frame.originalRequest().length());
            var results = List.of(
                    new RequestedResult("location-x", region, Kind.WORKSPACE_LOCATION,
                            "active workspace", "current turn", EvidenceBasis.TURN_WORKSPACE),
                    new RequestedResult("purpose-y", region, Kind.PROJECT_PURPOSE,
                            "active workspace", "current turn", EvidenceBasis.ADMITTED_PROJECT_SOURCES));
            return new Meaning(new RequestedResultInventory(frame.originalRequest(), results, List.of()),
                    Set.of(), Publication.NO_NAMES, true, false);
        })) {
            Run run = conversation.run("Where is this workspace and what does this project do? Do not open any files.",
                    "Invented source explanation.");
            assertTrue(run.terminalAnswer().contains(workspace.toString()), run.terminalAnswer());
            assertTrue(run.answer().toLowerCase().contains("purpose"), run.answer());
            assertTrue(run.answer().toLowerCase().contains("unresolved"), run.answer());
            assertNoPhysicalObservation(conversation.lastFrame());
            assertAbsent(run, "unadmitted-purpose-body-82", "Invented source explanation");
            assertParity(run);
        }
    }

    @Test
    void inactiveResultsBeforeLocationPreserveTheExactTerminalField() throws Exception {
        try (var conversation = new Conversation((frame, task) -> {
            var region = new SourceRegion(0, frame.originalRequest().length());
            var cancelled = new RequestedResult("old-list", region, Kind.DIRECTORY_CONTENTS,
                    "active workspace", "top-level, observation time", EvidenceBasis.SCOPED_DIRECTORY_OBSERVATION,
                    RequestedResultInventory.Disposition.CANCELLED, "");
            var location = new RequestedResult("path-new", region, Kind.WORKSPACE_LOCATION,
                    "active workspace", "current turn", EvidenceBasis.TURN_WORKSPACE);
            return new Meaning(new RequestedResultInventory(frame.originalRequest(), List.of(cancelled, location), List.of()),
                    Set.of(), Publication.NO_NAMES, true, false);
        })) {
            Run run = conversation.run("List the folder. Cancel that, only tell me where the workspace is.");
            assertTrue(run.terminalAnswer().contains(workspace.toString()), run.terminalAnswer());
            assertTrue(run.answer().contains("cancelled"), run.answer());
            assertNoPhysicalObservation(conversation.lastFrame());
            assertParity(run);
        }
    }

    @Test
    void defaultInferredLocationRetainsGenericTerminalPathMasking() throws Exception {
        String unrelatedPath = temp.resolve("unrelated-private-default-7e31").toString();
        try (var conversation = new Conversation(null)) {
            Run run = conversation.run("Where is this workspace?", "Unused provider location " + unrelatedPath);
            assertTrue(run.answer().contains(workspace.toString()), run.answer());
            assertFalse(run.terminal().contains(workspace.toString()), run.terminal());
            assertTrue(run.terminalAnswer().contains("[path]"), run.terminalAnswer());
            assertAbsent(run, unrelatedPath, "Unused provider location");
            assertTrue(conversation.frames.isEmpty());
            assertParity(run);
        }
    }

    @Test
    void permittedListingUsesActualDirectoryEvidenceAndKeepsUsefulNames() throws Exception {
        Files.writeString(workspace.resolve("alpha.txt"), "alpha-content-not-requested\n");
        Files.writeString(workspace.resolve("beta.txt"), "beta-content-not-requested\n");
        try (var ordinary = new Conversation(null)) {
            Run baseline = ordinary.run("List the entries in this workspace.", LIST, "alpha.txt and beta.txt.");
            assertEquals(1, successes(baseline, "talos.list_dir"), baseline.answer());
            assertTrue(baseline.answer().contains("alpha.txt"), baseline.answer());
            assertTrue(baseline.answer().contains("beta.txt"), baseline.answer());
            assertTrue(ordinary.frames.isEmpty(), "The default path must not invoke a controlled source");
            assertNull(TurnRequestFrame.current());
        }
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.ALL_NAMES, false, false, Kind.DIRECTORY_CONTENTS))) {
            Run run = conversation.run("List the entries in this workspace.", LIST, "Provider list prose.");
            assertEquals(1, successes(run, "talos.list_dir"), run.answer());
            assertTrue(run.answer().contains("alpha.txt"), run.answer());
            assertTrue(run.answer().contains("beta.txt"), run.answer());
            assertDirectoryObservation(conversation.lastFrame());
            assertAbsent(run, "alpha-content-not-requested", "beta-content-not-requested");
            assertParity(run);
        }
    }

    @Test
    void exactAllEntryCountDoesNotPublishAcquiredNames() throws Exception {
        Files.writeString(workspace.resolve("alpha-private-label.txt"), "alpha\n");
        Files.writeString(workspace.resolve("beta-private-label.txt"), "beta\n");
        Files.writeString(workspace.resolve(".hidden-private-label"), "hidden\n");
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_COUNT))) {
            Run run = conversation.run("Count all top-level entries in this workspace without showing their names.",
                    LIST, "There are 99 entries: alpha-private-label.txt and beta-private-label.txt.");
            assertEquals(1, successes(run, "talos.list_dir"), run.answer());
            assertTrue(run.answer().matches("(?s).*\\b3\\b.*"), run.answer());
            assertFalse(run.answer().matches("(?s).*\\b99\\b.*"), run.answer());
            assertAbsent(run, "alpha-private-label.txt", "beta-private-label.txt", ".hidden-private-label");
            assertFalse(run.providerInputs().contains("alpha-private-label.txt"), run.providerInputs());
            assertFalse(run.providerInputs().contains(".hidden-private-label"), run.providerInputs());
            assertDirectoryObservation(conversation.lastFrame());
            assertParity(run);
        }
    }

    @Test
    void completeEmptyObservationSupportsEmptinessWithoutSourceReads() throws Exception {
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_EMPTINESS))) {
            Run run = conversation.run("Is this workspace empty?", LIST, "It has many files.");
            assertEquals(1, successes(run, "talos.list_dir"), run.answer());
            assertTrue(run.answer().toLowerCase().contains("empty"), run.answer());
            assertFalse(run.answer().toLowerCase().contains("not empty"), run.answer());
            assertEquals(0, successes(run, "talos.read_file"));
            assertDirectoryObservation(conversation.lastFrame());
            assertAbsent(run, "many files");
            assertParity(run);
        }
    }

    @Test
    void deniedProposalCannotRestoreProviderProseAfterAnAdmittedCount() throws Exception {
        Files.writeString(workspace.resolve("private-child-fable-711.txt"), "private body");
        String forbiddenWrite = "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"private-child-fable-711.txt\",\"content\":\"changed\"}}";
        String invented = "There are 99 entries including private-child-fable-711.txt.";
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_COUNT))) {
            Run run = conversation.run("Count the workspace entries without showing their names.",
                    LIST, forbiddenWrite, invented);
            assertEquals(1, successes(run, "talos.list_dir"));
            assertEquals(0, successes(run, "talos.write_file"));
            assertEquals(0, conversation.approvals.get());
            assertTrue(run.answer().contains("count is 1"), run.answer());
            assertAbsent(run, "99", "private-child-fable-711.txt", "changed");
            assertEquals("private body", Files.readString(workspace.resolve("private-child-fable-711.txt")));
            assertTrue(run.turn().audit().toolCalls().stream().anyMatch(call ->
                    call.name().equals("talos.write_file") && !call.success()));
            assertParity(run);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DIRECTORY_COUNT", "DIRECTORY_EMPTINESS"})
    void resultWithoutNamesRejectsAConflictingPublicationProposal(String kind) throws Exception {
        Files.writeString(workspace.resolve("unrequested-provider-name-713.txt"), "private");
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.ALL_NAMES, false, false, Kind.valueOf(kind)))) {
            Run run = conversation.run("Only provide the count or emptiness result for the workspace.", LIST, "Unused prose.");
            assertEquals(0, successes(run, "talos.list_dir"));
            assertNoPhysicalObservation(conversation.lastFrame());
            assertAbsent(run, "unrequested-provider-name-713.txt");
            assertFalse(run.providerInputs().contains("unrequested-provider-name-713.txt"));
            assertTrue(run.answer().toLowerCase().contains("clarif"), run.answer());
        }
    }

    @Test
    void hiddenNameRestrictionPreservesPermittedVisibleNamesAcrossPresentation() throws Exception {
        Files.writeString(workspace.resolve("public.txt"), "public content\n");
        Path hidden = Files.writeString(workspace.resolve(".confidential-name-7c21"), "private content\n");
        if (!Files.isHidden(hidden)) Files.setAttribute(hidden, "dos:hidden", true);
        assertTrue(Files.isHidden(hidden), "The fixture must be hidden under the current platform rules");
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.VISIBLE_NAMES, false, false, Kind.DIRECTORY_CONTENTS))) {
            Run run = conversation.run("List the workspace entries, but do not reveal hidden names.",
                    LIST, "public.txt and .confidential-name-7c21 are here.");
            assertEquals(1, successes(run, "talos.list_dir"), run.answer());
            assertTrue(run.answer().contains("public.txt"), run.answer());
            assertAbsent(run, ".confidential-name-7c21", "private content");
            assertFalse(run.providerInputs().contains(".confidential-name-7c21"), run.providerInputs());
            assertDirectoryObservation(conversation.lastFrame());
            assertParity(run);
        }
    }

    @Test
    void hiddenInformationRestrictionsDoNotPublishAllEntryFacts() throws Exception {
        Files.writeString(workspace.resolve("public.txt"), "public body");
        Files.writeString(workspace.resolve(".hidden-secret-83"), "private body");
        try (var conversation = new Conversation((frame, task) -> {
            var selected = meaning(frame, DIRECTORY_ACCESS, Publication.VISIBLE_NAMES, false, false,
                    Kind.DIRECTORY_CONTENTS, Kind.DIRECTORY_COUNT, Kind.DIRECTORY_EMPTINESS);
            return new Meaning(selected.inventory(), selected.acquisitions(), selected.publication(), false, false,
                    new dev.talos.spi.WorkspaceObservationBoundary.Fields(false, false, false, false));
        })) {
            Run run = conversation.run("Show visible entries, keep all hidden information, counts and existence private.",
                    LIST, "There are 2 entries and the folder is not empty, including .hidden-secret-83.");
            assertTrue(run.answer().contains("public.txt"), run.answer());
            assertFalse(run.answer().contains("count is") || run.answer().contains("not empty"), run.answer());
            assertTrue(run.answer().contains("withheld"), run.answer());
            assertAbsent(run, ".hidden-secret-83", "private body", "There are 2");
            assertFalse(run.providerInputs().contains(".hidden-secret-83"), run.providerInputs());
            assertParity(run);
        }
    }

    @Test
    void unrequestedEntryTypesAreNotPublished() throws Exception {
        Files.createDirectory(workspace.resolve("public-folder"));
        try (var conversation = new Conversation((frame, task) -> {
            var selected = meaning(frame, DIRECTORY_ACCESS, Publication.ALL_NAMES, false, false, Kind.DIRECTORY_CONTENTS);
            return new Meaning(selected.inventory(), selected.acquisitions(), selected.publication(), false, false,
                    new dev.talos.spi.WorkspaceObservationBoundary.Fields(false, false, false, true));
        })) {
            Run run = conversation.run("Show entry names but no types or count and do not say whether it is empty.", LIST);
            assertTrue(run.answer().contains("public-folder"), run.answer());
            assertFalse(run.answer().contains("public-folder/"), run.answer());
            assertFalse(run.answer().contains("not empty") || run.answer().contains("count is"), run.answer());
            assertParity(run);
        }
    }

    @Test
    void replacementOfListingWithEmptinessDoesNotResurrectNames() throws Exception {
        Files.writeString(workspace.resolve("cancelled-list-name-3b17.txt"), "fixture\n");
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_EMPTINESS))) {
            Run run = conversation.run("List this workspace. Actually, replace that with whether it is empty only.",
                    LIST, "cancelled-list-name-3b17.txt is present.");
            assertEquals(1, successes(run, "talos.list_dir"), run.answer());
            assertTrue(run.answer().toLowerCase().contains("not empty"), run.answer());
            assertAbsent(run, "cancelled-list-name-3b17.txt");
            assertParity(run);
        }
    }

    @Test
    void explicitReplacementKeepsItsDecisionWithoutAcquiringOrShowingTheOldResult() throws Exception {
        Files.writeString(workspace.resolve("replaced-private-name.txt"), "private body");
        try (var conversation = new Conversation((frame, task) -> {
            var region = new SourceRegion(0, frame.originalRequest().length());
            var old = new RequestedResult("old-names", region, Kind.DIRECTORY_CONTENTS, "active workspace",
                    "top-level, observation time", EvidenceBasis.SCOPED_DIRECTORY_OBSERVATION,
                    RequestedResultInventory.Disposition.REPLACED, "new-empty");
            var replacement = new RequestedResult("new-empty", region, Kind.DIRECTORY_EMPTINESS, "active workspace",
                    "top-level, observation time", EvidenceBasis.SCOPED_DIRECTORY_OBSERVATION);
            return new Meaning(new RequestedResultInventory(frame.originalRequest(), List.of(old, replacement), List.of()),
                    DIRECTORY_ACCESS, Publication.NO_NAMES, false, false);
        })) {
            Run run = conversation.run("List the folder. Actually replace that with whether it is empty only.", LIST);
            assertTrue(run.answer().contains("replaced") && run.answer().contains("not empty"), run.answer());
            assertEquals(1, successes(run, "talos.list_dir"));
            assertAbsent(run, "replaced-private-name.txt", "private body");
            assertFalse(run.providerInputs().contains("replaced-private-name.txt"));
            assertParity(run);
        }
    }

    @Test
    void unresolvedWholeScopePublishesNeitherLocationNorNamesOrProviderText() throws Exception {
        Files.writeString(workspace.resolve("private-scope-name-5e13.txt"), "private-scope-body-9c31\n");
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.ALL_NAMES, true, true, Kind.WORKSPACE_LOCATION, Kind.DIRECTORY_CONTENTS))) {
            Run run = conversation.run("Where is this workspace and what is in it? Keep that private.",
                    LIST, "private-scope-provider-4a19 " + workspace + " private-scope-name-5e13.txt");
            assertNoPhysicalObservation(conversation.lastFrame());
            assertEquals(0, successes(run, "talos.list_dir"));
            assertFalse(run.answer().isBlank());
            assertTrue(run.answer().toLowerCase().matches("(?s).*(clarif|specif|scope|unresolved).*"), run.answer());
            assertAbsent(run, workspace.toString(), "private-scope-name-5e13.txt", "private-scope-body-9c31",
                    "private-scope-provider-4a19");
            assertParity(run);
        }
    }

    @Test
    void nextRequestDoesNotInheritPriorAcquisitionPermissionOrObservations() throws Exception {
        Files.writeString(workspace.resolve("previous-turn-name-6a21.txt"), "fixture\n");
        try (var conversation = new Conversation((frame, task) -> frame.originalRequest().equals("List this workspace.")
                ? meaning(frame, DIRECTORY_ACCESS, Publication.ALL_NAMES, false, false, Kind.DIRECTORY_CONTENTS)
                : meaning(frame, Set.of(), Publication.NO_NAMES, true, false, Kind.WORKSPACE_LOCATION))) {
            Run first = conversation.run("List this workspace.", LIST, "Listed the workspace.");
            TurnRequestFrame firstFrame = conversation.lastFrame();
            assertEquals(1, successes(first, "talos.list_dir"));
            assertDirectoryObservation(firstFrame);
            List<TurnRequestFrame.Observation> firstObservations = firstFrame.observations();

            Run second = conversation.run("Where is the workspace? Do not inspect anything.", LIST,
                    "previous-turn-name-6a21.txt is here.");
            TurnRequestFrame secondFrame = conversation.lastFrame();
            assertNotSame(firstFrame, secondFrame);
            assertTrue(secondFrame.revision() > firstFrame.revision());
            assertEquals(TurnRequestFrame.State.CLOSED, firstFrame.state());
            assertEquals(firstObservations, firstFrame.observations());
            assertNoPhysicalObservation(secondFrame);
            assertTrue(second.answer().contains(workspace.toString()), second.answer());
            assertTrue(second.terminalAnswer().contains(workspace.toString()), second.terminalAnswer());
            assertAbsent(second, "previous-turn-name-6a21.txt");
        }
    }

    @Test
    void pendingSaveIsComposedBeforeTheControlledObservationSourceCanConsumeItsFilename() throws Exception {
        var calls = new AtomicInteger();
        try (var conversation = new Conversation((frame, task) -> {
            calls.incrementAndGet();
            return meaning(frame, Set.of(), Publication.NO_NAMES, true, false, Kind.WORKSPACE_LOCATION);
        }, ApprovalResponse.APPROVED)) {
            Run pending = conversation.run("I want to write and save a txt file with a short medieval story.");
            assertNotNull(conversation.memory.pendingContentSave());
            assertTrue(pending.answer().toLowerCase().contains("filename"), pending.answer());
            assertEquals(0, calls.get());
            Run saved = conversation.run("story.txt", WRITE, READ, "The story was saved.");
            assertEquals(0, calls.get(), "The complete pending write must retain ownership before observation adoption");
            assertEquals(1, conversation.approvals.get());
            assertEquals(1, saved.turn().audit().approvalsGranted());
            assertEquals(1, successes(saved, "talos.write_file"));
            assertEquals(STORY, Files.readString(workspace.resolve("story.txt")));
            assertNull(conversation.memory.pendingContentSave());
            assertTrue(saved.turn().audit().localTrace().taskContract().expectedTargets().contains("story.txt"));
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(saved.answer(), "story.txt"), saved.persisted());
        }
    }

    @Test
    void capturedHandoffFollowsRealDenialAndFreshApprovedCreationWithoutNewTools() throws Exception {
        try (var conversation = new Conversation((frame, task) -> meaning(frame, Set.of(), Publication.ALL_NAMES,
                false, false, Kind.RETAINED_SAVES, Kind.RETAINED_VERIFICATION, Kind.RETAINED_UNCHECKED),
                ApprovalResponse.DENIED, ApprovalResponse.APPROVED)) {
            Run denied = conversation.run("Create story.txt with a short medieval story.", WRITE, "The story was saved.");
            assertEquals(1, denied.turn().audit().approvalsDenied());
            assertFalse(Files.exists(workspace.resolve("story.txt")));
            Run saved = conversation.run("Create story.txt now with the short medieval story.", WRITE, READ, "The story was saved.");
            assertEquals(1, saved.turn().audit().approvalsGranted());
            assertEquals(STORY, Files.readString(workspace.resolve("story.txt")));
            Run handoff = conversation.run("Give me a short handoff. What is saved and verified, and what hasn't been checked? Don't change any files.");
            assertTrue(handoff.answer().contains("story.txt"), handoff.answer());
            assertTrue(handoff.answer().contains("approval was denied"), handoff.answer());
            assertTrue(handoff.answer().contains("Current contents") && handoff.answer().contains("not established"), handoff.answer());
            assertTrue(handoff.turn().audit().toolCalls().isEmpty());
            assertEquals(2, conversation.approvals.get());
            assertTrue(handoff.providerInputs().isEmpty(), handoff.providerInputs());
            assertFalse(handoff.turn().audit().workStatusReceipt().presentationComplete());
            assertEquals(3, handoff.turn().audit().workStatusReceipt().resultDecisions().size());
            assertEquals(STORY, Files.readString(workspace.resolve("story.txt")));
            assertParity(handoff);
        }
    }

    @Test
    void activeFrameRejectsWrongThreadAndClosedFrameRejectsSameThreadUse() throws Exception {
        try (var conversation = new Conversation((frame, task) -> {
            Throwable rejection = assertDoesNotThrow(() -> CompletableFuture.supplyAsync(() -> {
                try {
                    frame.requireCurrent();
                    return (Throwable) null;
                } catch (Throwable failure) {
                    return failure;
                }
            }).get(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, rejection);
            return meaning(frame, Set.of(), Publication.NO_NAMES, true, false, Kind.WORKSPACE_LOCATION);
        })) {
            conversation.run("Where is this workspace?", "Unused location prose.");
            assertEquals(TurnRequestFrame.State.CLOSED, conversation.lastFrame().state());
            assertThrows(SecurityException.class, () -> conversation.lastFrame().requireCurrent());
            assertNull(TurnRequestFrame.current());
        }
    }

    @Test
    void prohibitedPrivateTargetIsNotPrintedByTheRealToolProgressPath() throws Exception {
        String privateName = "unadmitted-private-child-82f1.txt";
        Files.writeString(workspace.resolve(privateName), "private-body-1a93");
        String forbiddenRead = "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"" + privateName + "\"}}";
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_COUNT))) {
            Run run = conversation.run("Count all top-level workspace entries without showing any names.",
                    forbiddenRead, "No observation was completed.");
            assertEquals(0, successes(run, "talos.read_file"));
            assertNoPhysicalObservation(conversation.lastFrame());
            assertAbsent(run, privateName, "private-body-1a93");
            assertFalse(run.answer().contains("count is 1"), run.answer());
        }
    }

    @Test
    void topLevelGrantDoesNotAuthorizeAnUnrelatedDirectoryOrDeeperTraversal() throws Exception {
        Files.createDirectories(workspace.resolve("private-child-3d19"));
        Files.writeString(workspace.resolve("private-child-3d19/secret.txt"), "secret");
        String forbiddenList = "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"private-child-3d19\",\"max_depth\":2}}";
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_COUNT))) {
            Run run = conversation.run("Count only the top-level workspace entries without showing names.",
                    forbiddenList, "No admitted listing was completed.");
            assertEquals(0, successes(run, "talos.list_dir"));
            assertNoPhysicalObservation(conversation.lastFrame());
            assertAbsent(run, "private-child-3d19", "secret.txt");
        }
    }

    @Test
    void unrelatedArgumentsCannotTriggerPathPreparationObservations() throws Exception {
        Files.writeString(workspace.resolve("private-extra-target-44f1"), "private");
        String invalid = "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\",\"source\":\" private-extra-target-44f1 \"}}";
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_COUNT))) {
            Run run = conversation.run("Count top-level workspace entries without showing names.", invalid, "No listing was completed.");
            assertEquals(0, successes(run, "talos.list_dir"));
            assertNoPhysicalObservation(conversation.lastFrame());
            assertAbsent(run, "private-extra-target-44f1");
        }
    }

    @Test
    void requestAllowanceCannotOverrideHostDeny() throws Exception {
        Files.writeString(workspace.resolve("host-denied-entry-24b1.txt"), "fixture");
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.ALL_NAMES, false, false, Kind.DIRECTORY_CONTENTS))) {
            conversation.config.data.put("permissions", Map.of("rules", List.of(Map.of(
                    "effect", "deny", "tools", List.of("talos.list_dir")))));
            Run run = conversation.run("List this workspace.", LIST, "The listing succeeded.");
            assertEquals(0, successes(run, "talos.list_dir"));
            assertNoPhysicalObservation(conversation.lastFrame());
            assertAbsent(run, "host-denied-entry-24b1.txt");
            assertEquals(0, conversation.approvals.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void controlledListingStillRequiresHostAskApproval(boolean approved) throws Exception {
        Files.writeString(workspace.resolve("host-ask-entry.txt"), "fixture");
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.ALL_NAMES, false, false, Kind.DIRECTORY_CONTENTS),
                approved ? ApprovalResponse.APPROVED : ApprovalResponse.DENIED)) {
            conversation.config.data.put("permissions", Map.of("rules", List.of(Map.of(
                    "effect", "ask", "tools", List.of("talos.list_dir")))));
            Run run = conversation.run("List this workspace.", LIST, "The listing succeeded.");
            assertEquals(1, conversation.approvals.get());
            assertEquals(approved ? 1 : 0, successes(run, "talos.list_dir"));
            if (approved) assertDirectoryObservation(conversation.lastFrame());
            else {
                assertNoPhysicalObservation(conversation.lastFrame());
                assertAbsent(run, "host-ask-entry.txt");
            }
        }
    }

    @Test
    void admittedRepairContinuationRemainsOwnedByTheMutationPath() throws Exception {
        var calls = new AtomicInteger();
        Files.writeString(workspace.resolve("index.html"), "<html><body>Existing page.</body></html>");
        var retained = dev.talos.runtime.context.ActiveTaskContext.verifierFindings(2, "authored-verifier-context",
                List.of("index.html"), List.of("index.html: missing title"), "FAILED");
        try (var conversation = new Conversation((frame, task) -> {
            calls.incrementAndGet();
            return meaning(frame, Set.of(), Publication.NO_NAMES, true, false, Kind.WORKSPACE_LOCATION);
        })) {
            conversation.memory.setActiveTaskContext(retained);
            conversation.memory.setArtifactGoal(dev.talos.runtime.context.ArtifactGoal.fromActiveContext(retained));
            conversation.session.nextTurn();
            conversation.session.nextTurn();
            Run run = conversation.run("Fix those issues.", "The repair has not been applied.");
            assertEquals(0, calls.get(), "An admitted repair must not be replaced by an observation proposal");
            assertTrue(run.turn().audit().localTrace().taskContract().mutationRequested());
            assertTrue(run.turn().audit().localTrace().taskContract().expectedTargets().contains("index.html"));
        }
    }

    @Test
    void limitedObservationDoesNotProduceAnExactCountOrNames() throws Exception {
        Files.writeString(workspace.resolve("partial-private-a.txt"), "fixture");
        Files.writeString(workspace.resolve("partial-private-b.txt"), "fixture");
        String limited = "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\",\"max_entries\":1}}";
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_COUNT))) {
            Run run = conversation.run("Count all top-level workspace entries without showing names.", limited,
                    "There is exactly one entry.");
            assertFalse(run.answer().contains("count is 1"), run.answer());
            assertTrue(run.answer().toLowerCase().contains("unresolved"), run.answer());
            assertAbsent(run, "partial-private-a.txt", "partial-private-b.txt", "exactly one entry");
        }
    }

    @Test
    void limitedPositiveObservationStillSupportsUsefulNonemptyVerdict() throws Exception {
        Files.writeString(workspace.resolve("partial-private-a.txt"), "fixture");
        Files.writeString(workspace.resolve("partial-private-b.txt"), "fixture");
        String limited = "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\".\",\"max_entries\":1}}";
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_EMPTINESS))) {
            Run run = conversation.run("Only tell whether this workspace is empty, no names.", limited,
                    "The directory is empty.");
            assertTrue(run.answer().toLowerCase().contains("not empty"), run.answer());
            assertAbsent(run, "partial-private-a.txt", "partial-private-b.txt");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"subject", "scope", "basis", "duplicate", "blank-id", "content", "request"})
    void inadmissibleMeaningCannotAcquireOrPublishObservations(String defect) throws Exception {
        Files.writeString(workspace.resolve("unadmitted-entry-7e91.txt"), "private");
        try (var conversation = new Conversation((frame, task) -> {
            Meaning valid = meaning(frame, DIRECTORY_ACCESS, Publication.NO_NAMES, false, false, Kind.DIRECTORY_COUNT);
            RequestedResult result = valid.inventory().results().getFirst();
            var altered = new RequestedResult(defect.equals("blank-id") ? "" : result.id(), result.region(),
                    result.kind(), defect.equals("subject") ? "unbound folder" : result.subject(),
                    defect.equals("scope") ? "recursive" : result.scope(),
                    defect.equals("basis") ? EvidenceBasis.ADMITTED_PROJECT_SOURCES : result.evidenceBasis());
            var inventory = new RequestedResultInventory(
                    defect.equals("request") ? frame.originalRequest() + " unrelated" : frame.originalRequest(),
                    defect.equals("duplicate") ? List.of(altered, altered) : List.of(altered), List.of());
            return new Meaning(inventory, defect.equals("content") ? Set.of(Action.CONTENT) : DIRECTORY_ACCESS,
                    Publication.NO_NAMES, false, false);
        })) {
            Run run = conversation.run("Count top-level workspace entries without showing names.", LIST,
                    "Exactly one entry was counted.");
            assertEquals(0, successes(run, "talos.list_dir"), defect);
            assertNoPhysicalObservation(conversation.lastFrame());
            assertAbsent(run, "unadmitted-entry-7e91.txt", "count is 1", "Exactly one entry");
        }
    }

    @Test
    void childDirectoryLinkIsNotClassifiedThroughItsOutsideTarget() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.createSymbolicLink(workspace.resolve("outside-link"), outside);
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.ALL_NAMES, false, false, Kind.DIRECTORY_CONTENTS))) {
            Run run = conversation.run("List only top-level workspace entries.", LIST, "Unused listing prose.");
            assertEquals(1, successes(run, "talos.list_dir"));
            assertTrue(run.answer().contains("outside-link"), run.answer());
            assertFalse(run.answer().contains("outside-link/"), run.answer());
        }
    }

    @Test
    void visibleChildLinkDoesNotInheritItsOutsideTargetsHiddenFlag() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(workspace.getFileSystem().supportedFileAttributeViews().contains("dos"));
        Path outside = Files.createDirectory(temp.resolve("hidden-outside"));
        Files.setAttribute(outside, "dos:hidden", true);
        Files.createSymbolicLink(workspace.resolve("public-link"), outside);
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.VISIBLE_NAMES, false, false, Kind.DIRECTORY_CONTENTS))) {
            Run run = conversation.run("List only visible top-level workspace entries.", LIST, "Unused listing prose.");
            assertEquals(1, successes(run, "talos.list_dir"));
            assertTrue(run.answer().contains("public-link"), run.answer());
            assertFalse(run.answer().contains("public-link/"), run.answer());
        }
    }

    @Test
    void directExecutionCannotSubstituteADifferentSessionWorkspace() throws Exception {
        Path child = Files.createDirectory(workspace.resolve("nested"));
        Files.writeString(child.resolve("private-nested-entry.txt"), "private");
        var result = new AtomicReference<ToolResult>();
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_COUNT))) {
            conversation.afterHandle = context -> {
                int operations = conversation.lastFrame().observations().size();
                try (var differentSession = new Session(child, conversation.config, conversation.memory)) {
                    result.set(conversation.processor.executeTool(differentSession,
                            new ToolCall("talos.list_dir", Map.of("path", ".", "max_depth", "1")), context));
                }
                assertEquals(operations, conversation.lastFrame().observations().size());
            };
            conversation.run("Count top-level workspace entries without showing names.", LIST, "Unused prose.");
            assertNotNull(result.get());
            assertFalse(result.get().success());
            assertEquals(ToolError.DENIED, result.get().error().code());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void processorSuppliesAnExplicitLifetimeBoundContextToTheRealDirectoryTool(boolean controlled) throws Exception {
        Files.writeString(workspace.resolve("public.txt"), "fixture");
        var retained = new AtomicReference<dev.talos.tools.ToolContext>();
        TurnRequestFrame.ControlledContracts source = controlled ? (frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.ALL_NAMES, false, false, Kind.DIRECTORY_CONTENTS) : null;
        try (var conversation = new Conversation(source)) {
            var actual = new ListDirTool();
            conversation.registry.register(new dev.talos.tools.TalosTool() {
                @Override public String name() { return actual.name(); }
                @Override public String description() { return actual.description(); }
                @Override public dev.talos.tools.ToolDescriptor descriptor() { return actual.descriptor(); }
                @Override public ToolResult execute(ToolCall call, dev.talos.tools.ToolContext context) {
                    retained.set(context);
                    assertEquals(controlled, context.observationBoundary().controlled());
                    return actual.execute(call, context);
                }
            });
            Run run = conversation.run("List the entries in this workspace.", LIST, "public.txt is here.");
            assertEquals(1, successes(run, "talos.list_dir"));
            assertNotNull(retained.get());
            assertThrows(SecurityException.class, () -> retained.get().observationBoundary().controlled());
            assertThrows(SecurityException.class, () -> actual.execute(new ToolCall("talos.list_dir",
                    Map.of("path", "\u0000")), retained.get()));
        }
    }

    @Test
    void normalizedParentAliasCannotProbeAnOutsideLinkTarget() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside-parent-alias"));
        Files.writeString(outside.resolve("private-outside-marker.txt"), "private");
        Files.createSymbolicLink(workspace.resolve("nested"), outside);
        String aliased = "{\"name\":\"talos.list_dir\",\"arguments\":{\"path\":\"nested/..\",\"max_depth\":1}}";
        try (var conversation = new Conversation((frame, task) -> meaning(frame, DIRECTORY_ACCESS,
                Publication.NO_NAMES, false, false, Kind.DIRECTORY_COUNT))) {
            Run run = conversation.run("Count only the top-level workspace entries without names.", aliased, "Unused prose.");
            assertEquals(0, successes(run, "talos.list_dir"));
            assertNoPhysicalObservation(conversation.lastFrame());
            assertAbsent(run, "private-outside-marker.txt");
        }
    }

    private static Meaning meaning(TurnRequestFrame frame, Set<Action> acquisitions, Publication publication,
                                   boolean locationPublic, boolean unresolved, Kind... kinds) {
        String original = frame.originalRequest();
        var region = new SourceRegion(0, original.length());
        List<RequestedResult> results = new ArrayList<>();
        for (Kind kind : kinds) {
            results.add(new RequestedResult("controlled-result-" + (results.size() + 1), region, kind,
                    "active workspace", kind == Kind.WORKSPACE_LOCATION ? "current turn"
                            : RequestedResultInventory.isRetainedKind(kind) ? "retained conversation" : "top-level, observation time",
                    kind == Kind.WORKSPACE_LOCATION ? EvidenceBasis.TURN_WORKSPACE
                            : RequestedResultInventory.isRetainedKind(kind) ? EvidenceBasis.RETAINED_RUNTIME_RECORDS
                            : EvidenceBasis.SCOPED_DIRECTORY_OBSERVATION));
        }
        return new Meaning(new RequestedResultInventory(original, results, List.of()), acquisitions,
                publication, locationPublic, unresolved);
    }

    private static long successes(Run run, String canonicalName) {
        return run.turn().audit().toolCalls().stream()
                .filter(call -> canonicalName.equals(call.name()) && call.success()).count();
    }

    private static void assertNoPhysicalObservation(TurnRequestFrame frame) {
        assertTrue(frame.observations().stream().allMatch(observation -> "BLOCKED".equals(observation.disposition())),
                () -> "No completed or attempted physical access was permitted: " + frame.observations());
    }

    private static void assertDirectoryObservation(TurnRequestFrame frame) {
        assertTrue(frame.observations().stream().anyMatch(observation -> observation.action() == Action.ENUMERATE
                && "COMPLETED".equals(observation.disposition())), frame.observations().toString());
        assertTrue(frame.observations().stream().noneMatch(observation -> observation.action() == Action.CONTENT
                && !"BLOCKED".equals(observation.disposition())), frame.observations().toString());
    }

    private static void assertAbsent(Run run, String... forbidden) {
        for (String value : forbidden) {
            assertFalse(run.answer().contains(value), run.answer());
            assertFalse(run.terminal().contains(value), run.terminal());
            assertFalse(run.persisted().contains(value), run.persisted());
        }
    }

    private static void assertParity(Run run) {
        var redactor = run.terminalPolicy();
        String terminalBody = MemoryUpdateListener.stripUiChromeForHistory(run.terminalAnswer());
        var prepared = switch (run.turn().result()) {
            case Result.Ok ok -> ok.presentation;
            case Result.Streamed streamed -> streamed.presentation;
            default -> throw new AssertionError("An answer presentation was required");
        };
        assertNotNull(prepared, "Terminal publication must be frozen before the frame closes");
        String expectedTerminal = prepared.project(run.answer(), redactor);
        assertEquals(normalize(MemoryUpdateListener.stripUiChromeForHistory(expectedTerminal)), normalize(terminalBody));
        String expectedHistory = run.turn().audit().contentRestrictions().forHistory(
                MemoryUpdateListener.assistantTextForPersistence(run.answer(), run.request()));
        assertEquals(normalize(expectedHistory), normalize(run.persisted()));
        // The terminal's typed location exception does not alter the ordinary history projection.
        assertEquals(normalize(redactor.redactBlock(MemoryUpdateListener.stripUiChromeForHistory(run.answer()))),
                normalize(redactor.redactBlock(run.persisted())));
    }

    private static String normalize(String value) { return value.replaceAll("\\s+", " ").strip(); }

    private record Run(TurnResult turn, String answer, String terminal, String terminalAnswer, String persisted,
                       String providerInputs, String request, Redactor terminalPolicy) {}

    private final class Conversation implements AutoCloseable {
        private final Config config = new Config(null);
        private final SessionMemory memory = new SessionMemory();
        private final ToolRegistry registry = new ToolRegistry();
        private final List<TurnRequestFrame> frames = new ArrayList<>();
        private final AtomicInteger approvals = new AtomicInteger();
        private final List<ApprovalResponse> decisions;
        private final Session session;
        private final TurnProcessor processor;
        private Consumer<Context> afterHandle = context -> {};

        private Conversation(TurnRequestFrame.ControlledContracts contracts, ApprovalResponse... responses) {
            decisions = List.of(responses);
            registry.register(new ListDirTool());
            registry.register(new ReadFileTool());
            registry.register(new FileWriteTool());
            var gate = new ApprovalGate() {
                @Override public boolean approve(String description, String detail) {
                    return approveFull(description, detail).isApproved();
                }
                @Override public ApprovalResponse approveFull(String description, String detail) {
                    int index = approvals.getAndIncrement();
                    return index < decisions.size() ? decisions.get(index) : ApprovalResponse.DENIED;
                }
            };
            var mode = new UnifiedAssistantMode();
            var ordinaryProcessor = new TurnProcessor((request, root, context) -> {
                var result = mode.handle(request, root, (Context) context);
                afterHandle.accept((Context) context);
                return result;
            },
                    gate, registry, ApprovalPolicy.ALWAYS_ASK);
            processor = contracts == null ? ordinaryProcessor : ordinaryProcessor.withControlledContracts((frame, task) -> {
                        frames.add(frame);
                        return contracts.propose(frame, task);
                    });
            processor.addListener(new MemoryUpdateListener(new ConversationManager(memory,
                    TokenBudget.fromConfig(config)), null, memory));
            processor.addListener(new ActiveTaskContextUpdateListener(memory));
            session = new Session(workspace, config, memory);
        }

        private TurnRequestFrame lastFrame() {
            assertFalse(frames.isEmpty(), "The controlled source must be reached through real preparation");
            return frames.getLast();
        }

        private Run run(String request, String... responses) throws Exception {
            var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(responses.length == 0
                    ? List.of(new LlmClient.StreamResult("Unused provider response.", List.of()))
                    : Arrays.stream(responses).map(response -> new LlmClient.StreamResult(response, List.of())).toList(), 16_384);
            var bytes = new ByteArrayOutputStream();
            try (var client = fixture.client(); var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
                client.setToolSpecs(registry.descriptors().stream()
                        .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.parametersSchema())).toList());
                var terminalPolicy = new Redactor(config.data);
                var renderer = new RenderEngine(config, terminalPolicy, out, false);
                try {
                    var context = Context.builder(config).llm(client).memory(memory).toolRegistry(registry)
                            .sandbox(new Sandbox(workspace, Map.of()))
                            .toolCallLoop(new ToolCallLoop(processor, 4,
                                    (tool, action, detail) -> out.println("Progress " + tool + " " + action + " " + detail)))
                            .streamSink(new ToolCallStreamFilter(renderer.answerStreamSink(out::print))).build();
                    TurnResult turn = processor.process(session, request, context);
                    assertNotNull(turn);
                    String selected = switch (turn.result()) {
                        case Result.Ok ok -> ok.text;
                        case Result.Streamed streamed -> streamed.fullText + streamed.suffix;
                        default -> throw new AssertionError("An answer result was required: " + turn.result());
                    };
                    renderer.render(turn.result());
                    String terminal = bytes.toString(StandardCharsets.UTF_8).replaceAll("\u001B\\[[0-9;]*m", "");
                    String terminalAnswer = terminal.lines()
                            .filter(line -> line.startsWith("  │ ") || line.startsWith("  | "))
                            .map(line -> line.substring(4)).collect(Collectors.joining("\n"));
                    assertFalse(memory.getTurns().isEmpty(), "The selected answer must reach in-memory history");
                    String providerInputs = fixture.requests().stream().flatMap(value -> value.messages.stream())
                            .map(dev.talos.spi.types.ChatMessage::content).collect(Collectors.joining("\n"));
                    return new Run(turn, selected, terminal, terminalAnswer, memory.getTurns().getLast().content(),
                            providerInputs, request, terminalPolicy);
                } finally {
                    renderer.shutdown();
                }
            }
        }

        @Override public void close() { session.close(); }
    }
}
