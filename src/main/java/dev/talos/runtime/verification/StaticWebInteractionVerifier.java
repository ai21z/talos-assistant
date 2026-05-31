package dev.talos.runtime.verification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StaticWebInteractionVerifier {
    private static final Pattern REQUEST_ID_SELECTOR = Pattern.compile("#([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern VISIBLE_TEXT_ASSIGNMENT = Pattern.compile(
            "\\.\\s*(?:textContent|innerText)\\s*=", Pattern.CASE_INSENSITIVE);

    private StaticWebInteractionVerifier() {}

    static VerificationReport verify(String request, StaticWebSelectorAnalyzer.Facts facts) {
        Optional<TargetBinding> maybeBinding = detectBinding(request);
        if (maybeBinding.isEmpty()) return VerificationReport.empty();
        TargetBinding binding = maybeBinding.get();
        VerificationClaim claim = new VerificationClaim(
                "static-web-interaction:" + binding.triggerSelector() + "->" + binding.outputSelector(),
                "Static interaction " + binding.triggerSelector()
                        + " -> " + binding.outputSelector() + ".",
                ProofKind.STATIC_INTERACTION_GUARD,
                binding,
                true);
        VerificationObligation obligation = new VerificationObligation(
                claim,
                Set.of(ProofKind.STATIC_INTERACTION_GUARD),
                EvidenceAuthority.AUTHORITATIVE,
                binding);
        if (facts == null) {
            return VerificationReport.ofClaim(new ClaimResult(
                    claim,
                    obligation,
                    VerificationVerdict.UNAVAILABLE,
                    ProofKind.STATIC_INTERACTION_GUARD,
                    EvidenceAuthority.AUTHORITATIVE,
                    EvidenceCoverage.SCOPED,
                    List.of(),
                    List.of(),
                    List.of("Static interaction verification could not inspect the web surface.")));
        }

        String triggerId = id(binding.triggerSelector());
        String outputId = id(binding.outputSelector());
        List<String> problems = new ArrayList<>();
        if (!referencesId(facts, triggerId)) {
            problems.add(facts.jsFile() + ": requested trigger `" + binding.triggerSelector()
                    + "` is not present in the static web surface.");
        }
        if (!referencesId(facts, outputId)) {
            problems.add(facts.jsFile() + ": requested output `" + binding.outputSelector()
                    + "` is not present in the static web surface.");
        }
        if (!problems.isEmpty()) {
            return VerificationReport.ofClaim(new ClaimResult(
                    claim,
                    obligation,
                    VerificationVerdict.FAILED,
                    ProofKind.STATIC_INTERACTION_GUARD,
                    EvidenceAuthority.AUTHORITATIVE,
                    EvidenceCoverage.EXACT,
                    List.of(),
                    problems,
                    List.of()));
        }

        Optional<String> handlerWindow = clickHandlerWindow(facts.js(), triggerId);
        if (handlerWindow.isEmpty()) {
            if (assignsRequestedOutputInAnyClickHandler(facts.js(), outputId)) {
                return VerificationReport.ofClaim(new ClaimResult(
                        claim,
                        obligation,
                        VerificationVerdict.FAILED,
                        ProofKind.STATIC_INTERACTION_GUARD,
                        EvidenceAuthority.AUTHORITATIVE,
                        EvidenceCoverage.SCOPED,
                        List.of(),
                        List.of(facts.jsFile() + ": static interaction guard found a click handler that updates `"
                                + binding.outputSelector() + "`, but it is not bound to requested trigger `"
                                + binding.triggerSelector() + "`."),
                        List.of()));
            }
            return VerificationReport.ofClaim(new ClaimResult(
                    claim,
                    obligation,
                    VerificationVerdict.UNVERIFIED,
                    ProofKind.STATIC_INTERACTION_GUARD,
                    EvidenceAuthority.AUTHORITATIVE,
                    EvidenceCoverage.SCOPED,
                    List.of(),
                    List.of(),
                    List.of(facts.jsFile() + ": static interaction guard could not bind a `click` handler to `"
                            + binding.triggerSelector() + "`.")));
        }

        String handler = handlerWindow.get();
        if (assignsVisibleTextToId(facts.js(), handler, outputId)) {
            return VerificationReport.ofClaim(new ClaimResult(
                    claim,
                    obligation,
                    VerificationVerdict.VERIFIED,
                    ProofKind.STATIC_INTERACTION_GUARD,
                    EvidenceAuthority.AUTHORITATIVE,
                    EvidenceCoverage.SCOPED,
                    List.of("Static interaction guard verified `" + binding.triggerSelector()
                            + "` updates `" + binding.outputSelector() + "` in " + facts.jsFile() + "."),
                    List.of(),
                    List.of("Static interaction guard is static evidence; browser/runtime behavior was not executed.")));
        }

        if (VISIBLE_TEXT_ASSIGNMENT.matcher(handler).find()) {
            return VerificationReport.ofClaim(new ClaimResult(
                    claim,
                    obligation,
                    VerificationVerdict.FAILED,
                    ProofKind.STATIC_INTERACTION_GUARD,
                    EvidenceAuthority.AUTHORITATIVE,
                    EvidenceCoverage.SCOPED,
                    List.of(),
                    List.of(facts.jsFile() + ": click handler for `" + binding.triggerSelector()
                            + "` assigns visible text, but not to requested output `"
                            + binding.outputSelector() + "`."),
                    List.of()));
        }

        return VerificationReport.ofClaim(new ClaimResult(
                claim,
                obligation,
                VerificationVerdict.UNVERIFIED,
                ProofKind.STATIC_INTERACTION_GUARD,
                EvidenceAuthority.AUTHORITATIVE,
                EvidenceCoverage.SCOPED,
                List.of(),
                List.of(),
                List.of(facts.jsFile() + ": click handler for `" + binding.triggerSelector()
                        + "` does not assign visible text to requested output `"
                        + binding.outputSelector() + "` with `textContent` or `innerText`.")));
    }

    static Optional<TargetBinding> detectBinding(String request) {
        if (request == null || request.isBlank()) return Optional.empty();
        String lower = request.toLowerCase();
        if (!containsInteractionVerb(lower)) return Optional.empty();
        List<String> ids = new ArrayList<>();
        Matcher matcher = REQUEST_ID_SELECTOR.matcher(request);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (id != null && !id.isBlank()) ids.add(id);
        }
        if (ids.size() < 2) return Optional.empty();
        String trigger = ids.stream()
                .filter(id -> id.toLowerCase().contains("button")
                        || id.toLowerCase().contains("trigger"))
                .findFirst()
                .orElse(ids.get(0));
        String output = ids.stream()
                .filter(id -> !id.equals(trigger))
                .filter(id -> id.toLowerCase().contains("status")
                        || id.toLowerCase().contains("result")
                        || id.toLowerCase().contains("output")
                        || id.toLowerCase().contains("message"))
                .findFirst()
                .orElseGet(() -> ids.stream().filter(id -> !id.equals(trigger)).findFirst().orElse(""));
        if (output.isBlank()) return Optional.empty();
        boolean clickLike = lower.contains("click")
                || lower.contains("clicked")
                || lower.contains("button")
                || trigger.toLowerCase().contains("button");
        if (!clickLike) return Optional.empty();
        return Optional.of(new TargetBinding("#" + trigger, "#" + output, "click"));
    }

    private static boolean containsInteractionVerb(String lower) {
        return lower.contains("update")
                || lower.contains("change")
                || lower.contains("set ")
                || lower.contains("sets ")
                || lower.contains("display")
                || lower.contains("show")
                || lower.contains("write");
    }

    private static boolean referencesId(StaticWebSelectorAnalyzer.Facts facts, String id) {
        return facts.htmlIds().contains(id) || facts.jsIds().contains(id) || facts.cssIds().contains(id);
    }

    private static Optional<String> clickHandlerWindow(String js, String triggerId) {
        for (Pattern pattern : triggerHandlerPatterns(js, triggerId)) {
            Matcher matcher = pattern.matcher(js);
            if (matcher.find()) {
                int start = matcher.end();
                int end = handlerWindowEnd(js, start);
                return Optional.of(js.substring(start, end));
            }
        }
        return Optional.empty();
    }

    private static List<Pattern> triggerHandlerPatterns(String js, String triggerId) {
        List<String> aliases = aliasesForId(js, triggerId);
        List<Pattern> patterns = new ArrayList<>();
        String id = Pattern.quote(triggerId);
        patterns.add(Pattern.compile(
                "(?:getElementById\\s*\\(\\s*['\"]" + id + "['\"]\\s*\\)"
                        + "|querySelector\\s*\\(\\s*['\"]#" + id + "['\"]\\s*\\))"
                        + "\\s*\\.\\s*addEventListener\\s*\\(\\s*['\"]click['\"]",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
        for (String alias : aliases) {
            patterns.add(Pattern.compile("\\b" + Pattern.quote(alias)
                            + "\\b\\s*\\.\\s*addEventListener\\s*\\(\\s*['\"]click['\"]",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
        }
        return patterns;
    }

    private static int handlerWindowEnd(String js, int start) {
        int first = indexOrMax(js.indexOf("});", start));
        int second = indexOrMax(js.indexOf("})", start));
        int end = Math.min(first, second);
        if (end == Integer.MAX_VALUE) {
            end = Math.min(js.length(), start + 1600);
        }
        return Math.max(start, end);
    }

    private static int indexOrMax(int index) {
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static boolean assignsVisibleTextToId(String fullJs, String handler, String outputId) {
        if (directVisibleAssignment(outputId).matcher(handler).find()) return true;
        for (String alias : aliasesForId(fullJs, outputId)) {
            Pattern aliasAssignment = Pattern.compile("\\b" + Pattern.quote(alias)
                            + "\\b\\s*\\.\\s*(?:textContent|innerText)\\s*=",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            if (aliasAssignment.matcher(handler).find()) return true;
        }
        return false;
    }

    private static boolean assignsRequestedOutputInAnyClickHandler(String js, String outputId) {
        if (js == null || js.isBlank()) return false;
        Pattern pattern = Pattern.compile(
                "\\.\\s*addEventListener\\s*\\(\\s*['\"]click['\"]",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(js);
        while (matcher.find()) {
            int start = matcher.end();
            int end = handlerWindowEnd(js, start);
            if (assignsVisibleTextToId(js, js.substring(start, end), outputId)) {
                return true;
            }
        }
        return false;
    }

    private static Pattern directVisibleAssignment(String id) {
        String quoted = Pattern.quote(id);
        return Pattern.compile(
                "(?:getElementById\\s*\\(\\s*['\"]" + quoted + "['\"]\\s*\\)"
                        + "|querySelector\\s*\\(\\s*['\"]#" + quoted + "['\"]\\s*\\))"
                        + "\\s*\\.\\s*(?:textContent|innerText)\\s*=",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static List<String> aliasesForId(String js, String id) {
        if (js == null || js.isBlank() || id == null || id.isBlank()) return List.of();
        String quoted = Pattern.quote(id);
        Pattern pattern = Pattern.compile(
                "(?:const|let|var)?\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:document\\s*\\.\\s*)?"
                        + "(?:getElementById\\s*\\(\\s*['\"]" + quoted + "['\"]\\s*\\)"
                        + "|querySelector\\s*\\(\\s*['\"]#" + quoted + "['\"]\\s*\\))",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(js);
        Set<String> out = new LinkedHashSet<>();
        while (matcher.find()) {
            String alias = matcher.group(1);
            if (alias != null && !alias.isBlank() && !"document".equals(alias)) {
                out.add(alias);
            }
        }
        return List.copyOf(out);
    }

    private static String id(String selector) {
        if (selector == null) return "";
        String out = selector.strip();
        return out.startsWith("#") ? out.substring(1) : out;
    }
}
