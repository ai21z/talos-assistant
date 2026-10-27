package dev.talos.runtime;

import java.util.List;
import java.util.stream.Stream;

/** Process-local visibility for settlement-listener publication failures. */
public record TurnPublicationHealth(
        int completedListeners,
        int skippedListeners,
        int degradedListeners,
        List<TurnPublicationReceipt.Reason> reasons,
        List<String> exceptionClasses
) {
    public TurnPublicationHealth {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        exceptionClasses = exceptionClasses == null ? List.of() : List.copyOf(exceptionClasses);
    }

    public boolean healthy() {
        return degradedListeners == 0;
    }

    public static TurnPublicationHealth internalFailure(String exceptionClass) {
        return new TurnPublicationHealth(
                0, 0, 1, List.of(TurnPublicationReceipt.Reason.TURN_SETTLEMENT_FAILED),
                exceptionClass == null || exceptionClass.isBlank()
                        ? List.of()
                        : List.of(exceptionClass));
    }

    public static TurnPublicationHealth listenerFailure(String exceptionClass) {
        return new TurnPublicationHealth(
                0,
                0,
                1,
                List.of(TurnPublicationReceipt.Reason.LISTENER_EXCEPTION),
                exceptionClass == null || exceptionClass.isBlank()
                        ? List.of()
                        : List.of(exceptionClass));
    }

    public static TurnPublicationHealth combine(
            TurnPublicationHealth first,
            TurnPublicationHealth second
    ) {
        if (first == null) return second;
        if (second == null) return first;
        return new TurnPublicationHealth(
                first.completedListeners + second.completedListeners,
                first.skippedListeners + second.skippedListeners,
                first.degradedListeners + second.degradedListeners,
                Stream.concat(first.reasons.stream(), second.reasons.stream()).toList(),
                Stream.concat(
                        first.exceptionClasses.stream(),
                        second.exceptionClasses.stream()).toList());
    }
}
