package dev.talos.runtime.verification;

import dev.talos.core.diff.DocumentPairAdmission;
import dev.talos.core.diff.DocumentTextDiff;
import dev.talos.core.privacy.DocumentContentDecision;
import org.htmlunit.corejs.javascript.ast.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Supported source structures are described without evaluating either operand. */
final class DocumentStructureExplanation {
    record Explanation(boolean supported, String text) { }
    private record Structure(String role, String description) { }
    private DocumentStructureExplanation() { }

    static Explanation explain(DocumentPairAdmission.CapturedPair pair) {
        if (!pair.leftIdentity().relativePath().toLowerCase(Locale.ROOT).endsWith(".js")
                || !pair.rightIdentity().relativePath().toLowerCase(Locale.ROOT).endsWith(".js")) return unsupported();
        var view = pair.view(DocumentContentDecision.Use.LOCAL_DISPLAY).orElseThrow();
        if (view.left().representation() != DocumentTextDiff.Representation.EXACT_UTF8_TEXT
                || view.right().representation() != DocumentTextDiff.Representation.EXACT_UTF8_TEXT) return unsupported();
        var left = structure(view.left().text());
        var right = structure(view.right().text());
        if (left == null || right == null) return unsupported();
        String text = "Source-structure explanation. Left " + DocumentComparisonPresentation.quote(pair.leftIdentity().relativePath())
                + " " + left.description() + " Right " + DocumentComparisonPresentation.quote(pair.rightIdentity().relativePath())
                + " " + right.description() + " "
                + (left.role().equals(right.role())
                    ? "Both sources have the stated structural role. The listed selectors, bindings, expressions and literals are compared separately by the exact text diff. Equal runtime behavior is not established."
                    : "The source roles differ. A literal-declaration-only source does not contain the peer's handler and calculation structure.")
                + " These are bounded source relationships, not execution, browser behavior or HTML loading evidence.";
        return text.length() <= 3000 ? new Explanation(true, text) : unsupported();
    }

    private static Structure structure(String source) {
        if (source.startsWith("\ufeff")) source = source.substring(1);
        var script = ObservedWebAnswerSources.script(source);
        if (script.calculationSupported()) {
            List<String> inputs = script.operands().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .map(entry -> DocumentComparisonPresentation.quote(entry.getKey()) + " from "
                            + DocumentComparisonPresentation.quote("#" + entry.getValue())).toList();
            return new Structure("HANDLER", "declares a " + DocumentComparisonPresentation.quote(script.event())
                    + " handler on " + DocumentComparisonPresentation.quote(script.selector()) + ", with numeric input bindings "
                    + String.join(", ", inputs) + ". Its expression " + DocumentComparisonPresentation.quote(script.expression())
                    + (script.precision() == null ? "" : " is formatted with toFixed(" + script.precision() + ") and")
                    + " is assigned to textContent at " + DocumentComparisonPresentation.quote("#" + script.outputId()) + ".");
        }
        try {
            var root = ObservedWebAnswerSources.parseBounded(source);
            var statements = root.getStatements().stream().filter(value -> !(value instanceof EmptyStatement)).toList();
            if (statements.isEmpty() || statements.size() > 16) return null;
            var names = new HashSet<String>();
            var declarations = new ArrayList<String>();
            for (var statement : statements) {
                if (!(statement instanceof VariableDeclaration declaration) || declaration.getVariables().size() != 1) return null;
                int start = declaration.getAbsolutePosition();
                if (start < 0 || !source.startsWith("const", start)
                        || start + 5 >= source.length() || Character.isJavaIdentifierPart(source.charAt(start + 5))) return null;
                var variable = declaration.getVariables().getFirst();
                if (!(variable.getTarget() instanceof Name name) || !names.add(name.getIdentifier())) return null;
                var initializer = variable.getInitializer();
                String literal;
                if (initializer instanceof StringLiteral string) literal = DocumentComparisonPresentation.quote(string.getValue());
                else if (initializer instanceof NumberLiteral number && number.getValue().matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) literal = number.getValue();
                else if (initializer instanceof KeywordLiteral keyword && List.of("true", "false", "null").contains(keyword.toSource())) literal = keyword.toSource();
                else return null;
                declarations.add(DocumentComparisonPresentation.quote(name.getIdentifier()) + " = " + literal);
            }
            return new Structure("LITERALS", "contains only literal constant declarations, " + String.join(", ", declarations)
                    + ". No handler or calculation is declared in this complete source.");
        } catch (RuntimeException unsupported) { return null; }
    }

    private static Explanation unsupported() {
        return new Explanation(false, "The requested explanation remains unresolved outside the supported paired source relationships. Text comparison does not establish behavioral equivalence.");
    }
}
