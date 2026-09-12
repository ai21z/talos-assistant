package dev.talos.runtime.verification;

import org.htmlunit.corejs.javascript.CompilerEnvirons;
import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.ErrorReporter;
import org.htmlunit.corejs.javascript.EvaluatorException;
import org.htmlunit.corejs.javascript.Parser;
import org.htmlunit.corejs.javascript.ast.ArrayLiteral;
import org.htmlunit.corejs.javascript.ast.Assignment;
import org.htmlunit.corejs.javascript.ast.AstNode;
import org.htmlunit.corejs.javascript.ast.Name;
import org.htmlunit.corejs.javascript.ast.ObjectLiteral;
import org.htmlunit.corejs.javascript.ast.ObjectProperty;
import org.htmlunit.corejs.javascript.ast.Spread;
import org.htmlunit.corejs.javascript.ast.SpreadObjectProperty;
import org.htmlunit.corejs.javascript.ast.VariableDeclaration;

import java.util.List;

final class StaticWebJavaScriptSyntaxVerifier {

    private StaticWebJavaScriptSyntaxVerifier() {}

    /** HtmlUnit 4.21 coerces a global lexical `status` binding through Window.status.
     * It is not the ECMAScript global lexical binding observed in Chrome. Inspect
     * declarations rather than matching comments, strings, or function-local names.
     * This check observes source only; it must never rewrite or execute it.
     */
    static boolean hasUnsupportedGlobalStatusBinding(String source) {
        if (source == null || source.isBlank()) return false;
        CompilerEnvirons environs = new CompilerEnvirons();
        environs.setLanguageVersion(Context.VERSION_ECMASCRIPT);
        environs.setRecoverFromErrors(false);
        environs.setIdeMode(false);
        try {
            return new Parser(environs, new ThrowingErrorReporter())
                    .parse(source, "browser compatibility observation", 1).getStatements().stream()
                    .filter(VariableDeclaration.class::isInstance)
                    .map(VariableDeclaration.class::cast)
                    .filter(declaration -> declaration.isConst() || declaration.isLet())
                    .flatMap(declaration -> declaration.getVariables().stream())
                    .anyMatch(variable -> bindsStatus(variable.getTarget()));
        } catch (RuntimeException ignored) {
            // The existing syntax/runtime error paths remain responsible for
            // malformed or unsupported syntax; do not infer this specific gap.
            return false;
        }
    }

    private static boolean bindsStatus(AstNode binding) {
        // Follow binding positions only. In {status: outputNode}, the property key
        // is not a declaration; defaults' right sides are likewise not bindings.
        // Inspect direct program declarations, never nested function/block scopes.
        if (binding instanceof Name name) return "status".equals(name.getIdentifier());
        if (binding instanceof Assignment assignment) return bindsStatus(assignment.getLeft());
        if (binding instanceof Spread spread) return bindsStatus(spread.getExpression());
        if (binding instanceof ArrayLiteral array) {
            return array.getElements().stream().anyMatch(StaticWebJavaScriptSyntaxVerifier::bindsStatus);
        }
        if (binding instanceof ObjectLiteral object) {
            return object.getElements().stream().anyMatch(property -> {
                if (property instanceof ObjectProperty named) return bindsStatus(named.getValue());
                if (property instanceof SpreadObjectProperty rest) return bindsStatus(rest.getSpreadNode());
                return false;
            });
        }
        return false;
    }

    static List<String> syntaxProblems(String jsFile, String js) {
        if (js == null || js.isBlank()) return List.of();
        String source = jsFile == null || jsFile.isBlank() ? "JavaScript" : jsFile;
        CompilerEnvirons environs = new CompilerEnvirons();
        environs.setLanguageVersion(Context.VERSION_ECMASCRIPT);
        environs.setRecoverFromErrors(false);
        environs.setIdeMode(false);
        try {
            new Parser(environs, new ThrowingErrorReporter()).parse(js, source, 1);
            return List.of();
        } catch (EvaluatorException e) {
            return List.of(source + ": JavaScript syntax check failed"
                    + location(e) + ": " + safeMessage(e));
        } catch (RuntimeException e) {
            return List.of(source + ": JavaScript syntax check failed: " + safeMessage(e));
        }
    }

    private static String location(EvaluatorException e) {
        int line = e == null ? 0 : e.lineNumber();
        int column = e == null ? 0 : e.columnNumber();
        if (line > 0 && column > 0) return " at line " + line + ", column " + column;
        if (line > 0) return " at line " + line;
        return "";
    }

    private static String safeMessage(Throwable t) {
        String message = t == null ? "" : t.getMessage();
        if (message == null || message.isBlank()) return "invalid JavaScript";
        return message.replaceAll("\\s+", " ").strip();
    }

    private static final class ThrowingErrorReporter implements ErrorReporter {
        @Override
        public void warning(String message, String sourceName, int line, String lineSource, int lineOffset) {
            // Warnings are not proof of invalid JavaScript.
        }

        @Override
        public void error(String message, String sourceName, int line, String lineSource, int lineOffset) {
            throw runtimeError(message, sourceName, line, lineSource, lineOffset);
        }

        @Override
        public EvaluatorException runtimeError(
                String message,
                String sourceName,
                int line,
                String lineSource,
                int lineOffset
        ) {
            return new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
        }
    }
}
