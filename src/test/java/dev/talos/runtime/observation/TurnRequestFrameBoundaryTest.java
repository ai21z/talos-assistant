package dev.talos.runtime.observation;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.WorkspaceObservationBoundary;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.impl.ListDirTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TurnRequestFrameBoundaryTest {
    @TempDir Path workspace;

    @AfterEach void cleanup() {
        if (TurnRequestFrame.current() != null) TurnRequestFrame.current().close();
        ProviderResponseCapture.clear();
    }

    @Test void realToolRejectsRetainedContextsBeforePathPreparation() throws Exception {
        ToolContext retained;
        try (var frame = adopted()) {
            retained = new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config(null), frame.observationBoundary());
            var wrongThread = CompletableFuture.supplyAsync(() -> {
                try {
                    new ListDirTool().execute(new ToolCall("talos.list_dir", Map.of("path", "\u0000")), retained);
                    return (Throwable) null;
                } catch (Throwable failure) { return failure; }
            }).get(2, TimeUnit.SECONDS);
            assertInstanceOf(SecurityException.class, wrongThread);
            assertTrue(frame.observations().isEmpty());
        }
        assertThrows(SecurityException.class, () -> new ListDirTool().execute(
                new ToolCall("talos.list_dir", Map.of("path", "\u0000")), retained));
        try (var unrelated = TurnRequestFrame.open("Later request", workspace, 2, null)) {
            assertThrows(SecurityException.class, () -> retained.observationBoundary().controlled());
            assertTrue(unrelated.observations().isEmpty());
        }
    }

    @Test void delegatedSaveOwnershipDoesNotRestoreUnadmittedPins() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "private-pin-body-82");
        String request = "Write hello to note.txt, do not inspect @README.md.";
        try (var frame = TurnRequestFrame.open(request, workspace, 1, (owned, task) -> null)) {
            var context = dev.talos.cli.repl.Context.builder(new Config(null))
                    .sandbox(new Sandbox(workspace, Map.of())).build().withRequestFrame(frame);
            frame.adopt(TaskContractResolver.resolveFromUserRequest(request));
            assertEquals(TurnRequestFrame.State.DELEGATED, frame.state());
            assertTrue(context.prepareRequestPins(request, workspace).pinnedFiles().isEmpty(),
                    "Delegation is not permission to acquire a prohibited pin");
        }
    }

    @Test void cleanupCallbackFailureDoesNotStrandTheRequestFrame() {
        var frame = TurnRequestFrame.open("Default turn", workspace, 1, null);
        var scope = ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {
            @Override public void generationAbandoned(ProviderResponseCapture.GenerationAbandonment event) {
                throw new IllegalStateException("Controlled abandonment fault");
            }
        });
        scope.openGeneration(ProviderResponseCapture.Source.SCRIPTED_HARNESS, ProviderResponseCapture.Purpose.HARNESS_FIXTURE);
        assertThrows(IllegalStateException.class, () -> frame.finish(ProviderResponseCapture::clear));
        assertEquals(TurnRequestFrame.State.CLOSED, frame.state());
        assertNull(TurnRequestFrame.current());
        ProviderResponseCapture.clear();
        try (var next = TurnRequestFrame.open("Next default turn", workspace, 2, null)) {
            next.requireCurrent();
        }
        assertNull(TurnRequestFrame.current());
    }

    @Test void adapterRejectsDeepMetadataAndEnumerationBeforeTheOperation() throws Exception {
        var executions = new AtomicInteger();
        try (var frame = adopted()) {
            var boundary = frame.observationBoundary();
            assertThrows(SecurityException.class, () -> boundary.observe(WorkspaceObservationBoundary.Action.ENUMERATE,
                    workspace.resolve("nested"), executions::incrementAndGet));
            assertThrows(SecurityException.class, () -> boundary.observe(WorkspaceObservationBoundary.Action.METADATA,
                    workspace.resolve("nested/child"), executions::incrementAndGet));
            assertEquals(0, executions.get());
            assertEquals(2, frame.observations().size());
            assertTrue(frame.observations().stream().allMatch(item -> item.disposition().equals("BLOCKED")));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realDirectoryToolDoesNotPublishPrivateObservationErrors(boolean unchecked) throws Exception {
        String secret = "private-failure-path-724";
        var attempts = new AtomicInteger();
        var boundary = new WorkspaceObservationBoundary() {
            @Override public boolean controlled() { return true; }
            @Override public Publication publication() { return Publication.NO_NAMES; }
            @Override public <T> T observe(Action action, Path target, IoSupplier<T> operation) throws IOException {
                attempts.incrementAndGet();
                if (unchecked) throw new UncheckedIOException(new IOException(secret));
                throw new IOException(secret);
            }
        };
        var result = new ListDirTool().execute(new ToolCall("talos.list_dir", Map.of("path", ".")),
                new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config(null), boundary));
        assertFalse(result.success());
        assertEquals(1, attempts.get());
        assertFalse(result.error().message().contains(secret));
        assertNull(result.contentMetadata().directoryObservation());
    }

    @Test void noNameObservationTextDoesNotDistinguishWithheldExistence() throws Exception {
        var boundary = new WorkspaceObservationBoundary() {
            @Override public boolean controlled() { return true; }
            @Override public Publication publication() { return Publication.NO_NAMES; }
            @Override public Fields fields() { return new Fields(false, false, false, true); }
            @Override public <T> T observe(Action action, Path target, IoSupplier<T> operation) throws IOException {
                return operation.get();
            }
        };
        var context = new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config(null), boundary);
        var tool = new ListDirTool();
        var call = new ToolCall("talos.list_dir", Map.of("path", ".", "max_entries", "1"));
        String empty = tool.execute(call, context).output();
        Files.writeString(workspace.resolve("one.txt"), "fixture");
        String one = tool.execute(call, context).output();
        Files.writeString(workspace.resolve("two.txt"), "fixture");
        String limited = tool.execute(call, context).output();
        assertEquals(empty, one, "Provider-facing text must not reveal withheld existence");
        assertEquals(empty, limited, "Provider-facing text must not reveal withheld truncation counts");
    }

    private TurnRequestFrame adopted() {
        String request = "Count top-level workspace entries without names.";
        var frame = TurnRequestFrame.open(request, workspace, 1, (owned, task) -> {
            var result = new RequestedResultInventory.RequestedResult("count", new RequestedResultInventory.SourceRegion(0, request.length()),
                    RequestedResultInventory.Kind.DIRECTORY_COUNT, "active workspace", "top-level, observation time",
                    RequestedResultInventory.EvidenceBasis.SCOPED_DIRECTORY_OBSERVATION);
            return new TurnRequestFrame.Meaning(new RequestedResultInventory(request, List.of(result), List.of()),
                    Set.of(TurnRequestFrame.Action.METADATA, TurnRequestFrame.Action.ENUMERATE),
                    TurnRequestFrame.Publication.NO_NAMES, false, false);
        });
        frame.adopt(TaskContractResolver.resolveFromUserRequest(request));
        assertEquals(TurnRequestFrame.State.ADOPTED, frame.state());
        return frame;
    }
}
