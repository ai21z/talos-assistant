package dev.talos.runtime.observation;

import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskResolution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A turn-owned boundary is supplied only by explicit runtime wiring. No interpreter is enabled. */
public final class TurnRequestFrame implements AutoCloseable {
    public enum Action { METADATA, ENUMERATE, CONTENT }
    public enum State { PENDING, ADOPTED, UNRESOLVED, DELEGATED, CLOSED }
    public enum Publication { ALL_NAMES, VISIBLE_NAMES, NO_NAMES }
    @FunctionalInterface public interface ControlledContracts {
        Meaning propose(TurnRequestFrame frame, TaskResolution composedTask);
    }
    public record Meaning(RequestedResultInventory inventory, Set<Action> acquisitions,
                          Publication publication, boolean locationPublic, boolean unresolvedScope,
                          dev.talos.spi.WorkspaceObservationBoundary.Fields fields) {
        public Meaning {
            Objects.requireNonNull(inventory);
            acquisitions = Set.copyOf(acquisitions);
            Objects.requireNonNull(publication);
            Objects.requireNonNull(fields);
        }
        public Meaning(RequestedResultInventory inventory, Set<Action> acquisitions,
                       Publication publication, boolean locationPublic, boolean unresolvedScope) {
            this(inventory, acquisitions, publication, locationPublic, unresolvedScope,
                    dev.talos.spi.WorkspaceObservationBoundary.Fields.all());
        }
    }
    public record Observation(Action action, Path target, String disposition) {}

    private static final ThreadLocal<TurnRequestFrame> CURRENT = new ThreadLocal<>();
    private final String originalRequest;
    private final Path workspace;
    private final int revision;
    private final Thread owner = Thread.currentThread();
    private final ControlledContracts source;
    private final List<Observation> observations = new ArrayList<>();
    private State state;
    private Meaning meaning;

    private TurnRequestFrame(String request, Path workspace, int revision, ControlledContracts source) {
        this.originalRequest = Objects.requireNonNull(request);
        this.workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
        this.revision = revision;
        this.source = source;
        this.state = source == null ? State.DELEGATED : State.PENDING;
    }

    public static TurnRequestFrame open(String request, Path workspace, int revision, ControlledContracts source) {
        if (CURRENT.get() != null) throw new IllegalStateException("A request boundary is already active");
        var frame = new TurnRequestFrame(request, workspace, revision, source);
        CURRENT.set(frame);
        return frame;
    }

    public static TurnRequestFrame current() { return CURRENT.get(); }
    public String originalRequest() { return originalRequest; }
    public Path workspace() { return workspace; }
    public int revision() { return revision; }
    public State state() { return state; }
    public Meaning meaning() { return meaning; }
    public boolean hasControlledSource() { return source != null; }
    public List<Observation> observations() { return List.copyOf(observations); }
    public boolean controlled() { return source != null && state != State.DELEGATED; }
    public boolean preparationSuppressed() { return source != null; }
    public static boolean preparationSuppressedCurrent() {
        var frame = current();
        return frame != null && frame.preparationSuppressed();
    }
    public static boolean admits(RequestedResultInventory inventory) {
        var frame = current();
        if (frame == null) return false;
        frame.requireCurrent();
        return frame.state == State.ADOPTED && frame.meaning != null
                && frame.meaning.inventory().equals(inventory);
    }
    public static boolean controlledCurrent() {
        var frame = current();
        return frame != null && frame.controlled();
    }

    /** Pending save ownership is preserved before an observation proposal is considered. */
    public void adopt(TaskResolution task) {
        requireCurrent();
        if (state != State.PENDING) return;
        TaskContract contract = Objects.requireNonNull(task).contract();
        if (contract.mutationRequested() || contract.mutationAllowed()
                || !originalRequest.equals(contract.originalUserRequest())
                || task.intent().contentSaveRequest().present()) {
            state = State.DELEGATED;
            return;
        }
        Meaning proposed = source.propose(this, task);
        if (proposed == null || originalRequest.length() > 8192
                || !originalRequest.equals(proposed.inventory().originalRequest())
                || proposed.acquisitions().contains(Action.CONTENT)
                || !proposed.inventory().requires(RequestedResultInventory.Kind.DIRECTORY_CONTENTS)
                    && !proposed.inventory().requires(RequestedResultInventory.Kind.RETAINED_SAVES)
                    && proposed.publication() != Publication.NO_NAMES
                || !proposed.fields().hiddenFactsPublic() && proposed.publication() == Publication.ALL_NAMES
                || proposed.inventory().results().isEmpty() || proposed.inventory().results().size() > 16
                || proposed.inventory().results().stream().anyMatch(result -> !validResult(result))
                || proposed.inventory().results().stream().map(RequestedResultInventory.RequestedResult::id)
                    .distinct().count() != proposed.inventory().results().size()
                || !validReplacements(proposed.inventory())
                || proposed.inventory().retainedWork() && (!proposed.acquisitions().isEmpty()
                    || !dev.talos.runtime.policy.WorkStatusAnswerPolicy.permitsRetainedOnly(contract)
                    || proposed.inventory().results().stream().anyMatch(result ->
                        !RequestedResultInventory.isRetainedKind(result.kind())
                        && result.kind() != RequestedResultInventory.Kind.UNSUPPORTED))
                || !proposed.inventory().retainedWork() && proposed.acquisitions().contains(Action.ENUMERATE)
                    && !proposed.inventory().requires(RequestedResultInventory.Kind.DIRECTORY_CONTENTS)
                    && !proposed.inventory().requires(RequestedResultInventory.Kind.DIRECTORY_COUNT)
                    && !proposed.inventory().requires(RequestedResultInventory.Kind.DIRECTORY_EMPTINESS)) {
            state = State.UNRESOLVED;
            return;
        }
        meaning = proposed;
        state = proposed.unresolvedScope() ? State.UNRESOLVED : State.ADOPTED;
    }

    private static boolean validResult(RequestedResultInventory.RequestedResult result) {
        if (result.id() == null || !result.id().matches("[A-Za-z0-9_-]{1,64}") || result.kind() == null
                || result.region().start() == result.region().end() || !"active workspace".equals(result.subject()))
            return false;
        var basis = switch (result.kind()) {
            case WORKSPACE_LOCATION -> RequestedResultInventory.EvidenceBasis.TURN_WORKSPACE;
            case PROJECT_PURPOSE -> RequestedResultInventory.EvidenceBasis.ADMITTED_PROJECT_SOURCES;
            case RETAINED_SAVES, RETAINED_VERIFICATION, RETAINED_UNCHECKED ->
                    RequestedResultInventory.EvidenceBasis.RETAINED_RUNTIME_RECORDS;
            case UNSUPPORTED -> RequestedResultInventory.EvidenceBasis.NONE;
            default -> RequestedResultInventory.EvidenceBasis.SCOPED_DIRECTORY_OBSERVATION;
        };
        String scope = switch (result.kind()) {
            case WORKSPACE_LOCATION, PROJECT_PURPOSE, UNSUPPORTED -> "current turn";
            case RETAINED_SAVES, RETAINED_VERIFICATION, RETAINED_UNCHECKED -> "retained conversation";
            default -> "top-level, observation time";
        };
        return result.evidenceBasis() == basis && scope.equals(result.scope())
                && (result.kind() != RequestedResultInventory.Kind.UNSUPPORTED
                    || result.disposition() == RequestedResultInventory.Disposition.UNSUPPORTED);
    }

    private static boolean validReplacements(RequestedResultInventory inventory) {
        for (var result : inventory.results()) {
            if (result.disposition() != RequestedResultInventory.Disposition.REPLACED) {
                if (!result.replacementId().isEmpty()) return false;
            } else if (inventory.results().stream().noneMatch(target -> target.id().equals(result.replacementId())
                    && !target.id().equals(result.id())
                    && target.disposition() != RequestedResultInventory.Disposition.REPLACED
                    && target.disposition() != RequestedResultInventory.Disposition.CANCELLED)) return false;
        }
        return true;
    }

    public RequestedResultInventory inventoryFor(TaskContract contract) {
        requireCurrent();
        if (!controlled() || !originalRequest.equals(contract.originalUserRequest())) return null;
        return state == State.ADOPTED && meaning != null ? meaning.inventory()
                : new RequestedResultInventory(originalRequest, List.of(),
                    List.of(new RequestedResultInventory.SourceRegion(0, originalRequest.length())));
    }

    public boolean allows(Action action, Path target) {
        requireCurrent();
        if (!controlled()) return true;
        if (state != State.ADOPTED || !meaning.acquisitions().contains(action) || target == null) return false;
        Path normalized = target.toAbsolutePath().normalize();
        return switch (action) {
            case ENUMERATE -> normalized.equals(workspace);
            case METADATA -> normalized.equals(workspace) || workspace.equals(normalized.getParent());
            case CONTENT -> false;
        };
    }

    public boolean allowsTool(String canonicalName) {
        requireCurrent();
        if (!controlled()) return true;
        if (state != State.ADOPTED) return false;
        return switch (canonicalName) {
            case "talos.list_dir", "list_dir" -> meaning.acquisitions().contains(Action.ENUMERATE)
                    && meaning.acquisitions().contains(Action.METADATA);
            default -> false;
        };
    }

    public boolean allowsDirectoryTarget(java.util.Map<String, String> arguments) {
        requireCurrent();
        if (!controlled()) return true;
        if (!Set.of("path", "dir", "directory", "dir_path", "folder", "max_depth", "max_entries")
                .containsAll(arguments.keySet())) return false;
        for (String key : List.of("path", "dir", "directory", "dir_path", "folder")) {
            String value = arguments.get(key);
            if (value != null && !value.isBlank()) {
                try {
                    for (Path component : Path.of(value)) if (component.toString().equals("..")) return false;
                    if (!workspace.resolve(value).toAbsolutePath().normalize().equals(workspace)) return false;
                } catch (RuntimeException invalid) { return false; }
            }
        }
        String depth = arguments.get("max_depth");
        return depth == null || depth.equals("1");
    }

    /** The adapter retains this frame, not whichever frame later owns the thread. */
    public dev.talos.spi.WorkspaceObservationBoundary observationBoundary() {
        requireCurrent();
        return new dev.talos.spi.WorkspaceObservationBoundary() {
            @Override public boolean controlled() {
                requireCurrent();
                return TurnRequestFrame.this.controlled();
            }
            @Override public dev.talos.spi.WorkspaceObservationBoundary.Publication publication() {
                requireCurrent();
                return dev.talos.spi.WorkspaceObservationBoundary.Publication.valueOf(
                        controlled() && meaning != null ? meaning.publication().name() : Publication.ALL_NAMES.name());
            }
            @Override public dev.talos.spi.WorkspaceObservationBoundary.Fields fields() {
                requireCurrent();
                return controlled() && meaning != null ? meaning.fields()
                        : dev.talos.spi.WorkspaceObservationBoundary.Fields.all();
            }
            @Override public <T> T observe(dev.talos.spi.WorkspaceObservationBoundary.Action action, Path target,
                    dev.talos.spi.WorkspaceObservationBoundary.IoSupplier<T> operation) throws IOException {
                return observeBound(TurnRequestFrame.Action.valueOf(action.name()), target, operation);
            }
        };
    }

    /** The count records guarded operations, not OS calls or lazy stream elements. */
    private <T> T observeBound(Action action, Path target,
            dev.talos.spi.WorkspaceObservationBoundary.IoSupplier<T> operation) throws IOException {
        requireCurrent();
        if (!controlled()) return operation.get();
        if (!allows(action, target)) {
            observations.add(new Observation(action, target, "BLOCKED"));
            throw new SecurityException("Workspace observation was not admitted for the current request");
        }
        try {
            T result = operation.get();
            observations.add(new Observation(action, target, "COMPLETED"));
            return result;
        } catch (IOException | RuntimeException failure) {
            observations.add(new Observation(action, target, "FAILED"));
            throw failure;
        }
    }

    public void finish(Runnable cleanup) {
        requireCurrent();
        try { cleanup.run(); } finally { close(); }
    }

    public void requireCurrent() {
        if (Thread.currentThread() != owner || CURRENT.get() != this || state == State.CLOSED)
            throw new SecurityException("The request boundary is stale or belongs to another thread");
    }

    @Override public void close() {
        requireCurrent();
        state = State.CLOSED;
        CURRENT.remove();
    }
}
