package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop.ToolOutcome;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.EvidenceObligationVerifier;
import dev.talos.runtime.policy.ProjectSummaryEvidencePolicy;
import dev.talos.runtime.policy.ProtectedPathPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.trace.LocalTurnTraceCapture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Known read limitations are retained for settlement, not semantic or execution authority. */
public final class ReadEvidenceCoverage {
    private static final ReadEvidenceCoverage EMPTY =
            new ReadEvidenceCoverage(null, null, "", 0, List.of(), false, null, List.of(), List.of());

    private final Path workspace;
    private final TaskContract contract;
    private final String traceId;
    private final int turn;
    private final List<Observation> observations;
    private final boolean invalid;
    private final ProjectSummaryEvidencePolicy.AnchorSelection summaryRequirement;
    private final List<ToolOutcome> allOutcomes;
    private final List<SearchObservation> searches;
    private final List<DirectoryRead> directories;
    private final List<ToolOutcome> preExecutionRejections;

    private ReadEvidenceCoverage(Path workspace, TaskContract contract, String traceId, int turn,
                                 List<Observation> observations, boolean invalid,
                                 ProjectSummaryEvidencePolicy.AnchorSelection summaryRequirement,
                                 List<ToolOutcome> allOutcomes, List<SearchObservation> searches) {
        this(workspace, contract, traceId, turn, observations, invalid, summaryRequirement,
                allOutcomes, searches, List.of());
    }

    private ReadEvidenceCoverage(Path workspace, TaskContract contract, String traceId, int turn,
                                 List<Observation> observations, boolean invalid,
                                 ProjectSummaryEvidencePolicy.AnchorSelection summaryRequirement,
                                 List<ToolOutcome> allOutcomes, List<SearchObservation> searches,
                                 List<DirectoryRead> directories) {
        this(workspace, contract, traceId, turn, observations, invalid, summaryRequirement,
                allOutcomes, searches, directories, List.of());
    }

    private ReadEvidenceCoverage(Path workspace, TaskContract contract, String traceId, int turn,
                                 List<Observation> observations, boolean invalid,
                                 ProjectSummaryEvidencePolicy.AnchorSelection summaryRequirement,
                                 List<ToolOutcome> allOutcomes, List<SearchObservation> searches,
                                 List<DirectoryRead> directories, List<ToolOutcome> preExecutionRejections) {
        this.workspace = workspace;
        this.contract = contract;
        this.traceId = traceId;
        this.turn = turn;
        this.observations = List.copyOf(observations);
        this.invalid = invalid;
        this.summaryRequirement = summaryRequirement;
        this.allOutcomes = List.copyOf(allOutcomes);
        this.searches = List.copyOf(searches);
        this.directories = List.copyOf(directories);
        this.preExecutionRejections = List.copyOf(preExecutionRejections);
    }

    public static ReadEvidenceCoverage empty() { return EMPTY; }
    public boolean captured() { return this != EMPTY; }

    /** Only executed occurrences from the active read-only loop can produce this projection. */
    public static ReadEvidenceCoverage capture(LoopState state) {
        TaskContract contract = TurnTaskContractCapture.get();
        if (state == null || contract != null && (contract.mutationAllowed() || contract.mutationRequested())) return EMPTY;
        List<Observation> observations = new ArrayList<>();
        List<SearchObservation> searches = new ArrayList<>();
        List<DirectoryRead> directories = new ArrayList<>();
        List<ToolOutcome> preExecutionRejections = new ArrayList<>();
        for (ToolOutcome outcome : state.toolOutcomes) {
            if (state.toolOutcomeResults.preExecutionRejected(outcome, state.toolOutcomes, state.messages))
                preExecutionRejections.add(outcome);
            if ("list_dir".equals(outcome.localCanonicalToolName())) {
                directories.add(new DirectoryRead(outcome,
                        state.toolOutcomeResults.executionIndex(outcome, state.toolOutcomes, state.messages) >= 0,
                        state.toolOutcomeResults.sourceAdmitted(outcome) && !state.mutationSinceStart,
                        state.toolOutcomeResults.nativeDirectoryIndex(outcome, state.toolOutcomes, state.messages) >= 0));
            }
            if (Set.of("grep", "retrieve").contains(outcome.localCanonicalToolName())
                    && outcome.success() && !outcome.denied() && !outcome.sourcePaths().isEmpty()
                    && state.toolOutcomeResults.sourceAdmitted(outcome))
                searches.add(new SearchObservation(outcome,
                        state.toolOutcomeResults.executionIndex(outcome, state.toolOutcomes, state.messages) >= 0));
            if (!"read_file".equals(outcome.localCanonicalToolName()) || outcome.pathHint().isBlank()) continue;
            int index = state.toolOutcomeResults.executionIndex(outcome, state.toolOutcomes, state.messages);
            var shape = state.toolOutcomeResults.readShape(outcome);
            boolean usable = outcome.success() && !outcome.denied() && shape != null && shape.usable()
                    && !state.mutationSinceStart;
            boolean fullRefresh = usable && shape.full()
                    && state.toolOutcomeResults.nativeReadIndex(outcome, state.toolOutcomes, state.messages) >= 0;
            observations.add(new Observation(outcome, index >= 0, usable, fullRefresh,
                    shape == null ? "" : shape.privacyNotice(), shape != null && shape.full(),
                    shape == null ? "" : shape.excerpt(), shape != null && shape.admitted()));
        }
        return new ReadEvidenceCoverage(normalize(state.workspace), contract,
                LocalTurnTraceCapture.currentTraceId(), LocalTurnTraceCapture.currentTurnNumber(),
                observations, contract == null || state.workspace == null || !LocalTurnTraceCapture.isActive()
                    || LocalTurnTraceCapture.currentTraceId().isBlank(), state.summaryRequirement,
                state.toolOutcomes, searches, directories, preExecutionRejections);
    }

    /** Chronology is preserved across the existing bounded continuation, including unresolved gaps. */
    public ReadEvidenceCoverage followedBy(ReadEvidenceCoverage later) {
        if (later == null || later == EMPTY) return this;
        if (this == EMPTY) return later;
        List<Observation> combined = new ArrayList<>(observations);
        combined.addAll(later.observations);
        List<ToolOutcome> outcomes = new ArrayList<>(allOutcomes);
        outcomes.addAll(later.allOutcomes);
        List<SearchObservation> combinedSearches = new ArrayList<>(searches);
        combinedSearches.addAll(later.searches);
        List<DirectoryRead> combinedDirectories = new ArrayList<>(directories);
        combinedDirectories.addAll(later.directories);
        List<ToolOutcome> combinedRejections = new ArrayList<>(preExecutionRejections);
        combinedRejections.addAll(later.preExecutionRejections);
        boolean mismatch = !Objects.equals(workspace, later.workspace) || !Objects.equals(contract, later.contract)
                || !traceId.equals(later.traceId) || turn != later.turn;
        return new ReadEvidenceCoverage(workspace, contract, traceId, turn, combined,
                invalid || later.invalid || mismatch, summaryRequirement, outcomes, combinedSearches,
                combinedDirectories, combinedRejections);
    }

    public record DirectoryAssessment(EvidenceObligationVerifier.Result result, ToolOutcome observation) {}

    /** Only the latest bound observation of the requested directory may settle its contents. */
    public DirectoryAssessment assessDirectory(List<ToolOutcome> outcomes, Path currentWorkspace,
                                                TaskContract currentContract, String target) {
        if (!matches(outcomes, currentWorkspace, currentContract)
                || outcomes.size() != allOutcomes.size()) return missingDirectory("Directory evidence association is unavailable.");
        for (int i = 0; i < outcomes.size(); i++) {
            ToolOutcome outcome = outcomes.get(i);
            if (outcome != allOutcomes.get(i) || outcome.mutating() && !rejectedBeforeExecution(outcome)
                    || outcome.mutationEffects().hasEffects())
                return missingDirectory("Directory evidence association is unavailable.");
        }
        var paths = new ReadEvidenceLedger(workspace);
        List<ToolOutcome> listed = outcomes.stream()
                .filter(outcome -> "list_dir".equals(outcome.localCanonicalToolName())).toList();
        if (listed.size() != directories.size()) return missingDirectory("Directory observations are unavailable.");
        DirectoryRead latest = null;
        boolean earlierGap = false;
        for (int i = 0; i < directories.size(); i++) {
            DirectoryRead directory = directories.get(i);
            if (listed.get(i) != directory.outcome() || listed.stream()
                    .filter(outcome -> outcome == directory.outcome()).count() != 1)
                return missingDirectory("Directory observation identity is unavailable.");
            if (paths.sameEffectivePath(target, directory.outcome().pathHint())) {
                if (!directory.bound()) return missingDirectory("Directory observation was not bound to its executed call.");
                if (latest != null && (!latest.outcome().success() || !latest.admitted()
                        || latest.outcome().directoryObservation() == null
                        || !latest.outcome().directoryObservation().complete()
                        || !latest.outcome().directoryObservation().includesHiddenEntries())) earlierGap = true;
                latest = directory;
            }
        }
        if (latest == null) return missingDirectory("The requested directory was not observed.");
        ToolOutcome outcome = latest.outcome();
        if (!latest.bound()) return missingDirectory("Directory observation was not bound to an executed call.");
        if (outcome.denied()) return new DirectoryAssessment(
                EvidenceObligationVerifier.Result.blocked("The requested directory observation was blocked."), null);
        var observation = outcome.directoryObservation();
        if (!outcome.success() || !latest.admitted() || observation == null
                || !paths.sameEffectivePath(target, observation.resolvedTarget()))
            return missingDirectory("Successful admitted directory scope and completeness were not established.");
        if (earlierGap && !latest.nativeRefresh())
            return missingDirectory("An earlier directory evidence gap was not resolved by an authenticated refresh.");
        if (!observation.complete() || !observation.includesHiddenEntries())
            return new DirectoryAssessment(EvidenceObligationVerifier.Result.unsatisfied(
                    "Directory observation was limited. Complete contents remain unestablished."
                            + (observation.returnedEntries() == 0 ? " Emptiness remains unestablished." : "")), outcome);
        return new DirectoryAssessment(EvidenceObligationVerifier.Result.satisfied(
                "Directory contents were observed within the recorded scope, including hidden entries."), outcome);
    }

    private static DirectoryAssessment missingDirectory(String message) {
        return new DirectoryAssessment(EvidenceObligationVerifier.Result.unsatisfied(message), null);
    }

    private boolean rejectedBeforeExecution(ToolOutcome outcome) {
        return preExecutionRejections.stream().anyMatch(rejected -> rejected == outcome)
                && allOutcomes.stream().filter(candidate -> candidate == outcome).count() == 1;
    }

    /** Known read gaps cannot be erased by a later filesystem selection or a search snippet. */
    public ProjectSummaryEvidencePolicy.Assessment assessProjectSummary(
            List<ToolOutcome> outcomes, Path currentWorkspace, TaskContract currentContract) {
        if (!summaryMatches(outcomes, currentWorkspace, currentContract))
            return summaryResult(ProjectSummaryEvidencePolicy.Reason.ASSOCIATION_UNAVAILABLE,
                    "The current project evidence association is unavailable.", List.of());
        if (summaryRequirement.status() == ProjectSummaryEvidencePolicy.SelectionStatus.UNAVAILABLE)
            return summaryResult(ProjectSummaryEvidencePolicy.Reason.MISSING,
                    "Project summary anchor availability could not be checked before reading.", List.of());
        Set<String> targets = summaryTargets();
        if (targets.isEmpty())
            return summaryResult(ProjectSummaryEvidencePolicy.Reason.MISSING,
                    "Project summary requires source-bearing workspace content evidence.", List.of());
        List<String> recoverable = new ArrayList<>();
        boolean missing = false;
        for (String target : targets) {
            List<Observation> relevant = relevant(target);
            State status = state(relevant, true);
            if (status == State.BLOCKED)
                return summaryResult(ProjectSummaryEvidencePolicy.Reason.BLOCKED,
                        "Required current project source evidence was blocked.", List.of());
            if (status == State.AVAILABLE || relevant.isEmpty() && searched(target)) continue;
            missing = true;
            if (recoverable(target, relevant)) recoverable.add(target);
        }
        return missing ? summaryResult(ProjectSummaryEvidencePolicy.Reason.LIMITED,
                "Required project source evidence was missing, partial or not successfully refreshed.", recoverable)
                : summaryResult(ProjectSummaryEvidencePolicy.Reason.AVAILABLE,
                "Project source inspection was established without a known required read gap. "
                        + "This does not verify the meaning or completeness of the answer.", List.of());
    }

    private static ProjectSummaryEvidencePolicy.Assessment summaryResult(
            ProjectSummaryEvidencePolicy.Reason reason, String message, List<String> targets) {
        var result = reason == ProjectSummaryEvidencePolicy.Reason.AVAILABLE
                ? EvidenceObligationVerifier.Result.satisfied(message)
                : reason == ProjectSummaryEvidencePolicy.Reason.BLOCKED
                    ? EvidenceObligationVerifier.Result.blocked(message)
                    : EvidenceObligationVerifier.Result.unsatisfied(message);
        return new ProjectSummaryEvidencePolicy.Assessment(result, reason, targets);
    }

    private boolean summaryMatches(List<ToolOutcome> outcomes, Path currentWorkspace, TaskContract currentContract) {
        if (summaryRequirement == null || !matches(outcomes, currentWorkspace, currentContract)
                || outcomes.size() != allOutcomes.size()) return false;
        if (observations.stream().anyMatch(observation -> observation.outcome().success()
                && !observation.bound())) return false;
        if (searches.stream().anyMatch(search -> !search.bound())) return false;
        for (int i = 0; i < outcomes.size(); i++) {
            ToolOutcome outcome = outcomes.get(i);
            if (outcome != allOutcomes.get(i)
                    || outcomes.stream().filter(value -> value == outcome).count() != 1
                    || outcome.mutating() || outcome.mutationEffects().hasEffects()) return false;
        }
        return true;
    }

    private Set<String> summaryTargets() {
        Set<String> targets = new LinkedHashSet<>();
        if (summaryRequirement.status() == ProjectSummaryEvidencePolicy.SelectionStatus.FOUND)
            targets.add(summaryRequirement.anchor());
        observations.stream().filter(Observation::admitted)
                .forEach(observation -> targets.add(observation.outcome().pathHint()));
        searches.forEach(search -> targets.addAll(search.outcome().sourcePaths()));
        targets.addAll(contract.expectedTargets());
        targets.addAll(contract.sourceEvidenceTargets());
        return targets;
    }

    private List<Observation> relevant(String target) {
        var paths = new ReadEvidenceLedger(workspace);
        return observations.stream().filter(observation -> paths.sameEffectivePath(
                target, observation.outcome().pathHint())).toList();
    }

    private boolean searched(String target) {
        if (!safeSource(target)) return false;
        var paths = new ReadEvidenceLedger(workspace);
        return searches.stream().flatMap(search -> search.outcome().sourcePaths().stream())
                .anyMatch(path -> paths.sameEffectivePath(target, path));
    }

    private boolean safeSource(String target) {
        var policy = ProtectedPathPolicy.classify(workspace, target);
        return policy.hasPath() && policy.insideWorkspace() && !policy.workspaceEscape() && !policy.protectedPath();
    }

    private boolean recoverable(String target, List<Observation> relevant) {
        if (!safeSource(target) || relevant.stream().anyMatch(observation -> !observation.bound()
                || observation.outcome().denied() || !observation.privacyNotice().isBlank()
                || observation.outcome().success() && observation.excerpt().isBlank() && !observation.usable()))
            return false;
        try {
            return java.nio.file.Files.isRegularFile(workspace.resolve(target), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException invalidPath) { return false; }
    }

    /** Only admitted, bound observations are quoted, never arbitrary provider conclusions. */
    public String projectSummaryObservations(List<ToolOutcome> outcomes, Path currentWorkspace,
                                              TaskContract currentContract) {
        if (!summaryMatches(outcomes, currentWorkspace, currentContract)) return "";
        var output = new StringBuilder();
        int count = 0;
        for (String target : summaryTargets()) {
            if (count >= 3) break;
            if (!safeSource(target)) continue;
            List<Observation> relevant = relevant(target);
            if (relevant.isEmpty() || relevant.stream().anyMatch(observation -> !observation.bound()
                    || observation.outcome().denied() || !observation.privacyNotice().isBlank()
                    || observation.outcome().success() && observation.excerpt().isBlank())) continue;
            Observation selected = null;
            for (Observation observation : relevant) if (observation.outcome().success()) selected = observation;
            if (selected == null || selected.excerpt().isBlank()) continue;
            boolean earlier = selected != relevant.getLast();
            if (!output.isEmpty()) output.append("\n\n");
            output.append(earlier ? "Earlier observed excerpt, current refresh was not established" :
                    selected.full() ? "Observed source excerpt, not a complete answer" : "Partial observed excerpt")
                    .append(" from ").append(dev.talos.runtime.policy.ProtectedContentPolicy.sanitizeText(target))
                    .append(". Source text is quoted, not independently verified.\n");
            for (String line : selected.excerpt().split("\\R", -1)) output.append("> ").append(line).append('\n');
            count++;
        }
        return output.toString().strip();
    }

    /** Missing evidence and invalid association cannot be replaced by older successful outcomes. */
    public EvidenceObligationVerifier.Result assess(Set<String> targets, Set<String> mandatoryTargets,
                                                    List<ToolOutcome> outcomes, Path currentWorkspace,
                                                    TaskContract currentContract) {
        if (!matches(outcomes, currentWorkspace, currentContract))
            return EvidenceObligationVerifier.Result.unsatisfied("The current read evidence association is unavailable.");
        ReadEvidenceLedger paths = new ReadEvidenceLedger(workspace);
        boolean missing = false;
        for (String target : targets) {
            List<Observation> relevant = observations.stream()
                    .filter(observation -> paths.sameEffectivePath(target, observation.outcome().pathHint())).toList();
            if (relevant.isEmpty() && !mandatoryTargets.contains(target)) continue;
            State state = state(relevant);
            if (state == State.BLOCKED)
                return EvidenceObligationVerifier.Result.blocked("Required current source evidence was blocked.");
            if (state != State.AVAILABLE) missing = true;
        }
        if (missing) return EvidenceObligationVerifier.Result.unsatisfied("Required current source evidence was not established.");
        return EvidenceObligationVerifier.Result.satisfied("No known required read evidence gap remains.");
    }

    /** Superseded failures remain in the raw outcome and trace, only the verifier view is filtered. */
    public List<ToolOutcome> effectiveOutcomes(List<ToolOutcome> outcomes) {
        ReadEvidenceLedger paths = new ReadEvidenceLedger(workspace);
        return outcomes.stream().filter(outcome -> {
            if (outcome.success() || !"read_file".equals(outcome.localCanonicalToolName())) return true;
            List<Observation> relevant = observations.stream()
                    .filter(observation -> paths.sameEffectivePath(outcome.pathHint(), observation.outcome().pathHint()))
                    .toList();
            return state(relevant) != State.AVAILABLE;
        }).toList();
    }

    /** Runtime privacy notices survive containment without retaining the model answer. */
    public List<String> missingPrivacyNotices(List<ToolOutcome> outcomes, Path currentWorkspace,
                                             TaskContract currentContract) {
        if (!matches(outcomes, currentWorkspace, currentContract)) return List.of();
        Set<String> targets = new java.util.LinkedHashSet<>(contract.expectedTargets());
        targets.addAll(contract.sourceEvidenceTargets());
        if (summaryRequirement != null) {
            if (!summaryMatches(outcomes, currentWorkspace, currentContract)) return List.of();
            targets.addAll(summaryTargets());
            if (targets.isEmpty()) observations.stream().filter(observation -> !observation.privacyNotice().isBlank())
                    .forEach(observation -> targets.add(observation.outcome().pathHint()));
        }
        ReadEvidenceLedger paths = new ReadEvidenceLedger(workspace);
        Set<String> notices = new java.util.LinkedHashSet<>();
        for (String target : targets) {
            List<Observation> relevant = observations.stream()
                    .filter(observation -> paths.sameEffectivePath(target, observation.outcome().pathHint())).toList();
            if (state(relevant) == State.AVAILABLE) continue;
            relevant.stream().filter(Observation::bound).map(Observation::privacyNotice)
                    .filter(notice -> !notice.isBlank()).forEach(notices::add);
        }
        return List.copyOf(notices);
    }

    private boolean matches(List<ToolOutcome> outcomes, Path currentWorkspace, TaskContract currentContract) {
        if (!captured() || invalid || !Objects.equals(contract, currentContract)
                || currentWorkspace != null && !workspace.equals(normalize(currentWorkspace))
                || !LocalTurnTraceCapture.isActive() || !traceId.equals(LocalTurnTraceCapture.currentTraceId())
                || turn != LocalTurnTraceCapture.currentTurnNumber()) return false;
        List<ToolOutcome> reads = outcomes.stream()
                .filter(outcome -> "read_file".equals(outcome.localCanonicalToolName()) && !outcome.pathHint().isBlank())
                .toList();
        if (reads.size() != observations.size()) return false;
        for (int index = 0; index < reads.size(); index++) {
            ToolOutcome outcome = reads.get(index);
            if (outcome != observations.get(index).outcome()
                    || reads.stream().filter(candidate -> candidate == outcome).count() != 1) return false;
        }
        return true;
    }

    private static State state(List<Observation> observations) {
        return state(observations, false);
    }

    private static State state(List<Observation> observations, boolean requireComplete) {
        State result = State.MISSING;
        boolean limited = false;
        boolean unbound = false;
        for (Observation observation : observations) {
            if (!observation.bound()) unbound = true;
            if (unbound) {
                if (result != State.BLOCKED)
                    result = observation.outcome().denied() ? State.BLOCKED : State.MISSING;
                continue;
            }
            if (!observation.usable() || requireComplete && !observation.full()) {
                limited = true;
                if (result != State.BLOCKED)
                    result = observation.outcome().denied() ? State.BLOCKED : State.MISSING;
            } else if (!limited || observation.fullRefresh()) {
                limited = false;
                result = State.AVAILABLE;
            }
        }
        return result;
    }

    private static Path normalize(Path path) { return path == null ? null : path.toAbsolutePath().normalize(); }
    private enum State { MISSING, BLOCKED, AVAILABLE }
    private record SearchObservation(ToolOutcome outcome, boolean bound) {}
    private record DirectoryRead(ToolOutcome outcome, boolean bound, boolean admitted, boolean nativeRefresh) {}
    private record Observation(ToolOutcome outcome, boolean bound, boolean usable, boolean fullRefresh,
                               String privacyNotice, boolean full, String excerpt, boolean admitted) {}
}
