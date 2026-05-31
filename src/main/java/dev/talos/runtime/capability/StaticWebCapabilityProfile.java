package dev.talos.runtime.capability;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class StaticWebCapabilityProfile {
    public static final String ID = "static-web";

    private StaticWebCapabilityProfile() {}

    public static CapabilityProfile select(TaskContract contract, Path workspace, Set<String> mutatedPaths) {
        if (!shouldVerifyCoherence(contract, workspace, mutatedPaths)) {
            return CapabilityProfile.none();
        }
        ArtifactOperation operation = operationFor(contract);
        return CapabilityProfile.staticWeb(operation, targetSurfaceFor(contract, operation));
    }

    public static boolean shouldVerifyCoherence(TaskContract contract, Path workspace, Set<String> mutatedPaths) {
        if (contract == null) return false;
        if (hasOnlyExplicitNonWebMutationTargets(contract)) return false;
        String request = contract.originalUserRequest();
        if (looksWebGuideDocumentTask(request)) return false;
        if (hasExactHtmlCssJsExpectedTargets(contract)
                || shouldCheckSelectorCoherence(request)
                || looksBroadWebTask(contract)
                || looksFunctionalWebTask(contract)
                || looksStyledWebTask(contract, mutatedPaths)) {
            return true;
        }
        return looksGenericMutationFollowUp(request) && mutatesSmallWebSurface(workspace, mutatedPaths);
    }

    public static boolean requiresSeparateAssetMutations(CapabilityProfile profile) {
        return profile != null
                && profile.staticWeb()
                && profile.targetSurface() == TargetSurface.HTML_CSS_JS;
    }

    public static boolean looksFunctionalWebTask(TaskContract contract) {
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        if (!looksBroadWebTask(contract) && !looksWebAssetInteractionTask(lower)) return false;
        return lower.contains("functioning")
                || lower.contains("functional")
                || lower.contains("working")
                || lower.contains("interactive")
                || lower.contains("interaction")
                || lower.contains("calculator")
                || lower.contains("bmi")
                || lower.contains("make it work")
                || lower.contains("actually work")
                || lower.contains("does not work")
                || lower.contains("doesn't work")
                || mentionsForm(lower);
    }

    public static boolean looksCalculatorOrFormTask(TaskContract contract) {
        if (!looksFunctionalWebTask(contract)) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("calculator")
                || lower.contains("bmi")
                || mentionsForm(lower)
                || lower.contains("input")
                || lower.contains("submit")
                || lower.contains("calculate");
    }

    public static boolean looksStyledWebTask(TaskContract contract, Set<String> mutatedPaths) {
        if (contract == null || !contract.mutationRequested()) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        if (!mentionsVisualDesignIntent(lower)) return false;
        return mentionsWebSurface(lower) || mutatesHtmlSurface(mutatedPaths);
    }

    public static boolean isSmallWebFile(String target) {
        String lower = target == null ? "" : target.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html")
                || lower.endsWith(".htm")
                || lower.endsWith(".css")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx");
    }

    public static boolean isStructuralProblem(String problem) {
        if (problem == null || problem.isBlank()) return false;
        String lower = problem.toLowerCase(Locale.ROOT);
        return lower.contains("does not link")
                || lower.contains("missing javascript")
                || lower.contains("missing js")
                || lower.contains("missing a submit")
                || lower.contains("missing submit")
                || lower.contains("missing calculate")
                || lower.contains("missing form")
                || lower.contains("missing input")
                || lower.contains("missing result")
                || lower.contains("result output")
                || lower.contains("selector mismatch")
                || lower.contains("selector")
                || lower.contains("duplicate id")
                || lower.contains("duplicate ids")
                || lower.contains("placeholder")
                || lower.contains("missing javascript behavior")
                || lower.contains("missing js behavior");
    }

    public static List<String> inferStructuralTargets(List<ChatMessage> messages, List<String> problems) {
        Set<String> targets = new LinkedHashSet<>();
        String combinedProblems = String.join("\n", problems == null ? List.of() : problems)
                .toLowerCase(Locale.ROOT);
        if (combinedProblems.contains("html")
                || combinedProblems.contains("form")
                || combinedProblems.contains("button")
                || combinedProblems.contains("input")
                || combinedProblems.contains("duplicate id")
                || combinedProblems.contains("selector")) {
            targets.add("index.html");
        }
        if (combinedProblems.contains("css")
                || combinedProblems.contains("style.css")
                || combinedProblems.contains("styles.css")) {
            targets.add("styles.css");
        }
        if (combinedProblems.contains("javascript")
                || combinedProblems.contains("script.js")
                || combinedProblems.contains("scripts.js")
                || combinedProblems.contains("placeholder")) {
            targets.add("scripts.js");
        }

        String conversation = messages == null ? "" : messages.stream()
                .filter(message -> message != null && message.content() != null)
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right)
                .toLowerCase(Locale.ROOT);
        if ((conversation.contains("3-file") || conversation.contains("three-file")
                || conversation.contains("three file"))
                && (conversation.contains("webpage") || conversation.contains("web page")
                || conversation.contains("website") || conversation.contains("page"))) {
            targets.add("index.html");
            targets.add("styles.css");
            targets.add("scripts.js");
        }
        return targets.stream().sorted().toList();
    }

    public static String profileFact(CapabilityProfile profile) {
        if (profile == null || !profile.staticWeb()) return "";
        return "Static Web capability profile selected; expected surface: "
                + profile.targetSurface().description() + ".";
    }

    public static String repairCoherenceGuidance(List<String> fullWriteTargets) {
        List<String> targets = fullWriteTargets == null ? List.of() : fullWriteTargets.stream()
                .filter(StaticWebCapabilityProfile::isSmallWebFile)
                .sorted()
                .toList();
        if (targets.isEmpty()) return "";
        return """

                Cross-file coherence checklist:
                - HTML must link every CSS and JavaScript file being written.
                - Every JavaScript ID or selector must exist in HTML before the JavaScript uses it.
                - CSS selectors should correspond to classes or IDs in HTML where practical.
                - If you rewrite any one of %s, cross-check all HTML/CSS/JS files before emitting tool calls.
                """.formatted(String.join(", ", targets)).stripTrailing();
    }

    private static ArtifactOperation operationFor(TaskContract contract) {
        if (contract == null) return ArtifactOperation.NONE;
        String lower = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(Locale.ROOT);
        if (lower.contains("fix") || lower.contains("repair") || lower.contains("remaining")) {
            return ArtifactOperation.REPAIR;
        }
        if (contract.type() == TaskType.FILE_CREATE
                || lower.contains("build")
                || containsPositiveCreateIntent(lower)
                || lower.contains("generate")
                || lower.contains("scaffold")
                || lower.contains("set up")
                || lower.contains("setup")
                || lower.contains("make me")) {
            return ArtifactOperation.CREATE;
        }
        if (contract.mutationAllowed()) return ArtifactOperation.EDIT;
        return ArtifactOperation.READ_ONLY;
    }

    private static TargetSurface targetSurfaceFor(TaskContract contract, ArtifactOperation operation) {
        if (contract == null || contract.originalUserRequest() == null) {
            return TargetSurface.FUNCTIONAL_WEB;
        }
        String lower = contract.originalUserRequest().toLowerCase(Locale.ROOT);
        if (lower.contains("self-contained")
                || lower.contains("single html")
                || lower.contains("one html")
                || lower.contains("one-file")
                || lower.contains("single-file")
                || (lower.contains("inline") && (lower.contains("css") || lower.contains("style"))
                && (lower.contains("javascript") || lower.contains("script")))) {
            return TargetSurface.SELF_CONTAINED_HTML;
        }
        if (operation == ArtifactOperation.CREATE && requiresSeparateAssetMutations(contract)) {
            return TargetSurface.HTML_CSS_JS;
        }
        return TargetSurface.FUNCTIONAL_WEB;
    }

    private static boolean requiresSeparateAssetMutations(TaskContract contract) {
        if (hasExactHtmlCssJsExpectedTargets(contract)) return true;
        if (!looksBroadWebTask(contract)) return false;
        String lower = contract.originalUserRequest().toLowerCase(Locale.ROOT);
        boolean createLike = contract.type() == TaskType.FILE_CREATE
                || lower.contains("build")
                || containsPositiveCreateIntent(lower)
                || lower.contains("generate")
                || lower.contains("scaffold")
                || lower.contains("set up")
                || lower.contains("setup");
        boolean separateAssets = (lower.contains("separate") || lower.contains("different files"))
                && (lower.contains("css") || lower.contains("styling"))
                && (lower.contains("javascript") || lower.contains("script") || lower.contains("scripting"));
        boolean explicitThreeFileSurface = lower.contains("index.html")
                && (lower.contains("styles.css") || lower.contains("style.css") || lower.contains(".css"))
                && (lower.contains("scripts.js") || lower.contains("script.js") || lower.contains(".js"));
        return createLike && (separateAssets || explicitThreeFileSurface);
    }

    private static boolean hasExactHtmlCssJsExpectedTargets(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) return false;
        boolean html = false;
        boolean css = false;
        boolean js = false;
        for (String target : contract.expectedTargets()) {
            String lower = target == null ? "" : target.toLowerCase(Locale.ROOT);
            html = html || lower.endsWith(".html") || lower.endsWith(".htm");
            css = css || lower.endsWith(".css");
            js = js || lower.endsWith(".js") || lower.endsWith(".jsx")
                    || lower.endsWith(".ts") || lower.endsWith(".tsx");
        }
        return html && css && js;
    }

    private static boolean shouldCheckSelectorCoherence(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (lower.contains("selector") || lower.contains(".cta-button") || lower.contains("#cta-button")) {
            return true;
        }
        boolean namesWebParts = lower.contains("html")
                && (lower.contains("css") || lower.contains("stylesheet"))
                && (lower.contains("javascript") || lower.contains("script.js") || lower.contains("js"));
        boolean asksAlignment = lower.contains("match")
                || lower.contains("mismatch")
                || lower.contains("align")
                || lower.contains("linkage")
                || lower.contains("wire")
                || lower.contains("reference");
        return namesWebParts && asksAlignment;
    }

    private static boolean looksBroadWebTask(TaskContract contract) {
        if (contract == null) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        boolean mutatingTask = contract.mutationRequested();
        boolean mentionsWebSurface = mentionsWebSurface(lower);
        boolean mentionsStyle = lower.contains("css")
                || lower.contains(".css")
                || lower.contains("stylesheet")
                || lower.contains("style.css")
                || lower.contains("styles.css")
                || lower.contains("styling");
        boolean mentionsScript = lower.contains("javascript")
                || lower.contains(".js")
                || lower.contains("script.js")
                || lower.contains("scripts.js")
                || lower.contains("scripting")
                || lower.contains(" js ")
                || lower.endsWith(" js")
                || lower.contains("script file");
        boolean asksFunctional = lower.contains("functioning")
                || lower.contains("functional")
                || lower.contains("working")
                || lower.contains("interactive")
                || lower.contains("calculator")
                || lower.contains("bmi")
                || lower.contains("make it work")
                || lower.contains("actually work")
                || lower.contains("does not work")
                || lower.contains("doesn't work")
                || mentionsForm(lower);
        return mutatingTask && mentionsWebSurface
                && ((mentionsStyle && mentionsScript) || asksFunctional);
    }

    private static boolean mentionsWebSurface(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains("website")
                || lower.contains("web app")
                || lower.contains("webpage")
                || lower.contains("web page")
                || lower.contains("index.html")
                || lower.contains(".html")
                || lower.contains(" html")
                || lower.startsWith("html")
                || lower.contains(" site")
                || lower.contains(" page");
    }

    private static boolean mentionsVisualDesignIntent(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains("styling")
                || lower.contains("stylesheet")
                || lower.contains("modern")
                || lower.contains("visual")
                || lower.contains("design")
                || lower.contains("synthwave")
                || lower.contains("neon")
                || lower.contains("theme")
                || lower.contains("polished")
                || lower.contains("good looking")
                || lower.contains("cool looking")
                || containsWholeWord(lower, "style");
    }

    private static boolean looksWebAssetInteractionTask(String lower) {
        if (lower == null || lower.isBlank()) return false;
        boolean mentionsStyle = lower.contains("css")
                || lower.contains(".css")
                || lower.contains("stylesheet")
                || lower.contains("style.css")
                || lower.contains("styles.css")
                || mentionsVisualDesignIntent(lower);
        boolean mentionsScript = lower.contains("javascript")
                || lower.contains(".js")
                || lower.contains("script.js")
                || lower.contains("scripts.js")
                || lower.contains("scripting")
                || lower.contains("interaction")
                || lower.contains("interactive");
        return mentionsStyle && mentionsScript;
    }

    private static boolean looksWebGuideDocumentTask(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        boolean explicitTextOutput = lower.contains("txt file")
                || lower.contains("text file")
                || lower.contains(".txt")
                || lower.contains("markdown file")
                || lower.contains(".md");
        boolean explanatoryDocument = lower.contains("talks about")
                || lower.contains("how to build")
                || lower.contains("how to create")
                || lower.contains("guide")
                || lower.contains("instructions");
        return explicitTextOutput && explanatoryDocument && mentionsWebSurface(lower);
    }

    private static boolean containsPositiveCreateIntent(String lower) {
        if (lower == null || lower.isBlank()) return false;
        int start = 0;
        while (start < lower.length()) {
            int index = lower.indexOf("create", start);
            if (index < 0) return false;
            int before = index - 1;
            int after = index + "create".length();
            boolean leftBoundary = before < 0 || !Character.isLetterOrDigit(lower.charAt(before));
            boolean rightBoundary = after >= lower.length() || !Character.isLetterOrDigit(lower.charAt(after));
            if (leftBoundary && rightBoundary && !hasImmediateCreateNegation(lower, index)) {
                return true;
            }
            start = after;
        }
        return false;
    }

    private static boolean hasImmediateCreateNegation(String lower, int createIndex) {
        int from = Math.max(0, createIndex - 24);
        String prefix = lower.substring(from, createIndex).stripTrailing();
        return prefix.endsWith("do not")
                || prefix.endsWith("don't")
                || prefix.endsWith("dont")
                || prefix.endsWith("not")
                || prefix.endsWith("without")
                || prefix.endsWith("avoid")
                || prefix.endsWith("never")
                || prefix.endsWith("no");
    }

    private static boolean mutatesHtmlSurface(Set<String> mutatedPaths) {
        return mutatedPaths != null && mutatedPaths.stream().anyMatch(path -> hasExtension(path, ".html", ".htm"));
    }

    private static boolean hasOnlyExplicitNonWebMutationTargets(TaskContract contract) {
        return contract != null
                && contract.mutationRequested()
                && !contract.expectedTargets().isEmpty()
                && contract.expectedTargets().stream().noneMatch(StaticWebCapabilityProfile::isSmallWebFile);
    }

    private static boolean mentionsForm(String lower) {
        return containsWholeWord(lower, "form") || containsWholeWord(lower, "forms");
    }

    private static boolean containsWholeWord(String lower, String token) {
        if (lower == null || lower.isBlank() || token == null || token.isBlank()) return false;
        int start = 0;
        while (start < lower.length()) {
            int index = lower.indexOf(token, start);
            if (index < 0) return false;
            int before = index - 1;
            int after = index + token.length();
            boolean leftBoundary = before < 0 || !Character.isLetterOrDigit(lower.charAt(before));
            boolean rightBoundary = after >= lower.length() || !Character.isLetterOrDigit(lower.charAt(after));
            if (leftBoundary && rightBoundary) return true;
            start = index + token.length();
        }
        return false;
    }

    private static boolean looksGenericMutationFollowUp(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT).strip();
        return lower.equals("can you make it?")
                || lower.equals("make it")
                || lower.equals("make it please")
                || lower.equals("do it")
                || lower.equals("do it please")
                || lower.equals("make the edits please")
                || lower.equals("make the changes please")
                || lower.equals("apply it")
                || lower.equals("apply the changes")
                || lower.equals("fix it")
                || lower.equals("edit it");
    }

    private static boolean mutatesSmallWebSurface(Path root, Set<String> mutatedPaths) {
        if (root == null || mutatedPaths == null || mutatedPaths.isEmpty()) return false;
        if (mutatedPaths.stream().noneMatch(path -> hasExtension(path, ".html", ".htm", ".css", ".js"))) {
            return false;
        }
        return hasPrimaryWebSurface(root);
    }

    private static boolean hasPrimaryWebSurface(Path root) {
        if (root == null || !Files.isDirectory(root)) return false;
        boolean html = false;
        boolean css = false;
        boolean js = false;
        try (var stream = Files.list(root)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName() == null ? "" : file.getFileName().toString();
                html = html || hasExtension(name, ".html", ".htm");
                css = css || hasExtension(name, ".css");
                js = js || hasExtension(name, ".js");
            }
        } catch (Exception e) {
            return false;
        }
        return html && css && js;
    }

    private static boolean hasExtension(String path, String... exts) {
        if (path == null || exts == null) return false;
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String ext : exts) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
}
