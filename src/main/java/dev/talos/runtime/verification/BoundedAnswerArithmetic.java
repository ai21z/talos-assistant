package dev.talos.runtime.verification;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A closed expression grammar and exact, bounded mathematical evaluator. Parsing an
 * expression establishes its syntax only. The caller must independently establish
 * the authority and relevance of the expression and every supplied operand.
 * This class does not execute source code or reproduce JavaScript Number semantics.
 */
public final class BoundedAnswerArithmetic {
    public static final int MAX_EXPRESSION_CHARACTERS = 4_096;
    public static final int MAX_NODES = 64;
    public static final int MAX_DEPTH = 16;
    public static final int MAX_LITERAL_CHARACTERS = 64;
    public static final int MAX_SIGNIFICANT_DIGITS = 32;
    public static final int MAX_ABSOLUTE_SCALE = 18;
    public static final int MAX_INTERMEDIATE_BITS = 4_096;
    public static final int MAX_DECIMAL_PLACES = 12;
    private static final int MAX_IDENTIFIER_CHARACTERS = 64;
    private static final BigInteger MAX_MAGNITUDE = BigInteger.ONE.shiftLeft(MAX_INTERMEDIATE_BITS)
            .subtract(BigInteger.ONE);

    private BoundedAnswerArithmetic() {}

    public enum Status {
        SUCCESS,
        UNSUPPORTED_EXPRESSION,
        LIMIT_EXCEEDED,
        INVALID_DECIMAL,
        DIVISION_BY_ZERO,
        MISSING_VARIABLE,
        INVALID_PRECISION,
        INVALID_BINDING
    }

    /** Invalid or excessive input is represented by a status, never a partial AST. */
    public static Parsed parse(String expression) {
        if (expression == null) return failed(Status.UNSUPPORTED_EXPRESSION);
        if (expression.length() > MAX_EXPRESSION_CHARACTERS) return failed(Status.LIMIT_EXCEEDED);
        if (expression.isBlank()) return failed(Status.UNSUPPORTED_EXPRESSION);
        try {
            Parser parser = new Parser(expression);
            Node root = parser.sum(1);
            if (parser.token.kind != TokenKind.END) throw failure(Status.UNSUPPORTED_EXPRESSION);
            return new Parsed(Status.SUCCESS, root);
        } catch (Failure failure) {
            return failed(failure.status);
        }
    }

    public static final class Parsed {
        private final Status status;
        private final Node root;
        private final Set<String> variables;
        private final String canonical;

        private Parsed(Status status, Node root) {
            this.status = status;
            this.root = root;
            LinkedHashSet<String> names = new LinkedHashSet<>();
            if (root != null) collectVariables(root, names);
            variables = Collections.unmodifiableSet(names);
            canonical = root == null ? "" : render(root, Map.of());
        }

        public Status status() { return status; }
        public boolean successful() { return status == Status.SUCCESS; }
        public Set<String> variables() { return variables; }

        /**
         * Fully parenthesized structural identity, with equal decimal literals
         * normalized and squared lowered to multiplication. It does not reorder,
         * reassociate, simplify, or otherwise certify the formula's relevance.
         */
        public String canonical() { return canonical; }

        /** Variables absent from the simultaneous renaming map retain their names. */
        public Parsed renameVariables(Map<String, String> replacements) {
            if (!successful()) return this;
            if (replacements == null) return failed(Status.INVALID_BINDING);
            Map<String, String> admitted = new LinkedHashMap<>();
            for (String variable : variables) {
                if (!replacements.containsKey(variable)) continue;
                String replacement = replacements.get(variable);
                if (!validIdentifier(replacement)) return failed(Status.INVALID_BINDING);
                admitted.put(variable, replacement);
            }
            return new Parsed(Status.SUCCESS, rename(root, admitted));
        }

        public Evaluation evaluate(Map<String, String> operands, int decimalPlaces) {
            if (!successful()) return Evaluation.failed(status, decimalPlaces);
            if (decimalPlaces < 0 || decimalPlaces > MAX_DECIMAL_PLACES) {
                return Evaluation.failed(Status.INVALID_PRECISION, decimalPlaces);
            }
            if (operands == null) return Evaluation.failed(Status.INVALID_BINDING, decimalPlaces);
            try {
                Map<String, Decimal> admitted = new LinkedHashMap<>();
                for (String variable : variables) {
                    if (!operands.containsKey(variable)) throw failure(Status.MISSING_VARIABLE);
                    admitted.put(variable, decimal(operands.get(variable)));
                }
                Rational value = evaluateNode(root, admitted);
                String rounded = round(value, decimalPlaces);
                return new Evaluation(Status.SUCCESS, value.numerator, value.denominator,
                        rounded, render(root, admitted), decimalPlaces);
            } catch (Failure failure) {
                return Evaluation.failed(failure.status, decimalPlaces);
            }
        }
    }

    /** Numerator and positive denominator are present only on success. */
    public record Evaluation(Status status, BigInteger numerator, BigInteger denominator,
                             String rounded, String expression, int decimalPlaces) {
        public boolean successful() { return status == Status.SUCCESS; }
        public String exact() { return successful() ? numerator + "/" + denominator : ""; }
        public String roundingMode() { return "HALF_UP"; }

        private static Evaluation failed(Status status, int decimalPlaces) {
            return new Evaluation(status, null, null, "", "", decimalPlaces);
        }
    }

    private static Parsed failed(Status status) { return new Parsed(status, null); }

    private enum NodeKind { LITERAL, VARIABLE, UNARY, BINARY }
    private record Node(NodeKind kind, String text, Rational value, Node left, Node right,
                        int nodes, int depth) {}
    private record Decimal(Rational value, String canonical) {}
    private record Rational(BigInteger numerator, BigInteger denominator) {}

    private static Node unary(String operator, Node operand) {
        checkTree(operand.nodes + 1, operand.depth + 1);
        return new Node(NodeKind.UNARY, operator, null, operand, null,
                operand.nodes + 1, operand.depth + 1);
    }

    private static Node binary(String operator, Node left, Node right) {
        int nodes = left.nodes + right.nodes + 1;
        int depth = Math.max(left.depth, right.depth) + 1;
        checkTree(nodes, depth);
        return new Node(NodeKind.BINARY, operator, null, left, right, nodes, depth);
    }

    private static void checkTree(int nodes, int depth) {
        if (nodes > MAX_NODES || depth > MAX_DEPTH) throw failure(Status.LIMIT_EXCEEDED);
    }

    private static void collectVariables(Node node, Set<String> names) {
        if (node.kind == NodeKind.VARIABLE) names.add(node.text);
        if (node.left != null) collectVariables(node.left, names);
        if (node.right != null) collectVariables(node.right, names);
    }

    private static Node rename(Node node, Map<String, String> names) {
        if (node.kind == NodeKind.VARIABLE) {
            return new Node(node.kind, names.getOrDefault(node.text, node.text), null, null, null, 1, 1);
        }
        if (node.kind == NodeKind.LITERAL) return node;
        return new Node(node.kind, node.text, null, rename(node.left, names),
                node.right == null ? null : rename(node.right, names), node.nodes, node.depth);
    }

    private static String render(Node node, Map<String, Decimal> bindings) {
        return switch (node.kind) {
            case LITERAL -> node.text;
            case VARIABLE -> bindings.containsKey(node.text) ? bindings.get(node.text).canonical : node.text;
            case UNARY -> "(" + node.text + render(node.left, bindings) + ")";
            case BINARY -> "(" + render(node.left, bindings) + " " + node.text + " "
                    + render(node.right, bindings) + ")";
        };
    }

    private static Rational evaluateNode(Node node, Map<String, Decimal> bindings) {
        return switch (node.kind) {
            case LITERAL -> node.value;
            case VARIABLE -> bindings.get(node.text).value;
            case UNARY -> {
                Rational value = evaluateNode(node.left, bindings);
                yield node.text.equals("-")
                        ? new Rational(value.numerator.negate(), value.denominator) : value;
            }
            case BINARY -> {
                Rational left = evaluateNode(node.left, bindings);
                Rational right = evaluateNode(node.right, bindings);
                yield switch (node.text) {
                    case "+" -> add(left, right);
                    case "-" -> add(left, new Rational(right.numerator.negate(), right.denominator));
                    case "*" -> multiply(left, right);
                    case "/" -> {
                        if (right.numerator.signum() == 0) throw failure(Status.DIVISION_BY_ZERO);
                        yield multiply(left, rational(right.denominator, right.numerator));
                    }
                    default -> throw failure(Status.UNSUPPORTED_EXPRESSION);
                };
            }
        };
    }

    private static Rational rational(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) throw failure(Status.DIVISION_BY_ZERO);
        if (numerator.abs().bitLength() > MAX_INTERMEDIATE_BITS
                || denominator.abs().bitLength() > MAX_INTERMEDIATE_BITS) {
            throw failure(Status.LIMIT_EXCEEDED);
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        return new Rational(numerator.divide(gcd), denominator.divide(gcd));
    }

    private static Rational add(Rational left, Rational right) {
        BigInteger gcd = left.denominator.gcd(right.denominator);
        BigInteger leftFactor = right.denominator.divide(gcd);
        BigInteger rightFactor = left.denominator.divide(gcd);
        BigInteger numerator = boundedAdd(boundedMultiply(left.numerator, leftFactor),
                boundedMultiply(right.numerator, rightFactor));
        return rational(numerator, boundedMultiply(left.denominator, leftFactor));
    }

    private static Rational multiply(Rational left, Rational right) {
        BigInteger crossLeft = left.numerator.gcd(right.denominator);
        BigInteger crossRight = right.numerator.gcd(left.denominator);
        return rational(boundedMultiply(left.numerator.divide(crossLeft), right.numerator.divide(crossRight)),
                boundedMultiply(left.denominator.divide(crossRight), right.denominator.divide(crossLeft)));
    }

    private static BigInteger boundedMultiply(BigInteger left, BigInteger right) {
        if (left.signum() == 0 || right.signum() == 0) return BigInteger.ZERO;
        int bitSum = left.abs().bitLength() + right.abs().bitLength();
        if (bitSum > MAX_INTERMEDIATE_BITS
                && left.abs().compareTo(MAX_MAGNITUDE.divide(right.abs())) > 0) {
            throw failure(Status.LIMIT_EXCEEDED);
        }
        return left.multiply(right);
    }

    private static BigInteger boundedAdd(BigInteger left, BigInteger right) {
        if (left.signum() == right.signum()
                && left.abs().compareTo(MAX_MAGNITUDE.subtract(right.abs())) > 0) {
            throw failure(Status.LIMIT_EXCEEDED);
        }
        return left.add(right);
    }

    private static String round(Rational value, int decimalPlaces) {
        // Precision was checked before the power, and multiplication is bounded before allocation.
        BigInteger factor = BigInteger.TEN.pow(decimalPlaces);
        BigInteger[] quotient = boundedMultiply(value.numerator.abs(), factor)
                .divideAndRemainder(value.denominator);
        BigInteger midpoint = value.denominator.shiftRight(1);
        if (value.denominator.testBit(0)) midpoint = midpoint.add(BigInteger.ONE);
        if (quotient[1].compareTo(midpoint) >= 0) quotient[0] = boundedAdd(quotient[0], BigInteger.ONE);
        String digits = quotient[0].toString();
        if (decimalPlaces > 0) {
            if (digits.length() <= decimalPlaces) digits = "0".repeat(decimalPlaces + 1 - digits.length()) + digits;
            int point = digits.length() - decimalPlaces;
            digits = digits.substring(0, point) + "." + digits.substring(point);
        }
        return value.numerator.signum() < 0 && quotient[0].signum() != 0 ? "-" + digits : digits;
    }

    private static Decimal decimal(String input) {
        if (input == null || input.isEmpty()) throw failure(Status.INVALID_DECIMAL);
        if (input.length() > MAX_LITERAL_CHARACTERS) throw failure(Status.LIMIT_EXCEEDED);
        int position = 0;
        boolean negative = input.charAt(0) == '-';
        if (negative || input.charAt(0) == '+') position++;
        StringBuilder digits = new StringBuilder(MAX_LITERAL_CHARACTERS);
        int fractionalDigits = 0;
        int significantDigits = 0;
        boolean significantStarted = false;
        boolean point = false;
        while (position < input.length()) {
            char current = input.charAt(position);
            if (digit(current)) {
                digits.append(current);
                if (point) fractionalDigits++;
                if (current != '0') significantStarted = true;
                if (significantStarted && ++significantDigits > MAX_SIGNIFICANT_DIGITS) {
                    throw failure(Status.LIMIT_EXCEEDED);
                }
            } else if (current == '.' && !point) {
                point = true;
            } else {
                break;
            }
            position++;
        }
        if (digits.isEmpty()) throw failure(Status.INVALID_DECIMAL);
        int exponent = 0;
        if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
            position++;
            boolean negativeExponent = position < input.length() && input.charAt(position) == '-';
            if (position < input.length() && (negativeExponent || input.charAt(position) == '+')) position++;
            int start = position;
            while (position < input.length() && digit(input.charAt(position))) {
                int next = input.charAt(position++) - '0';
                if (exponent > (MAX_LITERAL_CHARACTERS + MAX_ABSOLUTE_SCALE - next) / 10) {
                    throw failure(Status.LIMIT_EXCEEDED);
                }
                exponent = exponent * 10 + next;
            }
            if (position == start) throw failure(Status.INVALID_DECIMAL);
            if (negativeExponent) exponent = -exponent;
        }
        if (position != input.length()) throw failure(Status.INVALID_DECIMAL);
        int scale = fractionalDigits - exponent;
        if (Math.abs(scale) > MAX_ABSOLUTE_SCALE) throw failure(Status.LIMIT_EXCEEDED);
        // All textual, digit, and scale bounds precede integer and power construction.
        BigInteger unscaled = new BigInteger(digits.toString());
        if (negative) unscaled = unscaled.negate();
        BigInteger power = BigInteger.TEN.pow(Math.abs(scale));
        Rational value = scale >= 0 ? rational(unscaled, power)
                : rational(boundedMultiply(unscaled, power), BigInteger.ONE);
        return new Decimal(value, normalizedDecimal(unscaled, scale));
    }

    private static String normalizedDecimal(BigInteger unscaled, int scale) {
        if (unscaled.signum() == 0) return "0";
        String digits = unscaled.abs().toString();
        while (scale > 0 && digits.endsWith("0")) {
            digits = digits.substring(0, digits.length() - 1);
            scale--;
        }
        if (scale <= 0) digits += "0".repeat(-scale);
        else if (digits.length() > scale) {
            int point = digits.length() - scale;
            digits = digits.substring(0, point) + "." + digits.substring(point);
        } else digits = "0." + "0".repeat(scale - digits.length()) + digits;
        return unscaled.signum() < 0 ? "-" + digits : digits;
    }

    private enum TokenKind { DECIMAL, IDENTIFIER, PLUS, MINUS, MULTIPLY, DIVIDE, SQUARED, OPEN, CLOSE, END }
    private record Token(TokenKind kind, String text) {}

    private static final class Parser {
        private final String source;
        private int position;
        private int admittedNodes;
        private Token token;

        private Parser(String source) {
            this.source = source;
            advance();
        }

        private Node sum(int nesting) {
            Node left = product(nesting);
            while (token.kind == TokenKind.PLUS || token.kind == TokenKind.MINUS) {
                String operator = token.kind == TokenKind.PLUS ? "+" : "-";
                reserveNodes(1);
                advance();
                left = binary(operator, left, product(nesting));
            }
            return left;
        }

        private Node product(int nesting) {
            Node left = signed(nesting);
            while (token.kind == TokenKind.MULTIPLY || token.kind == TokenKind.DIVIDE) {
                String operator = token.kind == TokenKind.MULTIPLY ? "*" : "/";
                reserveNodes(1);
                advance();
                left = binary(operator, left, signed(nesting));
            }
            return left;
        }

        private Node signed(int nesting) {
            if (token.kind == TokenKind.PLUS || token.kind == TokenKind.MINUS) {
                if (nesting >= MAX_DEPTH) throw failure(Status.LIMIT_EXCEEDED);
                String operator = token.kind == TokenKind.PLUS ? "+" : "-";
                reserveNodes(1);
                advance();
                return unary(operator, signed(nesting + 1));
            }
            Node node = primary(nesting);
            while (token.kind == TokenKind.SQUARED) {
                // Check expanded size and depth before either consuming or allocating the operator.
                checkTree(node.nodes * 2 + 1, node.depth + 1);
                reserveNodes(node.nodes + 1);
                advance();
                node = binary("*", node, node);
            }
            return node;
        }

        private Node primary(int nesting) {
            if (token.kind == TokenKind.DECIMAL) {
                reserveNodes(1);
                Decimal value = decimal(token.text);
                advance();
                return new Node(NodeKind.LITERAL, value.canonical, value.value, null, null, 1, 1);
            }
            if (token.kind == TokenKind.IDENTIFIER) {
                reserveNodes(1);
                String name = token.text;
                advance();
                return new Node(NodeKind.VARIABLE, name, null, null, null, 1, 1);
            }
            if (token.kind == TokenKind.OPEN) {
                if (nesting >= MAX_DEPTH) throw failure(Status.LIMIT_EXCEEDED);
                advance();
                Node node = sum(nesting + 1);
                if (token.kind != TokenKind.CLOSE) throw failure(Status.UNSUPPORTED_EXPRESSION);
                advance();
                return node;
            }
            throw failure(Status.UNSUPPORTED_EXPRESSION);
        }

        private void reserveNodes(int count) {
            if (admittedNodes + count > MAX_NODES) throw failure(Status.LIMIT_EXCEEDED);
            admittedNodes += count;
        }

        private void advance() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
            if (position == source.length()) {
                token = new Token(TokenKind.END, "");
                return;
            }
            int start = position;
            char current = source.charAt(position++);
            TokenKind symbol = switch (current) {
                case '+' -> TokenKind.PLUS;
                case '-' -> TokenKind.MINUS;
                case '*' -> TokenKind.MULTIPLY;
                case '/' -> TokenKind.DIVIDE;
                case '(' -> TokenKind.OPEN;
                case ')' -> TokenKind.CLOSE;
                default -> null;
            };
            if (symbol != null) {
                token = new Token(symbol, Character.toString(current));
                return;
            }
            if (digit(current) || current == '.') {
                while (position < source.length() && (digit(source.charAt(position)) || source.charAt(position) == '.')) {
                    position++;
                    if (position - start > MAX_LITERAL_CHARACTERS) throw failure(Status.LIMIT_EXCEEDED);
                }
                if (position < source.length() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                    position++;
                    if (position < source.length() && (source.charAt(position) == '+' || source.charAt(position) == '-')) position++;
                    while (position < source.length() && digit(source.charAt(position))) {
                        position++;
                        if (position - start > MAX_LITERAL_CHARACTERS) throw failure(Status.LIMIT_EXCEEDED);
                    }
                }
                if (position - start > MAX_LITERAL_CHARACTERS) throw failure(Status.LIMIT_EXCEEDED);
                token = new Token(TokenKind.DECIMAL, source.substring(start, position));
                return;
            }
            if (identifierStart(current)) {
                while (position < source.length() && identifierPart(source.charAt(position))) {
                    position++;
                    if (position - start > MAX_IDENTIFIER_CHARACTERS) throw failure(Status.LIMIT_EXCEEDED);
                }
                String word = source.substring(start, position);
                TokenKind kind = switch (word.toLowerCase(Locale.ROOT)) {
                    case "plus" -> TokenKind.PLUS;
                    case "minus" -> TokenKind.MINUS;
                    case "squared" -> TokenKind.SQUARED;
                    case "multiplied" -> TokenKind.MULTIPLY;
                    case "divided" -> TokenKind.DIVIDE;
                    default -> TokenKind.IDENTIFIER;
                };
                if (kind == TokenKind.MULTIPLY || kind == TokenKind.DIVIDE) {
                    while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
                    if (position + 2 > source.length() || !source.regionMatches(true, position, "by", 0, 2)
                            || position + 2 < source.length() && identifierPart(source.charAt(position + 2))) {
                        throw failure(Status.UNSUPPORTED_EXPRESSION);
                    }
                    position += 2;
                }
                token = new Token(kind, word);
                return;
            }
            throw failure(Status.UNSUPPORTED_EXPRESSION);
        }
    }

    private static boolean validIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_IDENTIFIER_CHARACTERS
                || !identifierStart(value.charAt(0))) return false;
        for (int index = 1; index < value.length(); index++) {
            if (!identifierPart(value.charAt(index))) return false;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "plus", "minus", "multiplied", "divided", "squared" -> false;
            default -> true;
        };
    }

    private static boolean digit(char value) { return value >= '0' && value <= '9'; }
    private static boolean identifierStart(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value == '_' || value == '$';
    }
    private static boolean identifierPart(char value) { return identifierStart(value) || digit(value); }

    private static Failure failure(Status status) { return new Failure(status); }
    private static final class Failure extends RuntimeException {
        private final Status status;
        private Failure(Status status) {
            super(null, null, false, false);
            this.status = status;
        }
    }
}
