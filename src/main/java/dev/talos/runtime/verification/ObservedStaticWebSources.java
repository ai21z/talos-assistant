package dev.talos.runtime.verification;

import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.Source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.talos.runtime.verification.ReadOnlyWebAnswerAssessment.Reason;
import static dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence.SOURCE_PROJECTION_VERSION;

/** Literal relationships are selected solely from the supplied normalized observations. */
final class ObservedStaticWebSources {
    private static final Pattern BASE = Pattern.compile("(?is)<base\\b");
    private static final Pattern SCRIPT_OPEN = Pattern.compile("(?is)<script\\b");
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script[\\t\\n\\f\\r ]*>");
    private static final Pattern LINK = Pattern.compile("(?is)<link\\b([^>]*)>");
    private static final Pattern UNSUPPORTED_CONTEXT = Pattern.compile(
            "(?is)<(?:template|noscript|svg|math|plaintext)(?=[\\s/>])");
    private static final Pattern UNKNOWN_DECLARATION = Pattern.compile(
            "(?is)<(?:\\?|!(?!--|doctype[\\t\\n\\f\\r ]+html[\\t\\n\\f\\r ]*>))");
    private static final Pattern RELATION_MARKUP = Pattern.compile("(?is)<(?:script|link|base)\\b");
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?(?:-->|$)");
    private static final Pattern RAW_TEXT = Pattern.compile(
            "(?is)<(title|textarea|style|xmp|iframe|noembed|noframes)\\b[^>]*>(.*?)(?:</\\1[\\t\\n\\f\\r ]*>|$)");
    private static final Pattern QUOTED_TAG = Pattern.compile(
            "(?is)<([A-Za-z][A-Za-z0-9:_-]*+)((?:[^<>\"']++|\"[^\"]*+\"|'[^']*+')*+)>");
    private static final Pattern CLOSING_TAG = Pattern.compile("(?is)</[A-Za-z][A-Za-z0-9:_-]*[\\t\\n\\f\\r ]*>");
    private static final Pattern DOCTYPE = Pattern.compile("(?is)<!doctype[\\t\\n\\f\\r ]+html[\\t\\n\\f\\r ]*>");
    private static final Pattern QUOTED_VALUE = Pattern.compile("(?s)(['\"])(.*?)\\1");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\G[\\t\\n\\f\\r ]+([A-Za-z_:][A-Za-z0-9_.:-]*)(?:[\\t\\n\\f\\r ]*=[\\t\\n\\f\\r ]*(?:\"([^\"]*)\"|'([^']*)'))?");
    private static final Pattern MODULE = Pattern.compile(
            "(?m)^\\s*(?:import\\b|export\\b)|\\b(?:import|require)\\s*\\(");
    private static final int MAX_SOURCES = 8;
    private static final int MAX_SOURCE_CHARS = 16_000;
    private static final Set<String> RAW_TEXT_ELEMENTS = Set.of(
            "script", "title", "textarea", "style", "xmp", "iframe", "noembed", "noframes");
    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr");

    private ObservedStaticWebSources() {}

    record Selection(StaticWebSelectorAnalyzer.Facts facts, List<String> readPaths,
                     Reason reason, String detail) {
        Selection {
            readPaths = List.copyOf(readPaths);
        }
        boolean available() { return facts != null; }
    }

    private record HtmlTargets(Set<String> classes, List<String> ids) {}

    static Selection select(List<Source> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > MAX_SOURCES)
            return unavailable(Reason.SOURCE_EVIDENCE_UNAVAILABLE, "No complete source observations are available.");
        Map<String, Source> byPath = new LinkedHashMap<>();
        Set<String> origins = new LinkedHashSet<>();
        int characters = 0;
        for (Source source : sources) {
            if (source == null || source.text() == null || source.text().isEmpty()
                    || source.originId() == null || source.originId().isBlank() || source.ordinal() < 1
                    || source.bodyHash() == null || source.bodyHash().isBlank()
                    || !SOURCE_PROJECTION_VERSION.equals(source.projectionVersion()))
                return unavailable(Reason.SOURCE_EVIDENCE_UNAVAILABLE, "A complete source observation is unavailable.");
            String path = resolve("", source.path());
            if (path == null)
                return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED, "A source path is outside the supported relative-path representation.");
            if (byPath.putIfAbsent(path, source) != null
                    || !origins.add(source.originId() + ':' + source.ordinal()))
                return unavailable(Reason.SOURCE_RELATION_AMBIGUOUS, "Source paths or observation identities are duplicated.");
            characters += source.text().length();
            if (characters > MAX_SOURCE_CHARS)
                return unavailable(Reason.SOURCE_EVIDENCE_UNAVAILABLE, "The source observations exceed the bounded analysis size.");
        }
        List<String> htmlPaths = byPath.keySet().stream().filter(ObservedStaticWebSources::isHtml).toList();
        if (htmlPaths.size() != 1)
            return unavailable(htmlPaths.isEmpty() ? Reason.SOURCE_RELATION_UNSUPPORTED : Reason.SOURCE_RELATION_AMBIGUOUS,
                    "The source relation requires exactly one observed HTML document.");
        String htmlPath = htmlPaths.getFirst();
        String html = byPath.get(htmlPath).text();
        if (unsupportedContext(html))
            return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED,
                    "Inert, foreign or embedded markup prevents an unambiguous source-link interpretation within the supported scope.");
        HtmlTargets targets = htmlTargets(html);
        if (targets == null)
            return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED,
                    "HTML tag or attribute scope cannot be established within the supported lexical representation.");
        if (BASE.matcher(html).find())
            return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED, "HTML base-element resolution is outside the supported source relation.");
        List<String> scripts = new ArrayList<>();
        Matcher script = SCRIPT.matcher(html);
        int scriptCount = 0;
        while (script.find()) {
            scriptCount++;
            Map<String, String> attributes = attributes(script.group(1));
            if (attributes == null)
                return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED, "A script link cannot be represented completely.");
            String type = attributes.get("type");
            String src = attributes.get("src");
            if (src == null || !script.group(2).isBlank()
                    || type != null && !Set.of("text/javascript", "application/javascript", "").contains(type.toLowerCase(Locale.ROOT)))
                return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED, "Inline, module or unrecognized script relationships are outside the supported source relation.");
            String resolved = resolve(parent(htmlPath), src);
            if (resolved == null || !hasExtension(resolved, ".js"))
                return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED, "A linked script uses an unsupported path or source type.");
            scripts.add(resolved);
        }
        if (scriptCount != SCRIPT_OPEN.matcher(html).results().count())
            return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED, "A script link cannot be represented completely.");
        if (scripts.size() != 1)
            return unavailable(scripts.isEmpty() ? Reason.SOURCE_RELATION_UNSUPPORTED : Reason.SOURCE_RELATION_AMBIGUOUS,
                    "The source relation requires exactly one unambiguous linked JavaScript source.");
        String jsPath = scripts.getFirst();
        Source jsSource = byPath.get(jsPath);
        if (jsSource == null)
            return unavailable(Reason.LINKED_SOURCE_UNOBSERVED,
                    "The inspected HTML links `" + jsPath + "`, which is not among the available source observations. Its existence on disk was not checked.");
        if (MODULE.matcher(jsSource.text()).find())
            return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED, "Module dependencies are outside the supported source relation.");

        List<String> styles = new ArrayList<>();
        Matcher link = LINK.matcher(html);
        while (link.find()) {
            Map<String, String> attributes = attributes(stripHtmlWhitespace(link.group(1)).replaceFirst("/$", ""));
            if (attributes == null)
                return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED, "An HTML link relationship cannot be represented completely.");
            String rel = attributes.get("rel");
            String href = attributes.get("href");
            if (rel == null || !List.of(rel.toLowerCase(Locale.ROOT).split("\\s+")).contains("stylesheet")) continue;
            String resolved = resolve(parent(htmlPath), href);
            if (resolved == null || !hasExtension(resolved, ".css"))
                return unavailable(Reason.SOURCE_RELATION_UNSUPPORTED, "A linked stylesheet uses an unsupported path or source type.");
            styles.add(resolved);
        }
        if (styles.size() > 1)
            return unavailable(Reason.SOURCE_RELATION_AMBIGUOUS, "Multiple linked stylesheets are outside the supported source relation.");
        String cssPath = styles.isEmpty() || !byPath.containsKey(styles.getFirst()) ? "" : styles.getFirst();
        String css = cssPath.isEmpty() ? "" : byPath.get(cssPath).text();
        return new Selection(StaticWebSelectorAnalyzer.analyzeObserved(
                htmlPath, html, targets.classes(), targets.ids(), jsPath, jsSource.text(), cssPath, css, styles, scripts),
                List.copyOf(byPath.keySet()), null, "");
    }

    private static Selection unavailable(Reason reason, String detail) {
        return new Selection(null, List.of(), reason, detail);
    }

    private static boolean unsupportedContext(String html) {
        if (UNSUPPORTED_CONTEXT.matcher(html).find() || UNKNOWN_DECLARATION.matcher(html).find()) return true;
        Matcher comments = HTML_COMMENT.matcher(html);
        while (comments.find()) {
            if (RELATION_MARKUP.matcher(comments.group()).find()) return true;
        }
        Matcher rawText = RAW_TEXT.matcher(html);
        while (rawText.find()) {
            if (RELATION_MARKUP.matcher(rawText.group(2)).find()) return true;
        }
        Matcher tags = QUOTED_TAG.matcher(html);
        while (tags.find()) {
            Matcher values = QUOTED_VALUE.matcher(tags.group());
            while (values.find()) {
                if (RELATION_MARKUP.matcher(values.group(2)).find()) return true;
            }
        }
        return false;
    }

    private static HtmlTargets htmlTargets(String html) {
        Set<String> classes = new LinkedHashSet<>();
        List<String> ids = new ArrayList<>();
        int cursor = 0;
        while ((cursor = html.indexOf('<', cursor)) >= 0) {
            if (html.startsWith("<!--", cursor)) {
                int end = html.indexOf("-->", cursor + 4);
                if (end < 0) return null;
                String comment = html.substring(cursor + 4, end);
                if (comment.startsWith(">") || comment.startsWith("->") || comment.contains("--")) return null;
                cursor = end + 3;
                continue;
            }
            Matcher declaration = DOCTYPE.matcher(html).region(cursor, html.length());
            if (declaration.lookingAt()) {
                cursor = declaration.end();
                continue;
            }
            Matcher closing = CLOSING_TAG.matcher(html).region(cursor, html.length());
            if (closing.lookingAt()) {
                cursor = closing.end();
                continue;
            }
            Matcher opening = QUOTED_TAG.matcher(html).region(cursor, html.length());
            if (!opening.lookingAt()) return null;
            String tag = opening.group(1).toLowerCase(Locale.ROOT);
            String attributeText = stripHtmlWhitespace(opening.group(2));
            if (attributeText.endsWith("/")) {
                if (!VOID_ELEMENTS.contains(tag)) return null;
                attributeText = attributeText.substring(0, attributeText.length() - 1);
            }
            Map<String, String> attributes = attributes(attributeText);
            if (attributes == null) return null;
            String id = attributes.get("id");
            String classNames = attributes.get("class");
            if (unsupportedTargetValue(id) || unsupportedTargetValue(classNames)) return null;
            if (id != null && !id.isEmpty()) ids.add(id);
            if (classNames != null) {
                for (String className : classNames.split("[\\t\\n\\f\\r ]+")) {
                    if (!className.isEmpty()) classes.add(className);
                }
            }
            cursor = opening.end();
            if (RAW_TEXT_ELEMENTS.contains(tag)) {
                Matcher end = Pattern.compile("(?is)</" + tag + "[\\t\\n\\f\\r ]*>").matcher(html);
                if (!end.find(cursor)) return null;
                cursor = end.end();
            }
        }
        return new HtmlTargets(classes, ids);
    }

    private static boolean unsupportedTargetValue(String value) {
        return value != null && (value.indexOf('&') >= 0 || value.indexOf('\0') >= 0);
    }

    private static Map<String, String> attributes(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(text);
        int end = 0;
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : "";
            if (out.putIfAbsent(name, value) != null) return null;
            end = matcher.end();
        }
        return text.substring(end).chars().allMatch(ObservedStaticWebSources::htmlWhitespace) ? out : null;
    }

    private static String stripHtmlWhitespace(String text) {
        int end = text.length();
        while (end > 0 && htmlWhitespace(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static boolean htmlWhitespace(int character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\f' || character == '\r';
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    static String resolve(String parent, String reference) {
        if (reference == null || reference.isBlank() || !reference.equals(reference.strip())) return null;
        String value = reference.replace('\\', '/');
        if (value.startsWith("/") || value.endsWith("/") || value.contains(":")
                || value.contains("?") || value.contains("#") || value.contains("%")
                || value.contains("&") || value.contains("`") || value.contains("<") || value.contains(">")
                || value.chars().anyMatch(character -> character < 32 || character == 127)) return null;
        List<String> components = new ArrayList<>();
        if (!parent.isEmpty()) components.addAll(List.of(parent.split("/")));
        for (String component : value.split("/", -1)) {
            if (component.isEmpty()) return null;
            if (component.equals(".")) continue;
            if (component.equals("..")) {
                if (components.isEmpty()) return null;
                components.removeLast();
            } else {
                components.add(component);
            }
        }
        return components.isEmpty() ? null : String.join("/", components);
    }

    private static boolean isHtml(String path) {
        return hasExtension(path, ".html") || hasExtension(path, ".htm");
    }

    private static boolean hasExtension(String path, String extension) {
        return path.toLowerCase(Locale.ROOT).endsWith(extension);
    }
}
