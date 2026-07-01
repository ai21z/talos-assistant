package dev.talos.runtime.verification;

import org.htmlunit.corejs.javascript.CompilerEnvirons;
import org.htmlunit.corejs.javascript.Context;
import org.htmlunit.corejs.javascript.ErrorReporter;
import org.htmlunit.corejs.javascript.EvaluatorException;
import org.htmlunit.corejs.javascript.Parser;

import java.util.List;

final class StaticWebJavaScriptSyntaxVerifier {

    private StaticWebJavaScriptSyntaxVerifier() {}

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
