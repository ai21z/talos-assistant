package dev.talos.cli.repl;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.modes.UnifiedAssistantMode;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.Result;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallStreamFilter;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.intent.RequestedResultInventory.EvidenceBasis;
import dev.talos.runtime.intent.RequestedResultInventory.Kind;
import dev.talos.runtime.intent.RequestedResultInventory.RequestedResult;
import dev.talos.runtime.intent.RequestedResultInventory.SourceRegion;
import dev.talos.runtime.observation.TurnRequestFrame;
import dev.talos.runtime.observation.TurnRequestFrame.Meaning;
import dev.talos.runtime.observation.TurnRequestFrame.Publication;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real prompt ingress is exercised with authored scope and an offline provider fixture. */
@Timeout(30)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class MicaRequestBoundaryIngressTest {
    @TempDir Path temp;
    private Path workspace;
    private String previousHome;

    @BeforeEach
    void isolate() throws Exception {
        workspace = Files.createDirectory(temp.resolve("workspace"));
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectory(temp.resolve("home")).toString());
        LastPromptCapture.clear();
        AtFilePins.recordLast(AtFilePins.Resolution.empty());
    }

    @AfterEach
    void clear() {
        LastPromptCapture.clear();
        AtFilePins.recordLast(AtFilePins.Resolution.empty());
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void defaultIngressReadsValidPinIntoTurnContextWithoutChangingContent() throws Exception {
        String content = "# Pin fixture\r\nUnicode: café\n  trailing spaces  \n";
        Files.writeString(workspace.resolve("README.md"), content, StandardCharsets.UTF_8);

        Run run = run("Explain @README.md", false);

        assertEquals(1, run.context().pinnedFiles().size());
        var pin = run.context().pinnedFiles().getFirst();
        assertEquals("README.md", pin.path());
        assertEquals(content, pin.content());
        assertEquals(content.length(), pin.totalChars());
        assertFalse(pin.truncated());
        assertEquals(run.context().pinnedFiles(), AtFilePins.lastResolution().pins());
        assertTrue(AtFilePins.lastResolution().notices().isEmpty());
        assertFalse(run.context().requestFrame().hasControlledSource());
        assertEquals(TurnRequestFrame.State.CLOSED, run.context().requestFrame().state());
        assertTrue(run.output().contains("Handled pin context."), run.output());
        assertNull(TurnRequestFrame.current());
    }

    @Test
    void defaultIngressRefusesOutsidePinWithoutAdmittingItsContent() throws Exception {
        String secret = "outside-pin-content-7ec48";
        Files.writeString(temp.resolve("outside.txt"), secret);

        Run run = run("Explain @../outside.txt", false);

        assertTrue(run.context().pinnedFiles().isEmpty());
        assertTrue(AtFilePins.lastResolution().pins().isEmpty());
        assertEquals(List.of("@-pin '../outside.txt' skipped: outside the workspace."),
                AtFilePins.lastResolution().notices());
        assertTrue(run.output().contains("outside the workspace"), run.output());
        assertAbsent(run, secret);
        assertNull(TurnRequestFrame.current());
    }

    @Test
    void defaultIngressRefusesProtectedPinWithoutAdmittingItsContent() throws Exception {
        String secret = "protected-pin-content-1a49d";
        Files.writeString(workspace.resolve(".env"), secret);

        Run run = run("Explain @.env", false);

        assertTrue(run.context().pinnedFiles().isEmpty());
        assertTrue(AtFilePins.lastResolution().pins().isEmpty());
        assertEquals(List.of("@-pin '.env' refused: protected path. "
                        + "Ask for it in the prompt instead; read_file is approval-gated."),
                AtFilePins.lastResolution().notices());
        assertTrue(run.output().contains("protected path"), run.output());
        assertTrue(run.output().contains("approval-gated"), run.output());
        assertAbsent(run, secret);
        assertNull(TurnRequestFrame.current());
    }

    @Test
    void controlledLocationIngressSkipsPinsAndPrivateManifestAcquisition() throws Exception {
        String readmeSecret = "unadmitted-readme-body-86e12";
        String privateDirectory = "private-nested-directory-2c857";
        String privateFile = "private-nested-file-4f159.txt";
        String privateContent = "unadmitted-nested-body-6e723";
        Files.writeString(workspace.resolve("README.md"), readmeSecret);
        Path nested = Files.createDirectory(workspace.resolve(privateDirectory));
        Files.writeString(nested.resolve(privateFile), privateContent);
        String request = "Where is the workspace? Do not inspect files, including @README.md.";

        Run run = run(request, true);

        assertNotNull(run.frame(), "The authored source must be reached through real preparation");
        assertEquals(request, run.frame().originalRequest());
        assertEquals(TurnRequestFrame.State.CLOSED, run.frame().state());
        assertNotNull(run.frame().meaning(), "A read-only authored meaning must be adopted");
        assertTrue(run.frame().observations().isEmpty(), run.frame().observations().toString());
        assertTrue(run.context().pinnedFiles().isEmpty());
        assertTrue(AtFilePins.lastResolution().pins().isEmpty());
        assertTrue(AtFilePins.lastResolution().notices().isEmpty());
        assertTrue(run.selected() instanceof Result.Ok, String.valueOf(run.selected()));
        assertTrue(((Result.Ok) run.selected()).text.contains(workspace.toString()),
                ((Result.Ok) run.selected()).text);
        assertTrue(run.output().contains(workspace.toString()), run.output());
        assertFalse(run.output().contains("[path]"), run.output());
        assertTrue(LastPromptCapture.latest().isPresent(), "The real UnifiedAssistantMode prompt must be captured");
        assertAbsent(run, readmeSecret, privateDirectory, privateFile, privateContent);
        assertNull(TurnRequestFrame.current());
    }

    private Run run(String request, boolean controlled) throws Exception {
        Config config = new Config(null);
        SessionMemory memory = new SessionMemory();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new ListDirTool());
        tools.register(new ReadFileTool());
        var seenContext = new AtomicReference<Context>();
        var seenFrame = new AtomicReference<TurnRequestFrame>();
        var selected = new AtomicReference<Result>();
        var mode = new UnifiedAssistantMode();
        var processor = new TurnProcessor((raw, root, context) -> {
            seenContext.set((Context) context);
            Optional<Result> result = controlled ? mode.handle(raw, root, (Context) context)
                    : Optional.of(new Result.Ok("Handled pin context."));
            result.ifPresent(selected::set);
            return result;
        }, (description, detail) -> false, tools, ApprovalPolicy.ALWAYS_ASK);
        if (controlled) processor = processor.withControlledContracts((frame, composedTask) -> {
            seenFrame.set(frame);
            String original = frame.originalRequest();
            var result = new RequestedResult("authored-location", new SourceRegion(0, original.length()),
                    Kind.WORKSPACE_LOCATION, "active workspace", "current turn", EvidenceBasis.TURN_WORKSPACE);
            return new Meaning(new RequestedResultInventory(original, List.of(result), List.of()),
                    Set.of(), Publication.NO_NAMES, true, false);
        });
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithContextWindow(
                List.of(new LlmClient.StreamResult("Harmless offline provider response.", List.of())), 16_384);
        var bytes = new ByteArrayOutputStream();
        try (var client = fixture.client(); var session = new Session(workspace, config, memory);
             var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            client.setToolSpecs(tools.descriptors().stream()
                    .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.parametersSchema())).toList());
            var renderer = new RenderEngine(config, new Redactor(), output, false);
            try {
                var context = Context.builder(config).llm(client).memory(memory).toolRegistry(tools)
                        .sandbox(new Sandbox(workspace, Map.of()))
                        .toolCallLoop(new ToolCallLoop(processor, 4,
                                (tool, action, detail) -> output.println("Progress " + tool + " " + action + " " + detail)))
                        .streamSink(new ToolCallStreamFilter(renderer.answerStreamSink(output::print))).build();
                var router = new ReplRouter(ModeController.defaultController(), processor, session, context,
                        renderer, new CommandRegistry(), workspace, new AtomicBoolean(false), "",
                        WorkspaceCommandTemplates.none());
                assertTrue(router.tryHandlePrompt(request));
                assertNotNull(seenContext.get(), "The prompt must reach TurnProcessor routing");
                assertNotNull(selected.get(), "The routed mode must return a result");
                assertTrue(context.pinnedFiles().isEmpty(), "The long-lived Context must retain no pins");
                String providerInputs = fixture.requests().stream().flatMap(value -> value.messages.stream())
                        .map(ChatMessage::content).collect(Collectors.joining("\n"));
                String capturedPrompt = LastPromptCapture.latest().map(Object::toString).orElse("");
                return new Run(seenContext.get(), seenFrame.get(), selected.get(),
                        bytes.toString(StandardCharsets.UTF_8), capturedPrompt, providerInputs);
            } finally {
                renderer.shutdown();
            }
        }
    }

    private static void assertAbsent(Run run, String... values) {
        for (String value : values) {
            assertFalse(run.output().contains(value), run.output());
            assertFalse(run.capturedPrompt().contains(value), run.capturedPrompt());
            assertFalse(run.providerInputs().contains(value), run.providerInputs());
            assertTrue(run.context().pinnedFiles().stream().noneMatch(pin -> pin.content().contains(value)));
        }
    }

    private record Run(Context context, TurnRequestFrame frame, Result selected, String output,
                       String capturedPrompt, String providerInputs) {}
}
