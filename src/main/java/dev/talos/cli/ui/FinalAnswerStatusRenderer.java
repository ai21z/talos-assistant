package dev.talos.cli.ui;

import dev.talos.runtime.trace.FinalAnswerStatus;
import dev.talos.runtime.trace.LocalTurnTrace;

import java.util.List;

/** One wording is used for current and persisted final status. */
public final class FinalAnswerStatusRenderer {
    private FinalAnswerStatusRenderer() {}

    public static List<String> lines(LocalTurnTrace trace) {
        var status = FinalAnswerStatus.decode(trace);
        if (!status.available()) return List.of("Answer delivery: unavailable", "Whole-request coverage: unavailable",
                "Terminal sink delivery: not recorded in this trace");
        String delivery = switch (status.delivery()) {
            case NO_KNOWN_LIMIT -> "no known limit";
            case UNAVAILABLE -> "unavailable";
            case LIMITED -> "limited, " + String.join(", ", status.causes().stream().map(cause -> switch (cause) {
                case PROVIDER_LENGTH -> "provider output limit";
                case RUNTIME_SAFE_CAP -> "runtime output limit";
                case LOCAL_PRESENTATION_LIMIT -> "presentation limit";
                case INCOMPLETE_PROVIDER_GENERATION -> "uncommitted provider answer withheld";
            }).toList()) + (status.associationAvailable() ? "" : ", some association unavailable");
        };
        return List.of("Work outcome: " + status.operational().status() + " ("
                        + status.operational().classification() + ")",
                "Answer delivery: " + delivery + " (selected answer limits)", "Whole-request coverage: not assessed",
                "Terminal sink delivery: not recorded in this trace");
    }
}
