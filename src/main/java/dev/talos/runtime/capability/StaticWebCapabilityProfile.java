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
        return CapabilityProfile.staticWeb(operationFor(contract), targetSurfaceFor(contract));
    }

    public static boolean shouldVerifyCoherence(TaskContract contract, Path workspace, Set<String> mutatedPaths) {
        if (contract == null) return false;
        String request = contract.originalUserRequest();
        if (shouldCheckSelectorCoherence(request) || looksBroadWebTask(contract)) return true;
        return looksGenericMutationFollowUp(request) && mutatesSmallWebSurface(workspace, mutatedPaths);
    }

    public static boolean requiresSeparateAssetMutations(CapabilityProfile profile) {
        return profile != null
                && profile.staticWeb()
                && profile.targetSurface() == TargetSurface.HTML_CSS_JS;
    }

    public static boolean looksFunctionalWebTask(TaskContract contract) {
        if (!looksBroadWebTask(contract)) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("functioning")
                || lower.contains("functional")
                || lower.contains("working")
                || lower.contains("interactive")
                || lower.contains("calculator")
                || lower.contains("bmi")
                || lower.contains("make it work")
                || lower.contains("actually work")
                || lower.contains("does not work")
                || lower.contains("doesn't work")
                || lower.contains("form");
    }

    public static boolean looksCalculatorOrFormTask(TaskContract contract) {
        if (!looksFunctionalWebTask(contract)) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("calculator")
                || lower.contains("bmi")
                || lower.contains("form")
                || lower.contains("input")
                || lower.contains("interactive")
                || lower.contains("functioning")
                || lower.contains("functional");
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
                || lower.contains("create")
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

    private static TargetSurface targetSurfaceFor(TaskContract contract) {
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
        if (requiresSeparateAssetMutations(contract)) {
            return TargetSurface.HTML_CSS_JS;
        }
        return TargetSurface.FUNCTIONAL_WEB;
    }

    private static boolean requiresSeparateAssetMutations(TaskContract contract) {
        if (!looksBroadWebTask(contract)) return false;
        String lower = contract.originalUserRequest().toLowerCase(Locale.ROOT);
        boolean createLike = contract.type() == TaskType.FILE_CREATE
                || lower.contains("build")
                || lower.contains("create")
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
        boolean mentionsWebSurface = lower.contains("website")
                || lower.contains("web app")
                || lower.contains("webpage")
                || lower.contains("web page")
                || lower.contains("index.html")
                || lower.contains(".html")
                || lower.contains(" html")
                || lower.startsWith("html")
                || lower.contains(" site")
                || lower.contains(" page");
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
                || lower.contains("form");
        return mutatingTask && mentionsWebSurface
                && ((mentionsStyle && mentionsScript) || asksFunctional);
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
