package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.StaticTaskVerifier;
import dev.talos.runtime.verification.StaticWebInteractionVerifier;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolAliasPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class StaticWebContinuationPlanner {
    private StaticWebContinuationPlanner() {
    }

    record Plan(
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            ChatRequestControls controls,
            String retryName,
            Optional<PendingActionObligation> pendingActionObligation,
            List<String> missingTargets
    ) {
        Plan {
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
            controls = controls == null ? ChatRequestControls.defaults() : controls;
            retryName = retryName == null ? "" : retryName;
            pendingActionObligation = Objects.requireNonNullElse(pendingActionObligation, Optional.empty());
            missingTargets = missingTargets == null ? List.of() : List.copyOf(missingTargets);
        }
    }

    private record VerificationContinuation(
            TaskVerificationResult verification,
            List<String> repairTargets,
            boolean fullRewriteRepair
    ) {
        VerificationContinuation {
            repairTargets = repairTargets == null ? List.of() : List.copyOf(repairTargets);
        }
    }

    static Optional<Plan> nextPlan(LoopState state, List<ToolSpec> baseTools) {
        Optional<Plan> directoryOnly = directoryOnlyPlan(state, baseTools);
        if (directoryOnly.isPresent()) return directoryOnly;
        return verificationFailurePlan(state, baseTools);
    }

    static Optional<Plan> directoryOnlyPlan(LoopState state, List<ToolSpec> baseTools) {
        if (!shouldContinueAfterDirectoryOnlyMutation(state)) return Optional.empty();
        List<ToolSpec> narrowed = filterTools(baseTools, List.of("talos.write_file"));
        if (narrowed.isEmpty()) {
            narrowed = filterTools(baseTools, List.of("talos.write_file", "talos.edit_file"));
        }
        List<ToolSpec> tools = narrowed.isEmpty()
                ? safeTools(baseTools)
                : narrowed;
        return Optional.of(new Plan(
                staticWebCreationContinuationMessages(state),
                tools,
                staticWebCreationContinuationControls(state, tools),
                "static-web-directory-only-continuation",
                Optional.empty(),
                List.of()));
    }

    static Optional<Plan> verificationFailurePlan(LoopState state, List<ToolSpec> baseTools) {
        Optional<VerificationContinuation> continuation = verificationContinuation(state);
        if (continuation.isEmpty()) return Optional.empty();
        VerificationContinuation value = continuation.get();
        List<String> allowedTools = value.fullRewriteRepair()
                ? List.of("talos.write_file")
                : List.of("talos.write_file", "talos.edit_file");
        List<ToolSpec> narrowed = filterTools(baseTools, allowedTools);
        List<ToolSpec> tools = narrowed.isEmpty()
                ? safeTools(baseTools)
                : narrowed;
        Optional<PendingActionObligation> obligation = value.repairTargets().isEmpty()
                ? Optional.empty()
                : Optional.of(value.fullRewriteRepair()
                        ? PendingActionObligation.staticRepairTargets(
                                value.repairTargets(),
                                staticWebVerificationFailureContext(value.verification()))
                        : PendingActionObligation.expectedTargets(
                                value.repairTargets(),
                                staticWebVerificationFailureContext(value.verification())));
        if (value.fullRewriteRepair()) {
            state.staticWebFullRewriteRequiredTargets.addAll(value.repairTargets());
        }
        LocalTurnTraceCapture.recordRepair(
                "PLANNED",
                "STATIC_VERIFICATION_REPAIR: static-web verification continuation targets "
                        + String.join(", ", value.repairTargets()));
        return Optional.of(new Plan(
                staticWebVerificationContinuationMessages(state, value),
                tools,
                staticWebCreationContinuationControls(state, tools),
                "static-web-verification-continuation",
                obligation,
                value.repairTargets()));
    }

    static boolean staticWebVerificationAlreadyPasses(LoopState state) {
        TaskVerificationResult verification = staticWebVerification(state);
        return verification.status() == TaskVerificationStatus.PASSED;
    }

    static boolean mutatedSmallWebFile(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || !outcome.success() || !outcome.mutating()) return false;
        String toolName = canonicalToolName(outcome.toolName());
        if (("talos.write_file".equals(toolName) || "talos.edit_file".equals(toolName))
                && StaticWebCapabilityProfile.isSmallWebFile(outcome.pathHint())) {
            return true;
        }
        WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
        if (plan == null || plan.pathEffects().isEmpty()) return false;
        for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
            if (effect != null && StaticWebCapabilityProfile.isSmallWebFile(effect.path())) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldContinueAfterDirectoryOnlyMutation(LoopState state) {
        if (state == null || state.toolOutcomes.isEmpty()) return false;
        TaskContract contract = taskContract(state);
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) return false;
        if (!StaticWebCapabilityProfile.looksFunctionalWebTask(contract)) return false;
        if (staticWebVerificationAlreadyPasses(state)) return false;
        boolean hasDirectoryMutation = false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            if (mutatedSmallWebFile(outcome)) {
                return false;
            }
            if (successfulDirectoryMutation(outcome)) {
                hasDirectoryMutation = true;
            }
        }
        return hasDirectoryMutation;
    }

    private static boolean successfulDirectoryMutation(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || !outcome.success() || !outcome.mutating()) return false;
        String toolName = canonicalToolName(outcome.toolName());
        if ("talos.mkdir".equals(toolName)) return true;
        WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
        if (plan == null) return false;
        if (plan.operationKind() == WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY) return true;
        for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
            if (effect != null
                    && effect.operationKind() == WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY) {
                return true;
            }
        }
        return false;
    }

    private static List<ChatMessage> staticWebCreationContinuationMessages(LoopState state) {
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (userTask == null || userTask.isBlank()) {
            TaskContract contract = taskContract(state);
            userTask = contract == null ? "Create the requested static web artifact." : contract.originalUserRequest();
        }
        String directorySummary = successfulDirectoryMutationSummary(state);
        StringBuilder frame = new StringBuilder();
        frame.append("[StaticWebCreationContinuation]\n")
                .append("A directory mutation succeeded, but a website/app creation request is not complete ")
                .append("until actual static web files are written.\n")
                .append("Do not answer in prose instead of calling a file mutation tool.\n")
                .append("Write the HTML/CSS/JavaScript surface now. Prefer index.html, styles.css, and script.js ")
                .append("unless the user requested different names.\n")
                .append("Do not claim completion until tool-backed file writes have executed and static verification can run.");
        if (!directorySummary.isBlank()) {
            frame.append("\nSuccessful directory mutation: ").append(directorySummary);
        }
        return List.of(
                ChatMessage.system("""
                        You are Talos, a local-first workspace assistant.
                        This is a bounded static-web creation continuation after a directory-only mutation.
                        Directory creation alone does not satisfy a website/app creation request.
                        Use the visible write-file tool now to create the actual web files.
                        """),
                ChatMessage.system(frame.toString()),
                ChatMessage.user("Current user request:\n"
                        + (userTask == null ? "" : userTask.strip())
                        + "\n\nCall talos.write_file now for the actual static web files."));
    }

    private static List<ChatMessage> staticWebVerificationContinuationMessages(
            LoopState state,
            VerificationContinuation continuation
    ) {
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (userTask == null || userTask.isBlank()) {
            TaskContract contract = taskContract(state);
            userTask = contract == null ? "Create the requested static web artifact." : contract.originalUserRequest();
        }
        TaskVerificationResult verification = continuation == null ? null : continuation.verification();
        List<String> problems = verification == null ? List.of() : verification.problems();
        List<String> targets = continuation == null ? List.of() : continuation.repairTargets();
        StringBuilder frame = new StringBuilder();
        frame.append("[StaticWebVerificationContinuation]\n")
                .append("Static verification found the current web artifact incomplete after a successful mutation.\n")
                .append("Continue the same user request with file mutation tools. Do not answer in prose.\n");
        if (!targets.isEmpty()) {
            frame.append(continuation.fullRewriteRepair()
                            ? "Static web repair target files: "
                            : "Missing or unmutated target files: ")
                    .append(String.join(", ", targets))
                    .append('\n');
        }
        if (!problems.isEmpty()) {
            frame.append("Verification problems:\n");
            for (String problem : problems) {
                if (problem == null || problem.isBlank()) continue;
                frame.append("- ").append(problem.strip()).append('\n');
            }
        }
        if (continuation != null && continuation.fullRewriteRepair()) {
            frame.append("Repair the listed static-web verification problems now. Preserve the requested ")
                    .append("trigger/output binding when present and use complete file content for each ")
                    .append("listed repair target.");
        } else {
            frame.append("Write or repair the missing static web assets now. ")
                    .append("For linked CSS/JavaScript files, create the exact linked filenames.");
        }
        String toolInstruction = continuation != null && continuation.fullRewriteRepair()
                ? "Call talos.write_file now for the listed static web repair target files."
                : "Call talos.write_file or talos.edit_file now for the missing static web target files.";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("""
                You are Talos, a local-first workspace assistant.
                This is a bounded static-web verification continuation.
                The prior mutation wrote part of the requested web artifact, but static verification found missing linked assets or structural web files.
                Use the visible file mutation tool(s) now. Do not claim completion until tool-backed changes have executed.
                """));
        messages.add(ChatMessage.system(frame.toString().stripTrailing()));
        StaticRepairReadbackContext.render(state, targets)
                .ifPresent(readbacks -> messages.add(ChatMessage.system(readbacks)));
        messages.add(ChatMessage.user(staticWebVerificationUserInstruction(targets, toolInstruction, userTask)));
        return messages;
    }

    private static String staticWebVerificationUserInstruction(
            List<String> targets,
            String toolInstruction,
            String userTask
    ) {
        String targetList = targets == null || targets.isEmpty() ? "(unknown)" : String.join(", ", targets);
        String safeToolInstruction = toolInstruction == null || toolInstruction.isBlank()
                ? "Use the visible file mutation tool now."
                : toolInstruction.strip();
        return "Repair exactly the listed static-web target path(s): " + targetList + ".\n"
                + safeToolInstruction + "\n"
                + "Do not write any other file in this continuation.\n\n"
                + "Original user request:\n"
                + (userTask == null ? "" : userTask.strip());
    }

    private static String staticWebVerificationFailureContext(TaskVerificationResult verification) {
        if (verification == null || verification.status() != TaskVerificationStatus.FAILED) return "";
        String summary = verification.summary() == null || verification.summary().isBlank()
                ? "Static verification failed."
                : verification.summary().strip();
        StringBuilder out = new StringBuilder();
        out.append("[Task incomplete: Static verification failed - ")
                .append(summary)
                .append("]");
        List<String> problems = verification.problems();
        if (problems != null && !problems.isEmpty()) {
            out.append("\n\nUnresolved static verification problems:");
            for (String problem : problems) {
                if (problem == null || problem.isBlank()) continue;
                out.append("\n- ").append(problem.strip());
            }
        }
        out.append("\n\nThe requested task is not verified complete.");
        return out.toString();
    }

    private static ChatRequestControls staticWebCreationContinuationControls(
            LoopState state,
            List<ToolSpec> tools
    ) {
        boolean required = state != null
                && state.ctx != null
                && state.ctx.llm() != null
                && state.ctx.llm().supportsRequiredToolChoice()
                && hasMutatingTool(tools);
        return new ChatRequestControls(
                required ? ToolChoiceMode.REQUIRED : ToolChoiceMode.AUTO,
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of("static-web-directory-only-continuation"));
    }

    private static String successfulDirectoryMutationSummary(LoopState state) {
        if (state == null || state.toolOutcomes.isEmpty()) return "";
        for (int i = state.toolOutcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = state.toolOutcomes.get(i);
            if (!successfulDirectoryMutation(outcome)) continue;
            String summary = outcome.summary() == null ? "" : outcome.summary().strip();
            if (!summary.isBlank()) return summary;
            return outcome.pathHint() == null ? "" : outcome.pathHint().strip();
        }
        return "";
    }

    private static Optional<VerificationContinuation> verificationContinuation(LoopState state) {
        if (state == null || state.workspace == null) return Optional.empty();
        TaskContract contract = taskContract(state);
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) {
            return Optional.empty();
        }
        if (!looksContinuationEligibleStaticWebTask(contract)) return Optional.empty();
        if (!hasSuccessfulSmallWebFileMutation(state)) return Optional.empty();
        TaskVerificationResult verification = staticWebVerification(state);
        if (verification.status() != TaskVerificationStatus.FAILED) return Optional.empty();
        List<String> missingTargets = missingStaticWebTargets(verification, state);
        if (!missingTargets.isEmpty()) {
            return Optional.of(new VerificationContinuation(verification, missingTargets, false));
        }
        List<String> interactionRepairTargets = interactionRepairTargets(verification, state, contract);
        if (interactionRepairTargets.isEmpty()) return Optional.empty();
        return Optional.of(new VerificationContinuation(verification, interactionRepairTargets, true));
    }

    private static List<String> interactionRepairTargets(
            TaskVerificationResult verification,
            LoopState state,
            TaskContract contract
    ) {
        if (contract == null
                || StaticWebInteractionVerifier.detectBinding(contract.originalUserRequest()).isEmpty()) {
            return List.of();
        }
        if (!looksLikeInteractionVerificationFailure(verification)) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> expected = contract.expectedTargets().stream()
                .map(ToolCallSupport::normalizePath)
                .filter(StaticWebCapabilityProfile::isSmallWebFile)
                .toList();
        boolean needsCss = hasCssProblem(verification);
        for (String target : expected) {
            String lower = target.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".js")) {
                out.add(target);
            } else if (needsCss && lower.endsWith(".css")) {
                out.add(target);
            }
        }
        if (needsCss) {
            out.addAll(optionalCssRepairTargets(contract));
        }
        if (out.isEmpty()) {
            for (String target : successfulSmallWebMutationKeys(state)) {
                String display = ExpectedTargetProgressAccounting.displayExpectedTargetForKey(expected, target);
                if (display.isBlank()) display = target;
                String lower = display.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".js")
                        || (needsCss && lower.endsWith(".css"))) {
                    out.add(display);
                }
            }
        }
        return out.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .filter(StaticWebCapabilityProfile::isSmallWebFile)
                .sorted()
                .toList();
    }

    private static List<String> optionalCssRepairTargets(TaskContract contract) {
        if (contract == null || contract.originalUserRequest().isBlank()) return List.of();
        TaskIntent intent = TaskContractResolver.intentFromUserRequest(contract.originalUserRequest());
        return intent.targets().pathsByRole(TargetRole.MAY_MUTATE).stream()
                .map(ToolCallSupport::normalizePath)
                .filter(path -> !path.isBlank())
                .filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".css"))
                .filter(StaticWebCapabilityProfile::isSmallWebFile)
                .sorted()
                .toList();
    }

    private static boolean looksLikeInteractionVerificationFailure(TaskVerificationResult verification) {
        if (verification == null || verification.status() != TaskVerificationStatus.FAILED) return false;
        String haystack = ((verification.summary() == null ? "" : verification.summary()) + "\n"
                + String.join("\n", verification.problems()) + "\n"
                + String.join("\n", verification.facts())).toLowerCase(Locale.ROOT);
        return haystack.contains("static interaction")
                || haystack.contains("browser behavior")
                || haystack.contains("click handler")
                || haystack.contains("visible text")
                || haystack.contains("trigger")
                || haystack.contains("output");
    }

    private static boolean looksContinuationEligibleStaticWebTask(TaskContract contract) {
        if (StaticWebCapabilityProfile.looksFunctionalWebTask(contract)) return true;
        return StaticWebInteractionVerifier.detectBinding(contract.originalUserRequest()).isPresent();
    }

    private static boolean hasCssProblem(TaskVerificationResult verification) {
        if (verification == null) return false;
        String haystack = ((verification.summary() == null ? "" : verification.summary()) + "\n"
                + String.join("\n", verification.problems())).toLowerCase(Locale.ROOT);
        return haystack.contains("css");
    }

    private static List<String> missingStaticWebTargets(TaskVerificationResult verification, LoopState state) {
        if (verification == null || verification.problems().isEmpty()) return List.of();
        Set<String> satisfied = successfulSmallWebMutationKeys(state);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        LinkedHashSet<String> exactTargets = new LinkedHashSet<>();
        for (String problem : verification.problems()) {
            if (problem == null || problem.isBlank()) continue;
            String lower = problem.toLowerCase(Locale.ROOT);
            Set<String> problemTargets = addBacktickStaticWebTargets(problem, targets);
            problemTargets.addAll(addPlainPrefixStaticWebTargets(problem, targets));
            exactTargets.addAll(problemTargets);
            if ((lower.contains("css file") || lower.contains("css target"))
                    && !hasTargetWithExtension(problemTargets, ".css")) {
                targets.add("styles.css");
            }
            if (lower.contains("javascript file") || lower.contains("js file")
                    || lower.contains("javascript target") || lower.contains("js target")) {
                if (!hasTargetWithExtension(problemTargets, ".js")) {
                    targets.add("script.js");
                }
            }
            if ((lower.contains("html file") || lower.contains("html target"))
                    && !hasTargetWithExtension(problemTargets, ".html")
                    && !hasTargetWithExtension(problemTargets, ".htm")) {
                targets.add("index.html");
            }
        }
        exactTargets.addAll(addLinkedMissingStaticWebAssetsFromMutatedHtml(state, targets));
        removeConventionalFallbackWhenExactTargetExists(targets, exactTargets, "script.js", ".js");
        removeConventionalFallbackWhenExactTargetExists(targets, exactTargets, "styles.css", ".css");
        removeConventionalFallbackWhenExactTargetExists(targets, exactTargets, "index.html", ".html");
        return targets.stream()
                .map(ToolCallSupport::normalizePath)
                .filter(target -> !target.isBlank())
                .filter(StaticWebCapabilityProfile::isSmallWebFile)
                .filter(target -> !satisfied.contains(normalizeExpectedTargetKey(target)))
                .sorted()
                .toList();
    }

    private static Set<String> addLinkedMissingStaticWebAssetsFromMutatedHtml(LoopState state, Set<String> targets) {
        LinkedHashSet<String> added = new LinkedHashSet<>();
        if (state == null || state.workspace == null || targets == null) return added;
        Path root = state.workspace.toAbsolutePath().normalize();
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (!mutatedSmallWebFile(outcome)) continue;
            String htmlPath = ToolCallSupport.normalizePath(outcome.pathHint());
            if (!(htmlPath.endsWith(".html") || htmlPath.endsWith(".htm"))) continue;
            try {
                Path resolved = root.resolve(htmlPath).toAbsolutePath().normalize();
                if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) continue;
                String html = Files.readString(resolved);
                for (String linked : linkedStaticWebAssets(html)) {
                    String target = resolveLinkedAssetAgainstHtmlPath(htmlPath, linked);
                    if (target.isBlank()) continue;
                    Path linkedPath = root.resolve(target).toAbsolutePath().normalize();
                    if (!linkedPath.startsWith(root) || Files.isRegularFile(linkedPath)) continue;
                    targets.add(target);
                    added.add(target);
                }
            } catch (Exception ignored) {
                // Verification already reports the failure; missing target inference is best effort.
            }
        }
        return added;
    }

    private static List<String> linkedStaticWebAssets(String html) {
        if (html == null || html.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String href : htmlAttributeValues(html, "href")) {
            String normalized = normalLinkedAssetCandidate(href);
            if (normalized.endsWith(".css")) out.add(normalized);
        }
        for (String src : htmlAttributeValues(html, "src")) {
            String normalized = normalLinkedAssetCandidate(src);
            if (normalized.endsWith(".js")) out.add(normalized);
        }
        return out.stream().toList();
    }

    private static List<String> htmlAttributeValues(String html, String attribute) {
        if (html == null || html.isBlank() || attribute == null || attribute.isBlank()) return List.of();
        String lower = html.toLowerCase(Locale.ROOT);
        String needle = attribute.toLowerCase(Locale.ROOT) + "=";
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < lower.length()) {
            int index = lower.indexOf(needle, start);
            if (index < 0) break;
            int valueStart = index + needle.length();
            while (valueStart < html.length() && Character.isWhitespace(html.charAt(valueStart))) {
                valueStart++;
            }
            if (valueStart >= html.length()) break;
            char quote = html.charAt(valueStart);
            if (quote == '"' || quote == '\'') {
                int valueEnd = html.indexOf(quote, valueStart + 1);
                if (valueEnd < 0) break;
                out.add(html.substring(valueStart + 1, valueEnd));
                start = valueEnd + 1;
            } else {
                int valueEnd = valueStart;
                while (valueEnd < html.length()
                        && !Character.isWhitespace(html.charAt(valueEnd))
                        && html.charAt(valueEnd) != '>') {
                    valueEnd++;
                }
                if (valueEnd > valueStart) {
                    out.add(html.substring(valueStart, valueEnd));
                }
                start = Math.max(valueEnd, valueStart + 1);
            }
        }
        return out;
    }

    private static String normalLinkedAssetCandidate(String value) {
        if (value == null || value.isBlank()) return "";
        String stripped = value.strip();
        int query = stripped.indexOf('?');
        if (query >= 0) stripped = stripped.substring(0, query);
        int fragment = stripped.indexOf('#');
        if (fragment >= 0) stripped = stripped.substring(0, fragment);
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (lower.isBlank()
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("//")
                || lower.startsWith("data:")
                || lower.startsWith("#")
                || lower.startsWith("/")) {
            return "";
        }
        return ToolCallSupport.normalizePath(stripped);
    }

    private static String resolveLinkedAssetAgainstHtmlPath(String htmlPath, String linked) {
        String normalizedHtml = ToolCallSupport.normalizePath(htmlPath);
        String normalizedLinked = ToolCallSupport.normalizePath(linked);
        if (normalizedHtml.isBlank() || normalizedLinked.isBlank()) return "";
        int slash = normalizedHtml.lastIndexOf('/');
        if (slash < 0) return normalizedLinked;
        return ToolCallSupport.normalizePath(normalizedHtml.substring(0, slash + 1) + normalizedLinked);
    }

    private static Set<String> addBacktickStaticWebTargets(String text, Set<String> targets) {
        LinkedHashSet<String> added = new LinkedHashSet<>();
        if (text == null || text.isBlank() || targets == null) return added;
        int start = 0;
        while (start < text.length()) {
            int open = text.indexOf('`', start);
            if (open < 0) return added;
            int close = text.indexOf('`', open + 1);
            if (close < 0) return added;
            String candidate = ToolCallSupport.normalizePath(text.substring(open + 1, close).strip());
            if (StaticWebCapabilityProfile.isSmallWebFile(candidate)) {
                targets.add(candidate);
                added.add(candidate);
            }
            start = close + 1;
        }
        return added;
    }

    private static Set<String> addPlainPrefixStaticWebTargets(String text, Set<String> targets) {
        LinkedHashSet<String> added = new LinkedHashSet<>();
        if (text == null || text.isBlank() || targets == null) return added;
        String stripped = text.strip();
        while (stripped.startsWith("-") || stripped.startsWith("*")) {
            stripped = stripped.substring(1).strip();
        }
        int colon = stripped.indexOf(':');
        if (colon <= 0) return added;
        String detail = stripped.substring(colon + 1).toLowerCase(Locale.ROOT);
        if (detail.contains("expected target was not successfully mutated")) return added;
        if (!detail.contains("file appears to be placeholder content")
                && !detail.contains("syntax check failed")
                && !detail.contains("could not be read for functional web verification")) {
            return added;
        }
        String candidate = ToolCallSupport.normalizePath(stripped.substring(0, colon).strip());
        if (candidate.contains(" ")) return added;
        if (StaticWebCapabilityProfile.isSmallWebFile(candidate)) {
            targets.add(candidate);
            added.add(candidate);
        }
        return added;
    }

    private static boolean hasTargetWithExtension(Set<String> targets, String extension) {
        if (targets == null || targets.isEmpty() || extension == null || extension.isBlank()) return false;
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        for (String target : targets) {
            String normalized = ToolCallSupport.normalizePath(target).toLowerCase(Locale.ROOT);
            if (normalized.endsWith(normalizedExtension)) return true;
        }
        return false;
    }

    private static void removeConventionalFallbackWhenExactTargetExists(
            Set<String> targets,
            Set<String> exactTargets,
            String conventional,
            String extension
    ) {
        if (targets == null || targets.isEmpty() || exactTargets == null || exactTargets.isEmpty()) return;
        if (!hasTargetWithExtension(exactTargets, extension)) return;
        String conventionalKey = normalizeExpectedTargetKey(conventional);
        boolean exactIncludesConventional = exactTargets.stream()
                .map(StaticWebContinuationPlanner::normalizeExpectedTargetKey)
                .anyMatch(conventionalKey::equals);
        if (!exactIncludesConventional) {
            targets.remove(conventional);
        }
    }

    private static boolean hasSuccessfulSmallWebFileMutation(LoopState state) {
        if (state == null) return false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (mutatedSmallWebFile(outcome)) return true;
        }
        return false;
    }

    private static Set<String> successfulSmallWebMutationKeys(LoopState state) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (state == null) return out;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (!mutatedSmallWebFile(outcome)) continue;
            addSmallWebMutationKey(out, outcome.pathHint());
            WorkspaceOperationPlan plan = outcome.workspaceOperationPlan();
            if (plan == null) continue;
            for (WorkspaceOperationPlan.PathEffect effect : plan.pathEffects()) {
                if (effect != null) {
                    addSmallWebMutationKey(out, effect.path());
                }
            }
        }
        return out;
    }

    private static void addSmallWebMutationKey(Set<String> out, String path) {
        if (out == null || path == null || path.isBlank()) return;
        if (!StaticWebCapabilityProfile.isSmallWebFile(path)) return;
        out.add(normalizeExpectedTargetKey(path));
    }

    private static TaskVerificationResult staticWebVerification(LoopState state) {
        if (state == null || state.workspace == null) return TaskVerificationResult.notRun("");
        TaskContract contract = taskContract(state);
        if (contract == null || !contract.mutationAllowed() || !contract.verificationRequired()) {
            return TaskVerificationResult.notRun("");
        }
        if (state.mutatingToolSuccesses <= 0) return TaskVerificationResult.notRun("");
        ToolCallLoop.LoopResult snapshot = new ToolCallLoop.LoopResult(
                state.currentText,
                state.iterations,
                state.totalToolsInvoked,
                List.copyOf(state.toolNames),
                state.messages,
                state.failedCalls,
                state.retriedCalls,
                false,
                state.mutatingToolSuccesses,
                List.copyOf(state.pathsReadThisTurn),
                state.cushionFiresRedundantRead,
                0,
                state.cushionFiresB3EditShortCircuit,
                state.cushionFiresE1Suggestion,
                state.failureDecision,
                List.copyOf(state.toolOutcomes));
        return StaticTaskVerifier.verifyWithoutTraceEvents(
                state.workspace,
                contract,
                snapshot,
                0);
    }

    private static TaskContract taskContract(LoopState state) {
        if (state == null) return null;
        return WorkspaceTargetReconciler.reconcile(
                TaskContractResolver.fromMessages(state.messages),
                state.workspace);
    }

    private static List<ToolSpec> safeTools(List<ToolSpec> baseTools) {
        return baseTools == null ? List.of() : List.copyOf(baseTools);
    }

    private static List<ToolSpec> filterTools(List<ToolSpec> specs, List<String> allowedNames) {
        if (specs == null || specs.isEmpty() || allowedNames == null || allowedNames.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(spec -> spec != null && allowedNames.contains(spec.name()))
                .toList();
    }

    private static boolean hasMutatingTool(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) return false;
        for (ToolSpec spec : specs) {
            String name = spec == null ? "" : spec.name();
            if ("talos.write_file".equals(name) || "talos.edit_file".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeExpectedTargetKey(String path) {
        return ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }
}
