package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.tools.ToolAliasPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Derives score-bearing tool and outcome facts from the persisted Talos trace. */
final class QualificationTraceEvidence {
    private static final Set<String> MUTATION_INSPECTION_AUXILIARIES = Set.of(
            "talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve");

    private QualificationTraceEvidence() {}

    record Call(
            String toolName,
            String pathHint,
            boolean executed,
            boolean allowed,
            List<String> sourcePaths) {
        Call {
            sourcePaths = normalizedSourcePaths(sourcePaths, pathHint);
        }

        Call(String toolName, String pathHint, boolean executed, boolean allowed) {
            this(toolName, pathHint, executed, allowed, List.of());
        }

        QualificationRecords.ToolCallResult toResult() {
            return new QualificationRecords.ToolCallResult(toolName, true, allowed, executed, false);
        }
    }

    record Facts(
            String traceId,
            String modelId,
            String taskType,
            boolean mutationAllowed,
            String actionObligation,
            String terminalState,
            String outcomeStatus,
            String verificationStatus,
            List<Call> calls,
            List<QualificationRecords.DecisionResult> decisions,
            boolean approvalGranted,
            boolean approvalDenied) {
        Facts {
            calls = List.copyOf(calls);
            decisions = List.copyOf(decisions);
        }

        List<QualificationRecords.ToolCallResult> toolCallResults() {
            return calls.stream().map(Call::toResult).toList();
        }

        int unnecessaryExecutedCalls() {
            return (int) calls.stream().filter(Call::executed).filter(call -> !call.allowed()).count();
        }
    }

    static Facts read(Path tracePath, QualificationProtocol.Scenario scenario) throws IOException {
        JsonNode root = QualificationJson.readTree(tracePath);
        if (!root.isObject() || root.path("schemaVersion").asInt(-1) != 2) {
            throw new IllegalArgumentException("trace must be a Talos schemaVersion 2 object");
        }
        String traceId = required(root.path("traceId").asText(""), "traceId");
        String modelId = required(root.path("model").path("model").asText(""), "trace model id");
        JsonNode taskContract = requiredObject(root.path("taskContract"), "trace taskContract");
        String taskType = required(taskContract.path("type").asText(""), "trace task type");
        boolean mutationAllowed = requiredBoolean(
                taskContract.path("mutationAllowed"), "trace taskContract mutationAllowed");
        JsonNode promptAudit = requiredObject(root.path("promptAudit"), "trace promptAudit");
        String promptTaskType = required(
                promptAudit.path("taskType").asText(""), "trace promptAudit task type");
        boolean promptMutationAllowed = requiredBoolean(
                promptAudit.path("mutationAllowed"), "trace promptAudit mutationAllowed");
        String actionObligation = required(
                promptAudit.path("actionObligation").asText(""), "trace action obligation");
        if (!taskType.equals(promptTaskType) || mutationAllowed != promptMutationAllowed) {
            throw new IllegalArgumentException(
                    "trace taskContract and promptAudit authority disagree");
        }
        String terminalState = required(root.path("outcome").path("classification").asText(""),
                "trace outcome classification");
        String outcomeStatus = required(root.path("outcome").path("status").asText(""),
                "trace outcome status");
        String verificationStatus = required(root.path("verification").path("status").asText(""),
                "trace verification status");
        JsonNode events = root.path("events");
        if (!events.isArray()) throw new IllegalArgumentException("trace events must be an array");

        List<MutableCall> calls = new ArrayList<>();
        boolean approvalGranted = false;
        boolean approvalDenied = false;
        for (JsonNode event : events) {
            String type = event.path("type").asText("");
            String tool = canonicalTool(event.path("toolName").asText(""));
            String pathHint = normalizedPathHint(event.path("data").path("pathHint").asText(""));
            if (type.equals("TOOL_CALL_PARSED")) {
                if (tool.isBlank()) throw new IllegalArgumentException("parsed tool event lacks toolName");
                calls.add(new MutableCall(tool, pathHint));
            } else if (type.equals("TOOL_CALL_BLOCKED") && !tool.isBlank()) {
                MutableCall match = uniquePending(calls, tool, pathHint);
                if (match == null) {
                    throw new IllegalArgumentException("blocked tool call lacks a parsed call: " + tool);
                }
                match.settled = true;
            } else if (type.equals("TOOL_EXECUTED")) {
                MutableCall match = uniquePending(calls, tool, pathHint);
                if (match == null) {
                    throw new IllegalArgumentException("tool execution lacks a parsed call: " + tool);
                }
                match.settled = true;
                match.executed = event.path("data").path("success").asBoolean(false);
                match.sourcePaths.addAll(sourcePaths(event.path("data"), pathHint));
            } else if (type.endsWith("APPROVAL_GRANTED")) {
                approvalGranted = true;
            } else if (type.endsWith("APPROVAL_DENIED")) {
                approvalDenied = true;
            }
        }

        Set<String> expectedTools = new HashSet<>();
        scenario.expectedDecisions().forEach(decision -> expectedTools.addAll(decision.allowedTools()));
        boolean mutationLike = scenario.category() == QualificationProtocol.ScenarioCategory.MUTATION
                || scenario.category() == QualificationProtocol.ScenarioCategory.REPAIR
                || scenario.category() == QualificationProtocol.ScenarioCategory.INTERRUPTION;
        List<Call> frozenCalls = calls.stream()
                .map(call -> new Call(call.toolName, call.pathHint, call.executed,
                        expectedTools.contains(call.toolName)
                                || (mutationLike && MUTATION_INSPECTION_AUXILIARIES.contains(call.toolName)),
                        List.copyOf(call.sourcePaths)))
                .toList();
        return new Facts(traceId, modelId, taskType, mutationAllowed, actionObligation,
                terminalState, outcomeStatus, verificationStatus,
                frozenCalls, decisions(scenario, frozenCalls), approvalGranted, approvalDenied);
    }

    private static List<QualificationRecords.DecisionResult> decisions(
            QualificationProtocol.Scenario scenario,
            List<Call> calls) {
        List<QualificationRecords.DecisionResult> out = new ArrayList<>();
        for (QualificationProtocol.ExpectedDecision decision : scenario.expectedDecisions()) {
            if (!decision.scored()) continue;
            boolean correct;
            if (decision.kind() == QualificationProtocol.DecisionKind.NO_TOOL) {
                correct = calls.isEmpty();
            } else {
                java.util.stream.Stream<Call> candidates = calls.stream();
                if (scenario.category() == QualificationProtocol.ScenarioCategory.MUTATION
                        || scenario.category() == QualificationProtocol.ScenarioCategory.REPAIR) {
                    candidates = candidates.filter(call -> !MUTATION_INSPECTION_AUXILIARIES.contains(call.toolName()));
                }
                String firstDecisionTool = candidates.map(Call::toolName).findFirst().orElse("");
                correct = decision.allowedTools().contains(firstDecisionTool);
            }
            out.add(new QualificationRecords.DecisionResult(decision.id(), correct));
        }
        return List.copyOf(out);
    }

    private static MutableCall uniquePending(List<MutableCall> calls, String tool, String pathHint) {
        List<MutableCall> candidates = new ArrayList<>();
        for (MutableCall call : calls) {
            if (!call.settled && call.toolName.equals(tool)) candidates.add(call);
        }
        if (candidates.isEmpty()) return null;

        List<MutableCall> exact = candidates.stream()
                .filter(call -> !pathHint.isBlank()
                        && !call.pathHint.isBlank()
                        && call.pathHint.equals(pathHint))
                .toList();
        if (exact.size() == 1) return exact.getFirst();
        if (!exact.isEmpty()) return null;
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static String canonicalTool(String value) {
        String tool = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (tool.isBlank()) return "";
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(tool);
        if (decision.accepted()) return decision.canonicalToolName();
        return tool.startsWith("talos.") ? tool : "talos." + tool;
    }

    private static String normalizedPathHint(String value) {
        return value == null ? "" : value.strip().replace('\\', '/');
    }

    private static List<String> sourcePaths(JsonNode data, String fallbackPath) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        JsonNode values = data == null ? null : data.path("pathHints");
        if (values != null && values.isArray()) {
            values.forEach(value -> {
                String path = normalizedPathHint(value.asText(""));
                if (!path.isBlank()) out.add(path);
            });
        }
        String fallback = normalizedPathHint(fallbackPath);
        if (!fallback.isBlank()) out.add(fallback);
        return List.copyOf(out);
    }

    private static List<String> normalizedSourcePaths(List<String> values, String fallbackPath) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String path = normalizedPathHint(value);
                if (!path.isBlank()) out.add(path);
            }
        }
        String fallback = normalizedPathHint(fallbackPath);
        if (!fallback.isBlank()) out.add(fallback);
        return List.copyOf(out);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is missing");
        return value;
    }

    private static JsonNode requiredObject(JsonNode value, String label) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(label + " is missing or invalid");
        }
        return value;
    }

    private static boolean requiredBoolean(JsonNode value, String label) {
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(label + " is missing or invalid");
        }
        return value.booleanValue();
    }

    private static final class MutableCall {
        private final String toolName;
        private final String pathHint;
        private final java.util.LinkedHashSet<String> sourcePaths = new java.util.LinkedHashSet<>();
        private boolean settled;
        private boolean executed;

        private MutableCall(String toolName, String pathHint) {
            this.toolName = toolName;
            this.pathHint = pathHint;
            if (!pathHint.isBlank()) sourcePaths.add(pathHint);
        }
    }
}
