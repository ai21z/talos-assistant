package dev.talos.runtime.verification;

import org.htmlunit.corejs.javascript.CompilerEnvirons;
import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.ErrorReporter;
import org.htmlunit.corejs.javascript.EvaluatorException;
import org.htmlunit.corejs.javascript.Node;
import org.htmlunit.corejs.javascript.Parser;
import org.htmlunit.corejs.javascript.Token;
import org.htmlunit.corejs.javascript.ast.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded source relations are inspected without executing the supplied text. */
final class ObservedWebAnswerSources {
    private static final Pattern TAG = Pattern.compile(
            "(?is)<(/?)([a-z][a-z0-9-]*+)((?:[^<>\"']++|\"[^\"]*+\"|'[^']*+')*+)>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\G\\s+([a-zA-Z_:][a-zA-Z0-9_.:-]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'))?");
    private static final Set<String> VOID = Set.of("meta", "link", "input", "br", "hr", "img");
    private static final Set<String> ELEMENTS = Set.of("html", "head", "body", "title", "meta", "link",
            "main", "div", "section", "article", "header", "footer", "p", "h1", "h2", "h3", "h4",
            "h5", "h6", "label", "input", "button", "script", "style", "span", "form", "br", "hr", "img");
    private static final Pattern LABEL = Pattern.compile("([A-Za-z][A-Za-z0-9 _-]{0,63})\\s*\\(([^()\\s]{1,24})\\)");
    private static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");

    private ObservedWebAnswerSources() {}

    record Button(String id, String label, int line) {}
    record Input(String id, String label, String unit, String defaultValue, int line) {}
    record Html(String reason, String labelReason, List<Button> buttons, Map<String, Input> inputs,
                Set<String> ids) {
        Html { buttons = List.copyOf(buttons); inputs = Map.copyOf(inputs); ids = Set.copyOf(ids); }
        boolean supported() { return reason.isEmpty(); }
    }
    record Script(String reason, String calculationReason, String selector, String event, int queryLine,
                  int listenerLine, Map<String, String> operands, String expression, String outputId,
                  Integer precision) {
        Script { operands = Map.copyOf(operands); }
        boolean supported() { return reason.isEmpty(); }
        boolean calculationSupported() { return supported() && calculationReason.isEmpty(); }
    }
    record Result(Html html, Script script) {}

    static Result inspect(String html, String script) {
        return new Result(html(html), script(script));
    }

    private static Html html(String text) {
        if (text == null || text.length() > 16_000) return badHtml("HTML_LIMIT");
        Deque<Frame> stack = new ArrayDeque<>();
        List<Button> buttons = new ArrayList<>();
        Map<String, Input> inputs = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        List<LabelBinding> labels = new ArrayList<>();
        int cursor = 0;
        try {
            while (cursor < text.length()) {
                int next = text.indexOf('<', cursor);
                if (next < 0) next = text.length();
                String content = text.substring(cursor, next);
                if (!stack.isEmpty()) stack.peek().text.append(content);
                else if (!content.isBlank()) throw new Rejected();
                if (next == text.length()) break;
                if (text.startsWith("<!--", next)) {
                    int end = text.indexOf("-->", next + 4);
                    if (end < 0) throw new Rejected();
                    if (stack.stream().anyMatch(frame -> frame.tag.equals("button") || frame.tag.equals("label")))
                        throw new Rejected();
                    cursor = end + 3;
                    continue;
                }
                Matcher doctype = Pattern.compile("(?i)<!doctype\\s+html\\s*>").matcher(text).region(next, text.length());
                if (doctype.lookingAt()) { cursor = doctype.end(); continue; }
                Matcher tag = TAG.matcher(text).region(next, text.length());
                if (!tag.lookingAt()) throw new Rejected();
                String name = tag.group(2).toLowerCase(Locale.ROOT);
                if (!ELEMENTS.contains(name)) throw new Rejected();
                boolean closing = !tag.group(1).isEmpty();
                if (closing) {
                    if (!tag.group(3).isBlank() || stack.isEmpty() || !stack.peek().tag.equals(name))
                        throw new Rejected();
                    Frame frame = stack.pop();
                    String plain = frame.text.toString().strip().replaceAll("\\s+", " ");
                    if (name.equals("button")) {
                        if (frame.complex || !literal(plain) || !validId(frame.attributes.get("id"))) throw new Rejected();
                        buttons.add(new Button(frame.attributes.get("id"), plain, frame.line));
                    }
                    if (name.equals("label")) {
                        if (frame.complex || !literal(plain)) throw new Rejected();
                        String target = frame.attributes.get("for");
                        if (target != null && !frame.controls.isEmpty() || target == null && frame.controls.size() != 1)
                            throw new Rejected();
                        labels.add(new LabelBinding(target == null ? frame.controls.getFirst() : target, plain));
                    }
                    cursor = tag.end();
                    continue;
                }
                Map<String, String> attributes = attributes(tag.group(3));
                if (attributes == null || attributes.keySet().stream().anyMatch(key -> key.startsWith("on") || key.equals("srcdoc")))
                    throw new Rejected();
                String id = attributes.get("id");
                if (id != null && (!validId(id) || !ids.add(id))) throw new Rejected();
                if (!stack.isEmpty() && Set.of("label", "button").contains(stack.peek().tag)) {
                    if (stack.peek().tag.equals("label") && name.equals("input")) {
                        if (!validId(id)) throw new Rejected();
                        stack.peek().controls.add(id);
                    } else stack.peek().complex = true;
                }
                if (name.equals("label") && stack.stream().anyMatch(frame -> frame.tag.equals("label"))) throw new Rejected();
                int line = line(text, next);
                if (name.equals("input")) {
                    if (!validId(id)) throw new Rejected();
                    inputs.put(id, new Input(id, "", "", attributes.getOrDefault("value", ""), line));
                }
                if (Set.of("script", "style", "title").contains(name)) {
                    Matcher end = Pattern.compile("(?is)</" + name + "\\s*>").matcher(text);
                    if (!end.find(tag.end())) throw new Rejected();
                    if (name.equals("script") && !text.substring(tag.end(), end.start()).isBlank()) throw new Rejected();
                    cursor = end.end();
                } else {
                    if (!VOID.contains(name)) stack.push(new Frame(name, attributes, line));
                    if (stack.size() > 64) throw new Rejected();
                    cursor = tag.end();
                }
            }
            if (!stack.isEmpty()) throw new Rejected();
            Set<String> labeled = new LinkedHashSet<>();
            String labelReason = "";
            for (LabelBinding label : labels) {
                Input input = inputs.get(label.target);
                Matcher match = LABEL.matcher(label.text);
                if (input == null || !labeled.add(label.target) || !match.matches()) {
                    labelReason = "INPUT_LABEL_UNSUPPORTED";
                    continue;
                }
                inputs.put(input.id(), new Input(input.id(), match.group(1).strip(), match.group(2), input.defaultValue(), input.line()));
            }
            return new Html("", labelReason, buttons, inputs, ids);
        } catch (Rejected failure) { return badHtml("HTML_RELATION_UNSUPPORTED"); }
    }

    private static Map<String, String> attributes(String raw) {
        String source = raw.replaceFirst("/\\s*$", "");
        Matcher matcher = ATTRIBUTE.matcher(source);
        Map<String, String> result = new LinkedHashMap<>();
        int end = 0;
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3) : "";
            if (!literal(value) || result.putIfAbsent(name, value) != null) return null;
            end = matcher.end();
        }
        return source.substring(end).isBlank() ? result : null;
    }

    static AstRoot parseBounded(String source) {
            if (!admitted(source)) throw new Rejected();
            CompilerEnvirons environs = new CompilerEnvirons();
            environs.setLanguageVersion(Context.VERSION_ECMASCRIPT);
            environs.setRecoverFromErrors(false);
            environs.setIdeMode(false);
            AstRoot root = new Parser(environs, new ErrorReporter() {
                public void warning(String message, String name, int line, String lineSource, int column) {}
                public void error(String message, String name, int line, String lineSource, int column) { throw new Rejected(); }
                public EvaluatorException runtimeError(String message, String name, int line, String lineSource, int column) {
                    return new EvaluatorException("Unsupported source.");
                }
            }).parse(source, "observed source", 1);
            int[] nodes = {0};
            root.visit(node -> {
                if (++nodes[0] > 4096 || node instanceof RegExpLiteral || node instanceof TemplateLiteral) throw new Rejected();
                int depth = 0;
                for (AstNode parent = node.getParent(); parent != null; parent = parent.getParent())
                    if (++depth > 128) throw new Rejected();
                return true;
            });
            return root;
    }

    static Script script(String source) {
        if (!admitted(source)) return badScript("JAVASCRIPT_ADMISSION_UNSUPPORTED");
        try {
            AstRoot root = parseBounded(source);
            List<AstNode> statements = root.getStatements().stream().filter(node -> !(node instanceof EmptyStatement)).toList();
            if (statements.size() != 2) throw new Rejected();
            VariableInitializer lookup = declaration(statements.get(0), source);
            String variable = name(lookup.getTarget());
            if (Set.of("document", "Number").contains(variable)) throw new Rejected();
            String selector = query(lookup.getInitializer());
            FunctionCall listener = call(expression(statements.get(1)));
            if (!(listener.getTarget() instanceof PropertyGet member) || !name(member.getTarget()).equals(variable)
                    || !member.getProperty().getIdentifier().equals("addEventListener") || listener.getArguments().size() != 2
                    || !(listener.getArguments().get(0) instanceof StringLiteral event) || !event.getValue().equals("click")
                    || !(listener.getArguments().get(1) instanceof FunctionNode function) || !function.getParams().isEmpty()
                    || !(function.getBody() instanceof Block body)) throw new Rejected();
            Script binding = new Script("", "CALCULATION_SOURCE_UNSUPPORTED", selector, event.getValue(),
                    lookup.getLineno(), listener.getLineno(), Map.of(), "", "", null);
            try {
                if (!function.getName().isEmpty() || function.isGenerator()) throw new Rejected();
                List<AstNode> callback = new ArrayList<>();
                for (Node child : body) if (!(child instanceof EmptyStatement)) callback.add((AstNode) child);
                if (callback.size() < 2 || callback.size() > 33) throw new Rejected();
                Map<String, String> operands = new LinkedHashMap<>();
                for (int i = 0; i < callback.size() - 1; i++) {
                    VariableInitializer input = declaration(callback.get(i), source);
                    String inputName = name(input.getTarget());
                    if (Set.of("document", "Number", variable).contains(inputName)) throw new Rejected();
                    FunctionCall conversion = call(input.getInitializer());
                    if (!name(conversion.getTarget()).equals("Number") || conversion.getArguments().size() != 1
                            || !(conversion.getArguments().getFirst() instanceof PropertyGet value)
                            || !value.getProperty().getIdentifier().equals("value")) throw new Rejected();
                    String inputId = query(value.getTarget()).substring(1);
                    if (operands.containsValue(inputId) || operands.putIfAbsent(inputName, inputId) != null) throw new Rejected();
                }
                AstNode last = expression(callback.getLast());
                if (!(last instanceof Assignment assignment) || assignment.getOperator() != Token.ASSIGN
                        || !(assignment.getLeft() instanceof PropertyGet output)
                        || !output.getProperty().getIdentifier().equals("textContent")) throw new Rejected();
                String outputId = query(output.getTarget()).substring(1);
                AstNode value = unwrap(assignment.getRight());
                Integer precision = null;
                if (value instanceof FunctionCall format) {
                    if (format instanceof NewExpression || !(format.getTarget() instanceof PropertyGet fixed) || !fixed.getProperty().getIdentifier().equals("toFixed")
                            || format.getArguments().size() != 1 || !(format.getArguments().getFirst() instanceof NumberLiteral digits)
                            || !digits.getValue().matches("[0-9]{1,2}")) throw new Rejected();
                    precision = Integer.valueOf(digits.getValue());
                    if (precision > 12) throw new Rejected();
                    value = fixed.getTarget();
                }
                String arithmetic = arithmetic(value, operands.keySet(), 0, new int[] {0});
                return new Script("", "", selector, event.getValue(), lookup.getLineno(), listener.getLineno(),
                        operands, arithmetic, outputId, precision);
            } catch (Rejected failure) { return binding; }
        } catch (RuntimeException failure) { return badScript("JAVASCRIPT_RELATION_UNSUPPORTED"); }
    }

    private static String arithmetic(AstNode node, Set<String> variables, int depth, int[] count) {
        if (depth > 16 || ++count[0] > 64) throw new Rejected();
        if (node instanceof ParenthesizedExpression parentheses) return "(" + arithmetic(parentheses.getExpression(), variables, depth + 1, count) + ")";
        if (node instanceof Name variable && variables.contains(variable.getIdentifier())) return variable.getIdentifier();
        if (node instanceof NumberLiteral number && number.getValue().matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) return number.getValue();
        if (node instanceof UnaryExpression unary && Set.of(Token.POS, Token.NEG).contains(unary.getOperator()))
            return "(" + (unary.getOperator() == Token.NEG ? "-" : "+") + arithmetic(unary.getOperand(), variables, depth + 1, count) + ")";
        if (node instanceof InfixExpression binary && !(node instanceof PropertyGet) && !(node instanceof Assignment)) {
            String operator = switch (binary.getOperator()) {
                case Token.ADD -> "+";
                case Token.SUB -> "-";
                case Token.MUL -> "*";
                case Token.DIV -> "/";
                default -> throw new Rejected();
            };
            return "(" + arithmetic(binary.getLeft(), variables, depth + 1, count) + operator
                    + arithmetic(binary.getRight(), variables, depth + 1, count) + ")";
        }
        throw new Rejected();
    }

    private static VariableInitializer declaration(AstNode node, String source) {
        if (!(node instanceof VariableDeclaration declaration)
                || !(declaration.isConst() || declaration.isLet()) || declaration.getVariables().size() != 1)
            throw new Rejected();
        // This parser normalizes ECMAScript const declarations to LET nodes. Bind
        // the declaration keyword to its original AST position instead of treating
        // that normalization as authority to accept mutable let declarations.
        int start = declaration.getAbsolutePosition();
        int end = start + "const".length();
        if (start < 0 || end >= source.length() || !source.startsWith("const", start)
                || Character.isJavaIdentifierPart(source.charAt(end))) throw new Rejected();
        return declaration.getVariables().getFirst();
    }
    private static AstNode expression(AstNode node) {
        if (!(node instanceof ExpressionStatement statement)) throw new Rejected();
        return statement.getExpression();
    }
    private static AstNode unwrap(AstNode node) {
        while (node instanceof ParenthesizedExpression parentheses) node = parentheses.getExpression();
        return node;
    }
    private static FunctionCall call(AstNode node) {
        if (!(node instanceof FunctionCall call) || node instanceof NewExpression) throw new Rejected();
        return call;
    }
    private static String name(AstNode node) {
        if (!(node instanceof Name name)) throw new Rejected();
        return name.getIdentifier();
    }
    private static String query(AstNode node) {
        FunctionCall call = call(node);
        if (!(call.getTarget() instanceof PropertyGet member) || !name(member.getTarget()).equals("document")
                || !member.getProperty().getIdentifier().equals("querySelector") || call.getArguments().size() != 1
                || !(call.getArguments().getFirst() instanceof StringLiteral selector)
                || !selector.getValue().matches("#[A-Za-z_][A-Za-z0-9_-]{0,63}")) throw new Rejected();
        return selector.getValue();
    }

    private static boolean admitted(String source) {
        if (source == null || source.isBlank() || source.length() > 16_000) return false;
        Deque<Character> delimiters = new ArrayDeque<>();
        int tokens = 0, unary = 0, assignments = 0;
        boolean operand = true;
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i + 2); i = end < 0 ? source.length() : end + 1; continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2); if (end < 0) return false; i = end + 2; continue;
            }
            if (++tokens > 2048 || c == '`' || c == '\0') return false;
            if (c == '\'' || c == '"') {
                char quote = c; boolean closed = false;
                for (i++; i < source.length(); i++) {
                    c = source.charAt(i);
                    if (c == '\n' || c == '\r') return false;
                    if (c == '\\') { i++; continue; }
                    if (c == quote) { i++; closed = true; break; }
                }
                if (!closed) return false;
                operand = false; unary = 0; continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int start = i;
                while (++i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || "_$".indexOf(source.charAt(i)) >= 0)) {}
                if (Set.of("typeof", "void", "delete", "new", "yield", "await").contains(source.substring(start, i))) return false;
                operand = false; unary = 0; continue;
            }
            if ("({[".indexOf(c) >= 0) {
                delimiters.push(c); if (delimiters.size() > 64) return false; operand = true;
            } else if (")}]".indexOf(c) >= 0) {
                if (delimiters.isEmpty() || "({[".indexOf(delimiters.pop()) != ")}]".indexOf(c)) return false;
                operand = false;
            } else if (c == '/' && operand) return false;
            else if (c == '+' || c == '-') {
                if (++unary > 16) return false;
                operand = true;
            } else if (c == '=') {
                if (++assignments > 16) return false;
                operand = true;
            } else if (c == ';') { assignments = 0; unary = 0; operand = true; }
            else if (",*>".indexOf(c) >= 0) {
                if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '*') return false;
                operand = true; unary = 0;
            }
            else if (c != '.' && c != '/') return false;
            i++;
        }
        return delimiters.isEmpty();
    }

    private static boolean validId(String value) { return value != null && ID.matcher(value).matches(); }
    private static boolean literal(String value) { return value != null && value.indexOf('&') < 0 && value.indexOf('\0') < 0 && value.length() <= 4096; }
    private static int line(String text, int index) { return 1 + (int) text.substring(0, index).chars().filter(c -> c == '\n').count(); }
    private static Html badHtml(String reason) { return new Html(reason, reason, List.of(), Map.of(), Set.of()); }
    private static Script badScript(String reason) { return new Script(reason, reason, "", "", 0, 0, Map.of(), "", "", null); }
    private record LabelBinding(String target, String text) {}
    private static final class Frame {
        final String tag;
        final Map<String, String> attributes;
        final int line;
        final StringBuilder text = new StringBuilder();
        final List<String> controls = new ArrayList<>();
        boolean complex;
        Frame(String tag, Map<String, String> attributes, int line) { this.tag = tag; this.attributes = attributes; this.line = line; }
    }
    private static final class Rejected extends RuntimeException {}
}
