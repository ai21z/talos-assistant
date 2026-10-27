package dev.talos.runtime.intent;

import dev.talos.runtime.task.AnswerRequirements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded command-intent admission. No tool or process authority is granted here. */
public record CommandExecutionRequest(List<String> commandClauses) {
    private static final String COMMAND_ACTION = "run|execute|call|try|probe|verify|check|test|use|python3?|py";
    private static final String OTHER_ACTION = "inspect|read|open|review|examine|diagnose|explain|list|show|"
            + "change|edit|modify|write|create|report|state|describe";
    private static final String PREFIX = "[\\s,]*(?:(?:[-*+]|\\d+[.)])\\s+)?(?:(?:please|also|now)\\s+)*"
            + "(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?|i\\s+(?:want|need)\\s+you\\s+to\\s+)?";
    private static final String NEGATION = "do\\s+not|don['\\u2019]?t|dont|never|avoid";
    private static final Pattern LEAD = Pattern.compile(
            "(?iu)" + PREFIX + "(?:(" + NEGATION + ")\\s+)?(" + COMMAND_ACTION + "|" + OTHER_ACTION + ")\\b");
    private static final Pattern PROHIBITED_LEAD = Pattern.compile("(?iu)" + PREFIX + "(?:" + NEGATION + ")\\b");
    private static final Pattern COMMAND = Pattern.compile("(?iu)^(?:" + COMMAND_ACTION + ")$");
    private static final Pattern CONNECTOR = Pattern.compile(
            "(?iu)(?:\\b(but|and|or|then)\\s+|[,;]\\s*|[\\r\\n]+)(?=" + PREFIX
                    + "(?:(?:" + NEGATION + ")\\s+)?(?:" + COMMAND_ACTION + "|" + OTHER_ACTION + ")\\b)");
    private static final Pattern CONTEXT_BOUNDARY = Pattern.compile(
            "(?iu)\\b(?:without|unless|if|when|whenever|until|assuming|suppose|supposing|"
                    + "hypothetically|never|not|do\\s+not|don['\\u2019]?t)\\b");
    private static final Pattern DESCRIPTIVE_TAIL = Pattern.compile(
            "(?iu)^\\s+(?:how|why|whether|what|that)\\b|"
                    + "\\b(?:was|were)\\s+(?:requested|run|executed|my|your|the)\\b");

    public CommandExecutionRequest {
        commandClauses = List.copyOf(commandClauses);
    }

    public static CommandExecutionRequest parse(String request) {
        if (request == null || request.isBlank()
                || request.length() > AnswerRequirements.MAX_REQUEST_CHARS || !validUnicode(request)) {
            return new CommandExecutionRequest(List.of());
        }
        RequestLexicalView view = RequestLexicalView.of(request);
        if (view.containsProviderRemovedControl(0, request.length())
                || view.containsLoneCarriageReturn(0, request.length())
                || view.containsMalformedDelimiter(0, request.length())) {
            return new CommandExecutionRequest(List.of());
        }
        List<String> commands = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < request.length(); i++) {
            if (view.isQuoted(i) || !view.isTopLevel(i)) continue;
            if (!view.isSentenceBoundaryAt(i)) continue;
            collect(view, start, i, commands);
            start = i + 1;
        }
        collect(view, start, request.length(), commands);
        return new CommandExecutionRequest(commands);
    }

    private static void collect(RequestLexicalView view, int start, int end,
                                List<String> commands) {
        Matcher connectors = CONNECTOR.matcher(view.maskedLower()).region(start, end);
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<String> joins = new ArrayList<>();
        int clauseStart = start;
        String join = "";
        while (connectors.find()) {
            if (!view.isTopLevel(connectors.start())) continue;
            starts.add(clauseStart);
            ends.add(connectors.start());
            joins.add(join);
            clauseStart = connectors.end();
            join = connectors.group(1) == null ? "," : connectors.group(1);
        }
        starts.add(clauseStart);
        ends.add(end);
        joins.add(join);
        boolean recognized = true;
        boolean prohibited = false;
        for (int i = 0; i < starts.size(); i++) {
            Matcher lead = LEAD.matcher(view.maskedLower()).region(starts.get(i), ends.get(i));
            if (!recognized) continue;
            if (!lead.lookingAt()) {
                if (PROHIBITED_LEAD.matcher(view.maskedLower()).region(starts.get(i), ends.get(i)).lookingAt()) {
                    prohibited = true;
                    continue;
                }
                recognized = false;
                continue;
            }
            // A sequential separator does not revoke an earlier prohibition.
            prohibited = lead.group(1) != null || prohibited && !"but".equals(joins.get(i));
            if (prohibited) continue;
            String tail = view.maskedLower().substring(lead.end(), ends.get(i));
            if (DESCRIPTIVE_TAIL.matcher(tail).find()) {
                recognized = false;
                continue;
            }
            Matcher boundary = CONTEXT_BOUNDARY.matcher(tail);
            int effectiveEnd = boundary.find() ? lead.end() + boundary.start() : ends.get(i);
            String visible = view.maskedLower().substring(starts.get(i), effectiveEnd);
            if (!COMMAND.matcher(lead.group(2)).matches()) continue;
            // A directly requested quoted command is an operand. Other quoted text stays opaque.
            int operand = lead.end();
            while (operand < effectiveEnd && Character.isWhitespace(view.request().charAt(operand))) operand++;
            RequestLexicalView.Span quoted = view.exactQuotedContentStartingAt(operand, effectiveEnd);
            if (quoted != null) {
                visible = view.maskedLower().substring(starts.get(i), lead.end()) + " "
                        + RequestLexicalView.foldCasePreservingLength(view.substring(quoted));
            }
            commands.add(visible);
        }
    }

    private static boolean validUnicode(String request) {
        for (int index = 0; index < request.length(); index++) {
            char value = request.charAt(index);
            if (Character.getType(value) == Character.FORMAT) return false;
            if (Character.isHighSurrogate(value)) {
                if (index + 1 == request.length() || !Character.isLowSurrogate(request.charAt(index + 1))) return false;
                index++;
            } else if (Character.isLowSurrogate(value)) return false;
        }
        return true;
    }
}
