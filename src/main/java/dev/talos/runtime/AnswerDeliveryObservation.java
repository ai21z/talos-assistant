package dev.talos.runtime;

import java.util.UUID;

/** Sink acceptance is recorded separately from frozen execution and answer coverage. */
public record AnswerDeliveryObservation(UUID presentationId, int turn, State state) {
    public enum State { NOT_ATTEMPTED, NO_DETECTED_SINK_ERROR, FAILED_OR_UNCERTAIN }
}
