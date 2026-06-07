package dev.talos.core.index;

import dev.talos.core.ingest.SourceClassifier;
import dev.talos.spi.types.SourceFormat;
import dev.talos.spi.types.SourceType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Lightweight deterministic symbol extraction for code-navigation evidence. */
public final class SymbolExtractor {

    private static final Pattern JAVA_TYPE = Pattern.compile(
            "\\b(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed)\\s+)*"
                    + "(class|interface|record|enum|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\\s+)*"
                    + "(?:<[^;{}()]+>\\s+)?"
                    + "[A-Za-z_$][A-Za-z0-9_$<>\\[\\],.?]*(?:\\s+[A-Za-z_$][A-Za-z0-9_$<>\\[\\],.?]*)*\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;{}]*\\)\\s*"
                    + "(?:throws\\s+[A-Za-z_$][A-Za-z0-9_$.]*(?:\\s*,\\s*[A-Za-z_$][A-Za-z0-9_$.]*)*\\s*)?"
                    + "(?:\\{|;|$)");
    private static final Pattern JS_CLASS = Pattern.compile(
            "\\b(?:export\\s+default\\s+|export\\s+)?(?:abstract\\s+)?class\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern JS_INTERFACE = Pattern.compile(
            "\\b(?:export\\s+)?interface\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern JS_FUNCTION = Pattern.compile(
            "\\b(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern JS_ARROW_FUNCTION = Pattern.compile(
            "\\b(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^=]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>");
    private static final Pattern PY_CLASS = Pattern.compile("^\\s*class\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern PY_FUNCTION = Pattern.compile("^\\s*def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    private SymbolExtractor() {}

    public static List<SymbolHit> extract(String relPath, String content) {
        if (relPath == null || relPath.isBlank() || content == null || content.isBlank()) {
            return List.of();
        }
        var identity = SourceClassifier.classify(relPath);
        if (identity.type() != SourceType.CODE_FILE && identity.type() != SourceType.BUILD_FILE) {
            return List.of();
        }

        Map<String, SymbolHit> hits = new LinkedHashMap<>();
        SourceFormat format = identity.format();
        boolean inBlockComment = false;
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            CommentStripped stripped = stripComments(lines[i], inBlockComment);
            inBlockComment = stripped.inBlockComment();
            String line = stripped.line();
            if (line.isBlank()) continue;
            String scanLine = maskStringLiteralContent(line);

            switch (format) {
                case JAVA, KOTLIN, SCALA, GROOVY -> extractJavaLike(relPath, scanLine, line, i + 1, hits);
                case JAVASCRIPT, TYPESCRIPT -> extractJavaScriptLike(relPath, scanLine, line, i + 1, hits);
                case PYTHON -> extractPython(relPath, scanLine, line, i + 1, hits);
                default -> {
                    // Unsupported code formats still fall back to no symbol hits.
                }
            }
        }
        return hits.values().stream()
                .sorted(Comparator
                        .comparing(SymbolHit::path, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(SymbolHit::lineStart)
                        .thenComparing(SymbolHit::symbol, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(hit -> hit.kind().name()))
                .toList();
    }

    private static void extractJavaLike(String path, String scanLine, String signatureLine, int lineNumber, Map<String, SymbolHit> hits) {
        var typeMatcher = JAVA_TYPE.matcher(scanLine);
        if (typeMatcher.find()) {
            SymbolKind kind = switch (typeMatcher.group(1)) {
                case "class" -> SymbolKind.CLASS;
                case "interface" -> SymbolKind.INTERFACE;
                case "record" -> SymbolKind.RECORD;
                case "enum" -> SymbolKind.ENUM;
                case "@interface" -> SymbolKind.ANNOTATION;
                default -> SymbolKind.CLASS;
            };
            add(hits, new SymbolHit(path, typeMatcher.group(2), kind, lineNumber, lineNumber, signatureLine.strip()));
            return;
        }

        if (looksLikeControlFlow(scanLine)) return;
        var methodMatcher = JAVA_METHOD.matcher(scanLine);
        if (methodMatcher.find()) {
            add(hits, new SymbolHit(path, methodMatcher.group(1), SymbolKind.METHOD, lineNumber, lineNumber, signatureLine.strip()));
        }
    }

    private static void extractJavaScriptLike(String path, String scanLine, String signatureLine, int lineNumber, Map<String, SymbolHit> hits) {
        var classMatcher = JS_CLASS.matcher(scanLine);
        if (classMatcher.find()) {
            add(hits, new SymbolHit(path, classMatcher.group(1), SymbolKind.CLASS, lineNumber, lineNumber, signatureLine.strip()));
        }
        var interfaceMatcher = JS_INTERFACE.matcher(scanLine);
        if (interfaceMatcher.find()) {
            add(hits, new SymbolHit(path, interfaceMatcher.group(1), SymbolKind.INTERFACE, lineNumber, lineNumber, signatureLine.strip()));
        }
        var functionMatcher = JS_FUNCTION.matcher(scanLine);
        if (functionMatcher.find()) {
            add(hits, new SymbolHit(path, functionMatcher.group(1), SymbolKind.FUNCTION, lineNumber, lineNumber, signatureLine.strip()));
        }
        var arrowMatcher = JS_ARROW_FUNCTION.matcher(scanLine);
        if (arrowMatcher.find()) {
            add(hits, new SymbolHit(path, arrowMatcher.group(1), SymbolKind.FUNCTION, lineNumber, lineNumber, signatureLine.strip()));
        }
    }

    private static void extractPython(String path, String scanLine, String signatureLine, int lineNumber, Map<String, SymbolHit> hits) {
        var classMatcher = PY_CLASS.matcher(scanLine);
        if (classMatcher.find()) {
            add(hits, new SymbolHit(path, classMatcher.group(1), SymbolKind.CLASS, lineNumber, lineNumber, signatureLine.strip()));
        }
        var functionMatcher = PY_FUNCTION.matcher(scanLine);
        if (functionMatcher.find()) {
            add(hits, new SymbolHit(path, functionMatcher.group(1), SymbolKind.FUNCTION, lineNumber, lineNumber, signatureLine.strip()));
        }
    }

    private static boolean looksLikeControlFlow(String line) {
        String trimmed = line.stripLeading().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("if ")
                || trimmed.startsWith("if(")
                || trimmed.startsWith("for ")
                || trimmed.startsWith("for(")
                || trimmed.startsWith("while ")
                || trimmed.startsWith("while(")
                || trimmed.startsWith("switch ")
                || trimmed.startsWith("switch(")
                || trimmed.startsWith("catch ")
                || trimmed.startsWith("catch(")
                || trimmed.startsWith("return ")
                || trimmed.startsWith("new ");
    }

    private static void add(Map<String, SymbolHit> hits, SymbolHit hit) {
        if (hit.symbol().isBlank()) return;
        String key = hit.path().toLowerCase(Locale.ROOT)
                + "\u0000" + hit.symbol().toLowerCase(Locale.ROOT)
                + "\u0000" + hit.kind()
                + "\u0000" + hit.lineStart();
        hits.putIfAbsent(key, hit);
    }

    private static CommentStripped stripComments(String line, boolean inBlockComment) {
        boolean block = inBlockComment;
        StringBuilder out = new StringBuilder();
        char quote = 0;
        boolean escaped = false;

        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (block) {
                if (ch == '*' && index + 1 < line.length() && line.charAt(index + 1) == '/') {
                    block = false;
                    index++;
                }
                continue;
            }

            if (quote != 0) {
                out.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }

            if (ch == '"' || ch == '\'' || ch == '`') {
                quote = ch;
                out.append(ch);
                continue;
            }

            if (ch == '/' && index + 1 < line.length()) {
                char next = line.charAt(index + 1);
                if (next == '/') {
                    break;
                }
                if (next == '*') {
                    block = true;
                    index++;
                    continue;
                }
            }

            out.append(ch);
        }

        if (quote != 0 && quote != '`') {
            // Java/Python/JS single-line string literals cannot carry comment state
            // across lines. Template literals are also kept local here; this extractor
            // is line-oriented and intentionally does not attempt full language parsing.
            quote = 0;
        }
        return new CommentStripped(out.toString(), block);
    }

    private static String maskStringLiteralContent(String line) {
        // Line-local by design: multiline template literal state is outside this
        // lightweight regex scanner and remains documented as a T717 limitation.
        StringBuilder out = new StringBuilder(line.length());
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (quote != 0) {
                out.append(ch == quote && !escaped ? ch : ' ');
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                quote = ch;
                out.append(ch);
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private record CommentStripped(String line, boolean inBlockComment) {}
}
