package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolError;

/** Truthfulness outcome for unsupported document reads through the read-file surface. */
public record UnsupportedDocumentCapabilityOutcome(boolean limited) {

    public static UnsupportedDocumentCapabilityOutcome assess(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) {
            return new UnsupportedDocumentCapabilityOutcome(false);
        }
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (outcome.success()) continue;
            if (ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode())) {
                return new UnsupportedDocumentCapabilityOutcome(true);
            }
        }
        return new UnsupportedDocumentCapabilityOutcome(false);
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }
}
