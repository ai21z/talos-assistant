package dev.talos.runtime.workspace;

import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.intent.ContinuationDirective;
import dev.talos.runtime.intent.TargetRef;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds the operand of a removal request to one workspace path, or asks (MICA invariant I2, decision D3).
 *
 * <p>A removal request is preserved as a removal. Its operand is a runtime fact: a quoted or path-shaped name, an
 * existing workspace entry, or the single file a kind noun such as "the page" refers to. A bare anaphor such as "it"
 * cannot tell a file from its content, so it asks. A bare word is never invented as an operand, a bulk phrase is never
 * bound, and content removal from a named file stays a content edit of that file.
 *
 * <p>The preflight never authorizes. A binding becomes the canonical request {@code Delete "<path>".}, which the
 * parser resolves exactly like a typed request, and the destructive approval prompt shows the resolved path. Content
 * removal becomes {@code edit <path> to remove <object>} in place, with the rest of the message kept verbatim. A
 * recognized but unresolved operand ends the turn with an answer, no tool and no change. Any other shape, which may be
 * a mention or a pasted command, is left to the parser unchanged.
 */
public final class OperationOperandPreflight {
    static final int MAX_LISTED = 12;
    static final int MAX_WALKED_FILES = 2_000;
    static final int MAX_DEPTH = 8;

    public enum OperandClass { EXPLICIT_PATH, BARE_WORD, ANAPHOR, KIND_NOUN, BULK, CONTENT_REMOVAL, UNRESOLVED }

    public enum Outcome { NOT_APPLICABLE, BOUND, CONTENT_EDIT, CLARIFIED, NOT_FOUND, BULK_REFUSED, CONSTRAINED }

    /** A direct answer, an effective request, or neither. Never both. */
    public record Result(
            Outcome outcome,
            OperandClass operandClass,
            String directAnswer,
            String effectiveRequest,
            String boundPath,
            int candidateCount) {
        static Result none() {
            return new Result(Outcome.NOT_APPLICABLE, OperandClass.UNRESOLVED, null, null, "", 0);
        }

        public boolean applies() {
            return outcome != Outcome.NOT_APPLICABLE;
        }
    }

    private static final Pattern REMOVAL_VERB = Pattern.compile("^(?:get rid of|delete|remove|erase|rm)\\b");
    private static final Pattern CONSTRAINT = Pattern.compile(
            "\\b(?:keep|except|unless|without|only|backup|back up|save|not|never|but|instead)\\b|n't\\b");
    private static final Pattern TRAILING_FILLER = Pattern.compile(
            "(?:\\s+(?:now|please|pls|for me|right now|too|as well|again|already|completely|permanently|"
                    + "entirely|thanks|thank you|asap))+$");
    private static final Pattern TRAILING_WORKSPACE_LOCATION = Pattern.compile(
            "\\s+(?:from|in)\\s+(?:the|this|my|our)\\s+(?:workspace|project|folder|directory|repo|repository)$");
    private static final Pattern LEADING_DETERMINER = Pattern.compile(
            "^(?:(?:the|this|that|my|our|your|a|an)\\s+)+");
    private static final Set<String> ANAPHORS = Set.of(
            "it", "them", "this", "that", "this one", "that one");
    private static final Pattern BULK = Pattern.compile(
            "^(?:everything|all|all of it|all of them|every file|every single file|all files|all the files|"
                    + "all of the files|the files|files|whole workspace|entire workspace|workspace|whole project|"
                    + "entire project|project|site|website|web site|whole site|entire site|whole thing)"
                    + "(?:\\s+(?:in|from|of)\\s+(?:the|this|my|our)\\s+(?:workspace|project|folder|directory))?$"
                    + "|^everything\\b|^all\\s+(?:the\\s+)?(?:files|pages|content)\\b");
    private static final Pattern QUOTED = Pattern.compile("^([\"'`])(.+)\\1$");
    private static final Pattern LOCATION = Pattern.compile("\\s+(?:from|in)\\s+");
    // An object that starts with a preposition or carries a verb of state is a sentence, not removable content.
    private static final Pattern NOT_CONTENT_OBJECT = Pattern.compile(
            "^(?:from|in|to|on|at|of|for|with|by)\\b|\\b(?:is|are|was|were|am|be|been|does|do|did|doesn't|don't|"
                    + "didn't|isn't|aren't|wasn't|has|have|had|seems?|gives?)\\b");
    private static final Pattern LIST_CONTINUATION = Pattern.compile(
            "\\s*(?:,|&|\\band\\b)\\s*(?:and\\s+)?(?:[\"'`][^\"'`]+[\"'`]|[^\\s,]*[./\\\\][^\\s,]*)");
    private static final Pattern FILE_WRAPPER = Pattern.compile(
            "^(?:(?:the|this|that|my)\\s+)?(?:file|folder|directory|dir)\\s+(\\S+)$"
                    + "|^(?:(?:the|this|that|my)\\s+)?(\\S+)\\s+(?:file|folder|directory|dir)$");
    private static final Set<String> NO_OPERAND = Set.of("nothing", "anything");
    private static final Set<String> TYPED_VERBS = Set.of("delete", "remove");

    private OperationOperandPreflight() {}

    /**
     * Evaluates the current message. {@code context} supplies a referent only. Its authority is never consumed here.
     */
    public static Result evaluate(
            String message,
            Path workspace,
            ActiveTaskContext context,
            int currentUserTurnNumber
    ) {
        if (message == null || message.isBlank() || workspace == null || !Files.isDirectory(workspace)) {
            return Result.none();
        }
        Clause parsed = Clause.of(message);
        if (parsed == null) return Result.none();
        Span span = parsed.span();
        String spanText = parsed.spanText();
        // "delete" and "remove" with the exact spelling of an existing path is already a typed request for the
        // parser, which owns its authority. Such a request is left unchanged.
        boolean typedVerb = parsed.typedVerb();
        boolean recursive = parsed.recursive();
        String phrase = parsed.phrase();

        WorkspaceListing listing = WorkspaceListing.of(workspace);
        Result content = contentRemoval(
                message, span, spanText, phrase, workspace, listing, context, currentUserTurnNumber);
        if (content != null) return content;

        phrase = operandPhrase(phrase);
        String constraintScope = normalized(message.substring(0, span.start()) + " " + message.substring(span.end()));
        boolean constrained = CONSTRAINT.matcher(constraintScope).find();

        Matcher quoted = QUOTED.matcher(phrase);
        if (quoted.matches() || pathShaped(phrase)) {
            String original = originalSpelling(spanText, quoted.matches() ? quoted.group(2) : phrase);
            if (typedVerb && !constrained && exists(workspace, original)) return Result.none();
            return bindExisting(OperandClass.EXPLICIT_PATH, original, phrase, workspace, recursive, constrained);
        }
        if (phrase.isEmpty()) {
            return answer(Outcome.CLARIFIED, OperandClass.UNRESOLVED, 0,
                    "Talos did not delete anything. The request names no path. Name the path to delete, "
                            + "for example: " + example(listing.files()));
        }
        String noun = LEADING_DETERMINER.matcher(phrase).replaceFirst("").strip();
        if (NO_OPERAND.contains(noun)) return Result.none();
        if (ANAPHORS.contains(phrase)) {
            return anaphor(phrase, contextReferents(context, currentUserTurnNumber, workspace, null), listing);
        }
        if (BULK.matcher(phrase).find() || BULK.matcher(noun).find()) {
            return bulk(phrase, listing);
        }
        Kind kind = Kind.of(noun);
        if (kind != null) {
            List<String> referents = contextReferents(context, currentUserTurnNumber, workspace, kind);
            if (referents.size() != 1) referents = listing.filesOf(kind);
            return choose(OperandClass.KIND_NOUN, phrase, referents, listing, recursive, constrained);
        }
        if (noun.matches("[\\p{L}\\p{N}_-]+")) {
            String entry = listing.rootEntryNamed(noun);
            if (entry == null) {
                return answer(Outcome.NOT_FOUND, OperandClass.BARE_WORD, 0,
                        "Talos did not delete anything. " + noun + " does not exist in the workspace. "
                                + "To remove content from a file, name the file, for example: remove the "
                                + noun + " from " + (listing.files().isEmpty() ? "index.html"
                                : listing.files().getFirst()));
            }
            if (typedVerb && !constrained && entry.equals(originalSpelling(spanText, noun))) return Result.none();
            return bound(OperandClass.BARE_WORD, phrase, entry, recursive, constrained, 1);
        }
        // Any other shape may be a mention, a pasted command or a complaint. It is left to the parser, whose
        // typed delete of a missing target is rejected before approval.
        return Result.none();
    }

    /**
     * Content removal names a file location: "remove the footer from index.html". The location decides, whatever
     * the object looks like. Returns null when the phrase has no file location, and {@link Result#none()} when the
     * request is left whole to the parser.
     */
    private static Result contentRemoval(
            String message,
            Span span,
            String spanText,
            String phrase,
            Path workspace,
            WorkspaceListing listing,
            ActiveTaskContext context,
            int turn
    ) {
        Matcher location = LOCATION.matcher(phrase);
        if (!location.find()) return null;
        int objectEnd = location.start();
        int placeStart = location.end();
        String object = phrase.substring(0, objectEnd).strip();
        String place = LEADING_DETERMINER.matcher(phrase.substring(placeStart).strip()).replaceFirst("");
        Matcher quoted = QUOTED.matcher(place);
        boolean namedPlace = quoted.matches() || pathShaped(place);
        Kind kind = namedPlace ? null : Kind.of(place);
        if (!namedPlace && kind == null) return null;
        // The place runs to the end of the phrase, so a second location, as in a removal from two files, never
        // resolves to one file. An empty object or an object that reads as a sentence is not one content edit.
        if (object.isEmpty() || NOT_CONTENT_OBJECT.matcher(object).find()) return Result.none();
        String originalObject = originalSpelling(spanText, object);
        String file;
        if (namedPlace) {
            String original = originalSpelling(spanText, quoted.matches() ? quoted.group(2) : place);
            if (isRegularFile(workspace, original)) {
                file = original;
            } else if (exists(workspace, original)) {
                return Result.none();
            } else {
                return answer(Outcome.NOT_FOUND, OperandClass.CONTENT_REMOVAL, 0,
                        "Talos changed nothing. " + original + " does not exist in the workspace.");
            }
        } else {
            List<String> referents = contextReferents(context, turn, workspace, kind);
            if (referents.size() != 1) referents = listing.filesOf(kind);
            if (referents.isEmpty()) {
                return answer(Outcome.NOT_FOUND, OperandClass.CONTENT_REMOVAL, 0,
                        "Talos changed nothing. The workspace has no " + place + ". Name the file to change.");
            }
            if (referents.size() > 1) {
                return answer(Outcome.CLARIFIED, OperandClass.CONTENT_REMOVAL, referents.size(),
                        "Talos changed nothing. \"" + place + "\" could mean " + joined(referents)
                                + ". Name the file, for example: remove " + originalObject + " from "
                                + referents.getFirst());
            }
            file = referents.getFirst();
        }
        return new Result(Outcome.CONTENT_EDIT, OperandClass.CONTENT_REMOVAL, null,
                contentEditRequest(message, span, originalObject, file), file, 1);
    }

    private static String contentEditRequest(String message, Span span, String originalObject, String file) {
        return message.substring(0, span.start())
                + (sentenceStart(message, span.start()) ? "Edit " : "edit ") + quote(file)
                + " to remove " + originalObject
                + message.substring(span.end());
    }

    private static String deleteRequest(String path, boolean recursive) {
        return "Delete " + quote(path) + (recursive ? " recursively." : ".");
    }

    /** A bare anaphor cannot tell a file from its content, so it is never bound. It asks with the candidates. */
    private static Result anaphor(String phrase, List<String> referents, WorkspaceListing listing) {
        if (referents.isEmpty()) {
            return answer(Outcome.CLARIFIED, OperandClass.ANAPHOR, 0,
                    "Talos did not delete anything. It is not clear which path \"" + phrase
                            + "\" refers to. Name the path to delete, for example: " + example(listing.files()));
        }
        String meaning = referents.size() == 1
                ? "the file " + referents.getFirst() + " or content in it"
                : joined(referents);
        return answer(Outcome.CLARIFIED, OperandClass.ANAPHOR, referents.size(),
                "Talos did not delete anything. \"" + phrase + "\" could mean " + meaning
                        + ". To delete a file, send: " + example(referents)
                        + ". To remove content, name it and the file, for example: remove the footer from "
                        + referents.getFirst() + ".");
    }

    private static Result bindExisting(
            OperandClass operandClass,
            String path,
            String phrase,
            Path workspace,
            boolean recursive,
            boolean constrained
    ) {
        if (!exists(workspace, path)) return notFound(operandClass, path);
        return bound(operandClass, phrase, path, recursive, constrained, 1);
    }

    private static Result choose(
            OperandClass operandClass,
            String phrase,
            List<String> referents,
            WorkspaceListing listing,
            boolean recursive,
            boolean constrained
    ) {
        if (referents.size() == 1) {
            return bound(operandClass, phrase, referents.getFirst(), recursive, constrained, 1);
        }
        String shown = phrase.isEmpty() ? "it" : phrase;
        if (referents.isEmpty()) {
            return answer(Outcome.CLARIFIED, operandClass, 0,
                    "Talos did not delete anything. It is not clear which path \"" + shown
                            + "\" refers to. Name the path to delete, for example: " + example(listing.files()));
        }
        return answer(Outcome.CLARIFIED, operandClass, referents.size(),
                "Talos did not delete anything. \"" + shown + "\" could mean " + joined(referents)
                        + ". Name the path to delete, for example: " + example(referents));
    }

    private static Result bound(
            OperandClass operandClass,
            String phrase,
            String path,
            boolean recursive,
            boolean constrained,
            int candidates
    ) {
        if (constrained) {
            return new Result(Outcome.CONSTRAINED, operandClass,
                    "Talos did not delete anything. \"" + phrase + "\" refers to " + path
                            + ", and the request also sets a condition that a delete cannot carry. "
                            + "To delete it, send: " + example(List.of(path)),
                    null, path, candidates);
        }
        return new Result(Outcome.BOUND, operandClass, null, deleteRequest(path, recursive), path, candidates);
    }

    private static Result bulk(String phrase, WorkspaceListing listing) {
        String contents = listing.files().isEmpty()
                ? "The workspace has no files."
                : "The workspace contains " + joined(listing.files()) + ".";
        return answer(Outcome.BULK_REFUSED, OperandClass.BULK, listing.files().size(),
                "Talos did not delete anything. Talos deletes one named path per request, and \"" + phrase
                        + "\" does not name one path. " + contents
                        + (listing.files().isEmpty() ? "" : " Name the path to delete, for example: "
                        + example(listing.files())));
    }

    private static Result notFound(OperandClass operandClass, String path) {
        return answer(Outcome.NOT_FOUND, operandClass, 0,
                "Talos did not delete anything. " + path + " does not exist in the workspace.");
    }

    private static Result answer(Outcome outcome, OperandClass operandClass, int candidates, String text) {
        return new Result(outcome, operandClass, text, null, "", candidates);
    }

    private static List<String> contextReferents(ActiveTaskContext context, int turn, Path workspace, Kind kind) {
        if (context == null || context.targets() == null || context.targets().isEmpty()) return List.of();
        if (!context.activeAt(turn)) return List.of();
        List<String> out = new ArrayList<>();
        for (String target : context.targets()) {
            if (target == null || target.isBlank()) continue;
            if (kind != null && !kind.matches(target)) continue;
            if (isRegularFile(workspace, target)) out.add(target);
        }
        return List.copyOf(out);
    }

    /**
     * The requests this preflight composes for {@code typedRequest} when its operand is bound to {@code path}: a
     * delete, a recursive delete or an in-place content edit. They are reproduced from the typed text and the path
     * alone, with the same functions the binding uses, so a durable turn record can be checked against them without
     * the workspace. Empty when the typed request is not a single removal whose operand could bind to that path.
     */
    public static List<String> compositions(String typedRequest, String path) {
        if (typedRequest == null || typedRequest.isBlank() || path == null || path.isBlank()) return List.of();
        Clause parsed = Clause.of(typedRequest);
        if (parsed == null) return List.of();
        String message = typedRequest;
        String phrase = parsed.phrase();
        Matcher location = LOCATION.matcher(phrase);
        if (location.find()) {
            String object = phrase.substring(0, location.start()).strip();
            String place = LEADING_DETERMINER.matcher(phrase.substring(location.end()).strip()).replaceFirst("");
            Matcher quoted = QUOTED.matcher(place);
            boolean namedPlace = quoted.matches() || pathShaped(place);
            Kind kind = namedPlace ? null : Kind.of(place);
            if (namedPlace || kind != null) {
                if (object.isEmpty() || NOT_CONTENT_OBJECT.matcher(object).find()) return List.of();
                // A named place is composed with its typed spelling, as the binding composes it.
                String file = namedPlace
                        ? originalSpelling(parsed.spanText(), quoted.matches() ? quoted.group(2) : place)
                        : path;
                boolean bindsPath = namedPlace ? sameTarget(file, path) : kind.matches(path);
                return bindsPath
                        ? List.of(contentEditRequest(message, parsed.span(),
                                originalSpelling(parsed.spanText(), object), file))
                        : List.of();
            }
        }
        String operand = operandPhrase(phrase);
        String constraintScope = normalized(message.substring(0, parsed.span().start()) + " "
                + message.substring(parsed.span().end()));
        if (operand.isEmpty() || CONSTRAINT.matcher(constraintScope).find()) return List.of();
        Matcher quoted = QUOTED.matcher(operand);
        boolean bindsPath;
        String target = path;
        if (quoted.matches() || pathShaped(operand)) {
            target = originalSpelling(parsed.spanText(), quoted.matches() ? quoted.group(2) : operand);
            bindsPath = sameTarget(target, path);
        } else {
            String noun = LEADING_DETERMINER.matcher(operand).replaceFirst("").strip();
            Kind kind = Kind.of(noun);
            if (NO_OPERAND.contains(noun) || ANAPHORS.contains(operand)
                    || BULK.matcher(operand).find() || BULK.matcher(noun).find()) {
                bindsPath = false;
            } else if (kind != null) {
                bindsPath = kind.matches(path);
            } else {
                bindsPath = noun.matches("[\\p{L}\\p{N}_-]+") && path.equalsIgnoreCase(noun);
            }
        }
        return bindsPath ? List.of(deleteRequest(target, parsed.recursive())) : List.of();
    }

    /** A typed spelling names an evidence path when both normalize to one target path, as durable evidence does. */
    private static boolean sameTarget(String typed, String path) {
        return withoutTrailingSlash(TargetRef.normalizePath(typed))
                .equals(withoutTrailingSlash(TargetRef.normalizePath(path)));
    }

    private static String withoutTrailingSlash(String path) {
        String out = path;
        while (out.length() > 1 && out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /** A wrapper such as "the file x" names x. A trailing "from this workspace" names no further place. */
    private static String operandPhrase(String phrase) {
        String out = TRAILING_WORKSPACE_LOCATION.matcher(phrase).replaceFirst("").strip();
        Matcher wrapped = FILE_WRAPPER.matcher(out);
        if (wrapped.matches()) {
            String named = wrapped.group(1) != null ? wrapped.group(1) : wrapped.group(2);
            if (QUOTED.matcher(named).matches() || pathShaped(named)) out = named;
        }
        return out;
    }

    /**
     * The single removal clause of a message, read from the text alone. Null when the message has no removal clause,
     * more than one, a directive clause, a clause that occurs twice, or a list of further paths after it.
     */
    private record Clause(Span span, String spanText, boolean typedVerb, boolean recursive, String phrase) {
        static Clause of(String message) {
            List<String> removal = ContinuationDirective.removalClauses(message);
            if (removal.size() != 1 || ContinuationDirective.present(message)) return null;
            String clause = removal.getFirst();
            // The clause must occur once, so its spelling and its surroundings are read from the right place.
            Span span = clauseSpan(message, clause);
            if (span == null) return null;
            // A removal followed by a list of further paths is several operations, left whole to the parser.
            if (LIST_CONTINUATION.matcher(message.substring(span.end())).lookingAt()) return null;
            Matcher verb = REMOVAL_VERB.matcher(clause);
            if (!verb.lookingAt()) return null;
            String rawPhrase = clause.substring(verb.end()).strip();
            boolean recursive = rawPhrase.matches(".*\\brecursively\\b.*");
            String phrase = stripTrailing(rawPhrase.replaceAll("\\s+recursively\\b", "").strip());
            return new Clause(span, message.substring(span.start(), span.end()),
                    TYPED_VERBS.contains(verb.group()), recursive, phrase);
        }
    }

    private static String stripTrailing(String phrase) {
        String out = phrase.replaceAll("[.!?,;:]+$", "").strip();
        out = TRAILING_FILLER.matcher(out).replaceFirst("").strip();
        return out.replaceAll("[.!?,;:]+$", "").strip();
    }

    private static String normalized(String message) {
        return message.strip().toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ");
    }

    /**
     * The span of the clause in the original message, matched case-insensitively with flexible whitespace. Null when
     * the clause is absent or occurs more than once, because the right occurrence is then unknown.
     */
    private static Span clauseSpan(String message, String clause) {
        String[] words = clause.strip().split(" ");
        StringBuilder regex = new StringBuilder("(?iu)");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) regex.append("\\s+");
            String[] parts = words[i].split("'", -1);
            for (int j = 0; j < parts.length; j++) {
                if (j > 0) regex.append("['’]");
                if (!parts[j].isEmpty()) regex.append(Pattern.quote(parts[j]));
            }
        }
        Matcher matcher = Pattern.compile(regex.toString()).matcher(message);
        if (!matcher.find()) return null;
        Span span = new Span(matcher.start(), matcher.end());
        return matcher.find() ? null : span;
    }

    private static boolean sentenceStart(String message, int start) {
        String before = message.substring(0, start).strip();
        return before.isEmpty() || ".!?".indexOf(before.charAt(before.length() - 1)) >= 0;
    }

    private static String originalSpelling(String message, String lowered) {
        Matcher matcher = Pattern.compile("(?iu)" + Pattern.quote(lowered)).matcher(message);
        return matcher.find() ? matcher.group() : lowered;
    }

    private static boolean pathShaped(String phrase) {
        return phrase.length() > 1
                && phrase.indexOf(' ') < 0
                && !phrase.matches("\\.+")
                && (phrase.indexOf('.') >= 0 || phrase.indexOf('/') >= 0 || phrase.indexOf('\\') >= 0);
    }

    private static String quote(String path) {
        if (path.indexOf('"') < 0) return "\"" + path + "\"";
        return "`" + path + "`";
    }

    private static String example(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "delete notes.txt";
        String path = paths.getFirst();
        return "delete " + (path.indexOf(' ') >= 0 ? quote(path) : path);
    }

    private static String joined(List<String> paths) {
        List<String> shown = paths.size() > MAX_LISTED ? paths.subList(0, MAX_LISTED) : paths;
        String text;
        if (shown.size() == 1) {
            text = shown.getFirst();
        } else {
            text = String.join(", ", shown.subList(0, shown.size() - 1)) + " or " + shown.getLast();
        }
        return paths.size() > MAX_LISTED ? text + " and " + (paths.size() - MAX_LISTED) + " more" : text;
    }

    private static boolean exists(Path workspace, String relative) {
        Path resolved = resolve(workspace, relative);
        return resolved != null && Files.exists(resolved, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isRegularFile(Path workspace, String relative) {
        Path resolved = resolve(workspace, relative);
        return resolved != null && Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS);
    }

    private static Path resolve(Path workspace, String relative) {
        if (relative == null || relative.isBlank()) return null;
        try {
            Path root = workspace.toAbsolutePath().normalize();
            Path resolved = root.resolve(relative.replace('\\', '/')).normalize();
            return resolved.startsWith(root) && !resolved.equals(root) ? resolved : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private record Span(int start, int end) {}

    /** File kinds a noun can name. A kind binds only when exactly one file of it is found. */
    enum Kind {
        PAGE(Set.of("page", "web page", "webpage", "html", "html file", "html page"),
                Set.of(".html", ".htm")),
        STYLESHEET(Set.of("stylesheet", "style sheet", "styles", "css", "css file", "style file"),
                Set.of(".css")),
        SCRIPT(Set.of("script", "js", "js file", "javascript", "javascript file", "script file"),
                Set.of(".js", ".mjs")),
        FILE(Set.of("file", "one file"), Set.of());

        private final Set<String> nouns;
        private final Set<String> extensions;

        Kind(Set<String> nouns, Set<String> extensions) {
            this.nouns = nouns;
            this.extensions = extensions;
        }

        static Kind of(String noun) {
            for (Kind kind : values()) {
                if (kind.nouns.contains(noun)) return kind;
            }
            return null;
        }

        boolean matches(String path) {
            if (extensions.isEmpty()) return true;
            String lower = path.toLowerCase(Locale.ROOT);
            for (String extension : extensions) {
                if (lower.endsWith(extension)) return true;
            }
            return false;
        }
    }

    /** Regular workspace files outside hidden folders, and the names at the workspace root. */
    record WorkspaceListing(List<String> files, List<String> rootEntries) {
        static WorkspaceListing of(Path workspace) {
            Path root = workspace.toAbsolutePath().normalize();
            List<String> files = new ArrayList<>();
            Set<String> rootEntries = new LinkedHashSet<>();
            try (var children = Files.list(root)) {
                children.forEach(child -> rootEntries.add(child.getFileName().toString()));
            } catch (IOException | RuntimeException ignored) {
                // An unreadable root lists nothing, so nothing can be bound.
            }
            try {
                Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(root) && hidden(dir)) return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && !hidden(file)) {
                            files.add(root.relativize(file).toString().replace('\\', '/'));
                        }
                        return files.size() >= MAX_WALKED_FILES
                                ? FileVisitResult.TERMINATE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // A partial walk keeps what was listed. Ambiguity then asks rather than binds.
            }
            files.sort(String::compareTo);
            return new WorkspaceListing(List.copyOf(files), List.copyOf(rootEntries));
        }

        private static boolean hidden(Path path) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            return name.startsWith(".") || name.equals("node_modules");
        }

        List<String> filesOf(Kind kind) {
            return files.stream().filter(kind::matches).toList();
        }

        String rootEntryNamed(String name) {
            for (String entry : rootEntries) {
                if (entry.equalsIgnoreCase(name) && !entry.startsWith(".")) return entry;
            }
            return null;
        }
    }
}
