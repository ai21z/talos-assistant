package dev.talos.runtime;

import dev.talos.runtime.trace.LocalTurnTrace;

/**
 * Read-only, fail-closed observation of one exact local trace artifact.
 * The digest covers every byte of the validated trace file. This binds
 * observed bytes for diagnostics; it does not claim that the trace and its
 * companion JSONL row were published transactionally.
 */
public record TraceArtifactObservation(
        State state,
        LocalTurnTrace trace,
        String artifactSha256
) {
    public enum State {
        ABSENT,
        EXACT,
        INVALID,
        UNAVAILABLE
    }

    public TraceArtifactObservation {
        state = state == null ? State.UNAVAILABLE : state;
        artifactSha256 = artifactSha256 == null ? "" : artifactSha256;
        if (state != State.EXACT) {
            trace = null;
            artifactSha256 = "";
        } else if (trace == null
                || !artifactSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "an exact trace observation requires a trace and digest");
        }
    }

    public static TraceArtifactObservation absent() {
        return new TraceArtifactObservation(State.ABSENT, null, "");
    }

    public static TraceArtifactObservation exact(
            LocalTurnTrace trace,
            String artifactSha256
    ) {
        return new TraceArtifactObservation(State.EXACT, trace, artifactSha256);
    }

    public static TraceArtifactObservation invalid() {
        return new TraceArtifactObservation(State.INVALID, null, "");
    }

    public static TraceArtifactObservation unavailable() {
        return new TraceArtifactObservation(State.UNAVAILABLE, null, "");
    }

    public boolean exact() {
        return state == State.EXACT;
    }
}
