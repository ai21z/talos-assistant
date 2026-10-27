package dev.talos.runtime.task;

import dev.talos.runtime.intent.AnswerInterpretationBoundary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.talos.runtime.task.AnswerResultRequirements.Role;
import static dev.talos.runtime.task.AnswerResultRequirements.Slot;

/** Request-only bounded interpretation, without source facts or execution authority. */
public final class ReadOnlyRequestMeaning {
    public static final String RULE_VERSION = "read-only-request-meaning-v1";
    private static final String FILE = "(?:`[A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9]{1,12}`|[A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9]{1,12})";
    private static final String WORD = "[A-Za-z][A-Za-z0-9_-]*";
    private static final String TARGET = "(?:`#[A-Za-z_][A-Za-z0-9_:-]*`|#[A-Za-z_][A-Za-z0-9_:-]*|" + WORD + "(?:[ \\t]+" + WORD + "){0,7})";
    private static final String WRAPPER = "(?:please\\s+|(?:can|could|would)\\s+you\\s+(?:please\\s+)?)?";
    private static final Pattern READ = pattern(WRAPPER + "(?:read|inspect)\\s+(" + FILE
            + "(?:\\s*(?:,\\s*(?:and\\s+)?|and\\s+)" + FILE + "){0,7})");
    private static final Pattern FILE_TOKEN = Pattern.compile(FILE);
    private static final Pattern DIAGNOSIS = pattern(WRAPPER + "(?:explain|describe)\\s+(?:exactly\\s+)?why\\s+(?:the\\s+)?("
            + TARGET + ")\\s+button\\s+(fails|does\\s+not\\s+work|is\\s+broken)");
    private static final Pattern SELECTOR = pattern(WRAPPER + "(?:give|identify|state)\\s+(?:the\\s+)?"
            + "(?:(?:correct|exact)\\s+selector|selector\\s+that\\s+should\\s+be\\s+used)"
            + "(?:\\s+for\\s+(?:the\\s+)?(" + TARGET + ")\\s+button)?");
    private static final String LINKAGE = "(?:their\\s+)?(?:HTML|page)\\s+linkage";
    private static final List<Pattern> SCRIPT_LINKAGE = List.of(
            pattern(WRAPPER + "(?:explain|describe)\\s+how\\s+(" + FILE + ")\\s+differs\\s+from\\s+(" + FILE
                    + ")\\s+in\\s+" + LINKAGE),
            pattern(WRAPPER + "(?:compare|distinguish)\\s+(" + FILE + ")\\s+and\\s+(" + FILE
                    + ")\\s+(?:by|in\\s+terms\\s+of)\\s+" + LINKAGE),
            pattern(WRAPPER + "(?:identify|state)\\s+which\\s+of\\s+(" + FILE + ")\\s+and\\s+(" + FILE
                    + ")\\s+is\\s+(?:linked|loaded)\\s+(?:by|in)\\s+(?:the\\s+)?(?:HTML|page)"));
    private static final String DIRECTIVE = WRAPPER + "(?:read|inspect|explain|describe|give|identify|state|compare|distinguish)\\b";
    private static final Pattern START = pattern("^" + DIRECTIVE);
    private static final Pattern JOIN = pattern("(?:,\\s*(?:and\\s+)?|\\s+and\\s+)(?=" + DIRECTIVE + ")");
    private static final Pattern MODAL = pattern("^(?:can|could|would)\\s+you\\b");
    private static final Pattern AMBIGUOUS_NAME = pattern(".*\\b(?:and|or|if|unless|when|that|which|whose|because|but|then)\\b.*");

    public enum Disposition { READ, RESULT, CONSTRAINT, UNRESOLVED }
    public record Region(String originalSlotId, int start, int end, Disposition disposition,
                         String ruleId, List<String> successorSlotIds, List<String> reasons) {
        public Region {
            successorSlotIds = List.copyOf(successorSlotIds);
            reasons = List.copyOf(reasons);
        }
    }
    public record ReadReference(String path, int start, int end) { }

    private final String request;
    private final AnswerResultRequirements original;
    private final AnswerResultRequirements requirements;
    private final List<Region> regions;
    private final List<ReadReference> readReferences;
    private final String target;
    private final boolean targetIsId;
    private final boolean targetAmbiguous;
    private final List<String> scriptTargets;
    private final boolean scriptsAmbiguous;
    private final String identity;

    private ReadOnlyRequestMeaning(Compiler compiler) {
        request = compiler.request;
        original = compiler.original;
        var slots = new ArrayList<Slot>();
        var reasons = new LinkedHashMap<String, List<String>>();
        var allRegions = new ArrayList<Region>();
        var reads = new ArrayList<ReadReference>();
        for (Node node : compiler.nodes) {
            if (node.slot != null) {
                slots.add(node.slot);
            }
            if (!node.reasons.isEmpty()) reasons.put(node.slot == null ? "context-" + node.start + "-" + node.end
                    : node.slot.id(), node.reasons);
            reads.addAll(node.reads);
            allRegions.add(new Region(node.parentId, node.start, node.end, node.disposition,
                    node.rule, node.slot == null ? List.of() : List.of(node.slot.id()), node.reasons));
        }
        slots.sort(Comparator.comparingInt(Slot::start));
        allRegions.sort(Comparator.comparingInt(Region::start));
        reads.sort(Comparator.comparingInt(ReadReference::start));
        requirements = AnswerResultRequirements.project(original, slots, reasons);
        regions = List.copyOf(allRegions);
        readReferences = List.copyOf(reads);
        targetAmbiguous = compiler.targetAmbiguous;
        target = compiler.target == null ? "" : compiler.target.text;
        targetIsId = compiler.target != null && compiler.target.id;
        scriptsAmbiguous = compiler.scriptsAmbiguous;
        scriptTargets = List.copyOf(compiler.scriptTargets);
        identity = hash(RULE_VERSION + "\n" + requestSha256() + "\n" + slots + "\n" + reasons
                + "\n" + regions + "\n" + readReferences + "\n" + target + "\n" + targetIsId
                + "\n" + targetAmbiguous + "\n" + scriptTargets + "\n" + scriptsAmbiguous);
    }

    public static ReadOnlyRequestMeaning compile(String request) {
        return new Compiler(request == null ? "" : request).compile();
    }

    public String request() { return request; }
    public String requestSha256() { return original.requestSha256(); }
    public String identity() { return identity; }
    public AnswerResultRequirements original() { return original; }
    public AnswerResultRequirements requirements() { return requirements; }
    public List<Region> regions() { return regions; }
    public List<ReadReference> readReferences() { return readReferences; }
    public String target() { return target; }
    public boolean targetIsId() { return targetIsId; }
    public boolean targetAmbiguous() { return targetAmbiguous; }
    public List<String> scriptTargets() { return scriptTargets; }
    public boolean scriptsAmbiguous() { return scriptsAmbiguous; }

    private static Pattern pattern(String expression) { return Pattern.compile(expression, Pattern.CASE_INSENSITIVE); }
    private static String unquote(String text) {
        return text.startsWith("`") && text.endsWith("`") ? text.substring(1, text.length() - 1) : text;
    }
    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Target(String text, boolean id) { }
    private record Span(int start, int end) { }

    private static final class Node {
        private final String parentId;
        private final int start;
        private final int end;
        private final Disposition disposition;
        private final String rule;
        private final Slot slot;
        private final List<String> reasons;
        private final List<ReadReference> reads;
        private final Target target;
        private final List<String> scripts;

        private Node(String parentId, int start, int end, Disposition disposition, String rule,
                     Slot slot, List<String> reasons, List<ReadReference> reads, Target target, List<String> scripts) {
            this.parentId = parentId;
            this.start = start;
            this.end = end;
            this.disposition = disposition;
            this.rule = rule;
            this.slot = slot;
            this.reasons = List.copyOf(reasons);
            this.reads = List.copyOf(reads);
            this.target = target;
            this.scripts = List.copyOf(scripts);
        }
    }

    private static final class Compiler {
        private final String request;
        private final AnswerResultRequirements original;
        private final List<Node> nodes = new ArrayList<>();
        private Target target;
        private boolean targetAmbiguous;
        private List<String> scriptTargets = List.of();
        private boolean scriptsAmbiguous;

        private Compiler(String request) {
            this.request = request;
            original = AnswerResultRequirements.fromRequest(request);
        }

        private ReadOnlyRequestMeaning compile() {
            for (var context : original.contextRegions()) {
                if (context.kind() == AnswerResultRequirements.ContextKind.READ) {
                    Node read = recognize("", new Span(context.start(), context.end()), null, false);
                    if (read != null) nodes.add(read);
                    else nodes.add(new Node("", context.start(), context.end(), Disposition.UNRESOLVED, "", null,
                            List.of("UNSUPPORTED_READ_REGION"), List.of(), null, List.of()));
                } else {
                    nodes.add(new Node("", context.start(), context.end(), Disposition.CONSTRAINT,
                            "existing-" + context.kind().name().toLowerCase(Locale.ROOT), null, List.of(), List.of(), null, List.of()));
                }
            }
            for (Slot slot : original.slots()) {
                if (slot.calculation() || slot.role() == Role.COMPARISON) {
                    nodes.add(new Node(slot.id(), slot.start(), slot.end(), Disposition.RESULT, "existing-result",
                            slot, List.of(), List.of(), null, List.of()));
                } else if (slot.role() == Role.UNKNOWN) {
                    List<String> reasons = original.regionReasons().getOrDefault(slot.id(), List.of("UNRESOLVED_REQUEST_REGION"));
                    if (reasons.equals(List.of("UNSUPPORTED_SCRIPT_DISTINCTION"))) {
                        Node linkage = recognize(slot.id(), new Span(slot.start(), slot.end()), null, true);
                        if (linkage != null && linkage.slot != null && linkage.slot.role() == Role.SCRIPT_DISTINCTION) nodes.add(linkage);
                        else nodes.add(unresolved(slot.id(), slot.start(), slot.end(), slot, reasons));
                    } else if (reasons.equals(List.of("UNSUPPORTED_RESULT_REQUEST"))) {
                        Node result = recognize(slot.id(), new Span(slot.start(), slot.end()), null, true);
                        if (result != null) nodes.add(result);
                        else nodes.add(unresolved(slot.id(), slot.start(), slot.end(), slot, reasons));
                    } else if (reasons.equals(List.of("UNSUPPORTED_RESULT_INPUTS"))) {
                        Node selector = recognize(slot.id(), new Span(slot.start(), slot.end()), null, true);
                        if (selector != null && selector.slot != null && selector.slot.role() == Role.SELECTOR && selector.target != null)
                            nodes.add(selector);
                        else nodes.add(unresolved(slot.id(), slot.start(), slot.end(), slot, reasons));
                    } else if (!reasons.equals(List.of("UNRESOLVED_REQUEST_REGION"))) {
                        nodes.add(unresolved(slot.id(), slot.start(), slot.end(), slot, reasons));
                    } else {
                        promote(slot);
                    }
                } else {
                    Node node = recognize(slot.id(), new Span(slot.start(), slot.end()), slot, true);
                    if (node == null) {
                        String reason = slot.role() == Role.SCRIPT_DISTINCTION ? "UNSUPPORTED_SCRIPT_SCOPE" : "UNSUPPORTED_RESULT_SCOPE";
                        nodes.add(unresolved(slot.id(), slot.start(), slot.end(), slot, List.of(reason)));
                    } else nodes.add(node);
                }
            }
            if (nodes.stream().filter(node -> node.slot != null).count() > AnswerResultRequirements.MAX_SLOTS) {
                for (Slot slot : original.slots()) {
                    if (slot.role() != Role.UNKNOWN) continue;
                    nodes.removeIf(node -> node.parentId.equals(slot.id()));
                    var reasons = new LinkedHashSet<>(original.regionReasons().getOrDefault(slot.id(), List.of()));
                    reasons.add("MEANING_SLOT_BOUND_EXCEEDED");
                    nodes.add(unresolved(slot.id(), slot.start(), slot.end(), slot, List.copyOf(reasons)));
                }
            }
            bindTargets();
            return new ReadOnlyRequestMeaning(this);
        }

        private void promote(Slot originalSlot) {
            String text = request.substring(originalSlot.start(), originalSlot.end());
            if (!START.matcher(text).find()) {
                nodes.add(unresolved(originalSlot.id(), originalSlot.start(), originalSlot.end(), originalSlot,
                        List.of("UNRESOLVED_REQUEST_REGION")));
                return;
            }
            List<Span> parts = parts(originalSlot);
            for (Span part : parts) {
                Node accepted = recognize(originalSlot.id(), part, null, true);
                if (accepted != null) nodes.add(accepted);
                else nodes.add(unresolved(originalSlot.id(), part.start(), part.end(),
                        parts.size() == 1 ? originalSlot : null, List.of("UNRESOLVED_REQUEST_REGION")));
            }
        }

        private Node recognize(String parent, Span originalSpan, Slot existing, boolean checkScope) {
            Span span = originalSpan;
            String text = request.substring(span.start(), span.end());
            if (text.endsWith("?") && MODAL.matcher(text).find()) {
                span = trim(new Span(span.start(), span.end() - 1));
                text = request.substring(span.start(), span.end());
            }
            Matcher match = READ.matcher(text);
            if (match.matches()) {
                var reads = new ArrayList<ReadReference>();
                var atoms = new ArrayList<Span>();
                Matcher files = FILE_TOKEN.matcher(text).region(match.start(1), match.end(1));
                while (files.find()) {
                    reads.add(new ReadReference(unquote(files.group()), span.start() + files.start(), span.start() + files.end()));
                    atoms.add(new Span(span.start() + files.start(), span.start() + files.end()));
                }
                if (checkScope && !eligible(originalSpan, atoms)) return null;
                return new Node(parent, originalSpan.start(), originalSpan.end(), Disposition.READ, "read-file-list",
                        null, List.of(), reads, null, List.of());
            }
            match = DIAGNOSIS.matcher(text);
            if (match.matches()) {
                Target button = button(match.group(1));
                if (button == null) return null;
                var atoms = List.of(new Span(span.start() + match.start(1), span.start() + match.end(1)),
                        new Span(span.start() + match.start(2), span.start() + match.end(2)));
                if (checkScope && !eligible(originalSpan, atoms)) return null;
                return result(parent, originalSpan, existing, Role.DIAGNOSIS, "button-failure", button, List.of());
            }
            match = SELECTOR.matcher(text);
            if (match.matches()) {
                Target button = match.group(1) == null ? null : button(match.group(1));
                if (match.group(1) != null && button == null) return null;
                List<Span> atoms = match.group(1) == null ? List.of()
                        : List.of(new Span(span.start() + match.start(1), span.start() + match.end(1)));
                if (checkScope && !eligible(originalSpan, atoms)) return null;
                return result(parent, originalSpan, existing, Role.SELECTOR, "button-selector", button, List.of());
            }
            for (Pattern linkage : SCRIPT_LINKAGE) {
                match = linkage.matcher(text);
                if (!match.matches()) continue;
                List<String> scripts = List.of(unquote(match.group(1)), unquote(match.group(2)));
                if (scripts.getFirst().equals(scripts.getLast()) || scripts.stream().anyMatch(path -> !path.toLowerCase(Locale.ROOT).endsWith(".js"))) return null;
                var atoms = List.of(new Span(span.start() + match.start(1), span.start() + match.end(1)),
                        new Span(span.start() + match.start(2), span.start() + match.end(2)));
                if (checkScope && !eligible(originalSpan, atoms)) return null;
                return result(parent, originalSpan, existing, Role.SCRIPT_DISTINCTION, "explicit-script-linkage", null, scripts);
            }
            return null;
        }

        private boolean eligible(Span span, List<Span> literals) {
            var masked = new StringBuilder(request);
            Matcher files = FILE_TOKEN.matcher(request);
            while (files.find()) {
                for (int index = files.start(); index < files.end(); index++) masked.setCharAt(index, ' ');
            }
            for (Slot candidate : original.slots()) {
                for (Span part : parts(candidate)) {
                    Span clause = part;
                    String text = request.substring(clause.start(), clause.end());
                    if (text.endsWith("?") && MODAL.matcher(text).find()) {
                        clause = trim(new Span(clause.start(), clause.end() - 1));
                        text = request.substring(clause.start(), clause.end());
                    }
                    Matcher diagnosis = DIAGNOSIS.matcher(text);
                    if (diagnosis.matches() && button(diagnosis.group(1)) != null) {
                        for (int index = clause.start() + diagnosis.start(2); index < clause.start() + diagnosis.end(2); index++)
                            masked.setCharAt(index, ' ');
                    }
                }
            }
            for (Span literal : literals) {
                for (int index = literal.start(); index < literal.end(); index++) masked.setCharAt(index, ' ');
            }
            return AnswerInterpretationBoundary.eligible(masked.toString(), span.start(), span.end());
        }

        private List<Span> parts(Slot slot) {
            if (!START.matcher(request.substring(slot.start(), slot.end())).find()) return List.of(new Span(slot.start(), slot.end()));
            int start = slot.start();
            Matcher joins = JOIN.matcher(request).region(start, slot.end());
            var parts = new ArrayList<Span>();
            while (joins.find()) {
                parts.add(trim(new Span(start, joins.start())));
                start = joins.end();
            }
            parts.add(trim(new Span(start, slot.end())));
            return List.copyOf(parts);
        }

        private Target button(String text) {
            String value = unquote(text);
            if (AMBIGUOUS_NAME.matcher(value).matches()) return null;
            return value.startsWith("#") ? new Target(value.substring(1), true) : new Target(value, false);
        }

        private Node result(String parent, Span span, Slot existing, Role role, String rule, Target button, List<String> scripts) {
            Slot slot = existing == null ? slot(role, span.start(), span.end()) : existing;
            return new Node(parent, span.start(), span.end(), Disposition.RESULT, rule, slot, List.of(), List.of(), button, scripts);
        }

        private Node unresolved(String parent, int start, int end, Slot previous, List<String> reasons) {
            Slot slot = previous == null ? slot(Role.UNKNOWN, start, end)
                    : new Slot(previous.id(), Role.UNKNOWN, start, end, -1, -1, -1, -1, "", "", List.of());
            return new Node(parent, start, end, Disposition.UNRESOLVED, "", slot, reasons, List.of(), null, List.of());
        }

        private Slot slot(Role role, int start, int end) {
            return new Slot("meaning-" + role.name().toLowerCase(Locale.ROOT) + "-" + start + "-" + end,
                    role, start, end, -1, -1, -1, -1, "", "", List.of());
        }

        private void bindTargets() {
            Map<String, Target> targets = new LinkedHashMap<>();
            Set<Set<String>> pairs = new LinkedHashSet<>();
            for (Node node : nodes) {
                if (node.target != null) targets.putIfAbsent(node.target.id ? "id:" + node.target.text
                        : "label:" + node.target.text.toLowerCase(Locale.ROOT), node.target);
                if (!node.scripts.isEmpty()) pairs.add(Set.copyOf(node.scripts));
            }
            targetAmbiguous = targets.size() > 1;
            target = targets.size() == 1 ? targets.values().iterator().next() : null;
            scriptsAmbiguous = pairs.size() > 1;
            if (pairs.size() == 1) scriptTargets = nodes.stream().filter(node -> !node.scripts.isEmpty()).findFirst().orElseThrow().scripts;
            for (int index = 0; index < nodes.size(); index++) {
                Node node = nodes.get(index);
                if (node.slot == null || node.disposition != Disposition.RESULT) continue;
                String reason = null;
                if (targetAmbiguous && (node.slot.role() == Role.DIAGNOSIS || node.slot.role() == Role.SELECTOR)) {
                    reason = "AMBIGUOUS_BUTTON_TARGET";
                } else if (node.slot.role() == Role.SELECTOR && target == null) {
                    reason = "UNBOUND_SELECTOR_TARGET";
                } else if (node.slot.role() == Role.SCRIPT_DISTINCTION && scriptsAmbiguous) {
                    reason = "AMBIGUOUS_SCRIPT_TARGETS";
                }
                if (reason != null) nodes.set(index, unresolved(node.parentId, node.start, node.end, node.slot, List.of(reason)));
            }
        }

        private Span trim(Span span) {
            int start = span.start();
            int end = span.end();
            while (start < end && Character.isWhitespace(request.charAt(start))) start++;
            while (end > start && Character.isWhitespace(request.charAt(end - 1))) end--;
            return new Span(start, end);
        }
    }
}
