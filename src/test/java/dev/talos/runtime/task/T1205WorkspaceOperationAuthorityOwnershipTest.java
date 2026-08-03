package dev.talos.runtime.task;

import dev.talos.runtime.intent.WorkspaceOperationObligation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-ownership ratchets for T1205 workspace-operation authority. */
class T1205WorkspaceOperationAuthorityOwnershipTest {

    private static final Path MAIN_SOURCE = Path.of("src/main/java");
    private static final Pattern RESOLVE_FROM_USER_REQUEST =
            Pattern.compile("\\bresolveFromUserRequest\\s*\\(");
    private static final Pattern INTENT_ONLY_RESOLUTION = Pattern.compile(
            "TaskContractResolver\\s*\\.\\s*resolveFromUserRequest\\s*\\("
                    + "[^;]*?\\)\\s*\\.\\s*intent\\s*\\(\\s*\\)",
            Pattern.DOTALL);
    private static final Pattern STATIC_VERIFIER_CONTRACT_ONLY_RESOLUTION =
            Pattern.compile(
                    "TaskResolution\\s+resolution\\s*=\\s*"
                            + "TaskContractResolver\\s*\\.\\s*"
                            + "resolveFromUserRequest\\s*\\([^;]*?\\)\\s*;",
                    Pattern.DOTALL);

    @Test
    void natural_language_resolution_is_confined_to_compiler_and_non_authority_compatibility_edges()
            throws Exception {
        Map<String, String> sources = productionSources();
        Map<String, Integer> actualOwners = occurrenceOwners(
                sources,
                RESOLVE_FROM_USER_REQUEST);

        Map<String, Integer> approvedOwners = Map.of(
                "dev/talos/runtime/task/TaskContractResolver.java", 4,
                "dev/talos/runtime/JsonSessionStore.java", 2,
                "dev/talos/runtime/TurnProcessor.java", 1,
                "dev/talos/runtime/TurnPolicyTrace.java", 1,
                "dev/talos/runtime/toolcall/StaticWebContinuationPlanner.java", 1,
                "dev/talos/runtime/verification/StaticTaskVerifier.java", 1);
        assertEquals(
                new TreeMap<>(approvedOwners),
                actualOwners,
                "Only the canonical task compiler and the enumerated lexical/contract/intent "
                        + "compatibility edges may parse a user request. A downstream reparse "
                        + "must never derive workspace-operation authority.");

        for (String intentOnlyOwner : List.of(
                "dev/talos/runtime/TurnProcessor.java",
                "dev/talos/runtime/TurnPolicyTrace.java",
                "dev/talos/runtime/toolcall/StaticWebContinuationPlanner.java")) {
            assertEquals(
                    1,
                    matcherCount(
                            INTENT_ONLY_RESOLUTION,
                            codeOnly(sources.get(intentOnlyOwner))),
                    intentOnlyOwner
                            + " may retain only its single intent-only compatibility fallback; "
                            + "it must not derive workspace-operation authority.");
        }

        String verifier = codeOnly(sources.get(
                "dev/talos/runtime/verification/StaticTaskVerifier.java"));
        assertEquals(
                1,
                matcherCount(STATIC_VERIFIER_CONTRACT_ONLY_RESOLUTION, verifier),
                "The raw verifier compatibility entry must resolve only the task contract.");
        assertTrue(
                verifier.contains("noWorkspaceAuthority(resolution.contract())"),
                "The raw verifier compatibility entry must explicitly supply no workspace "
                        + "operation authority.");
        assertFalse(
                Pattern.compile("\\bresolution\\s*\\.\\s*workspaceOperationPlan\\s*\\(")
                        .matcher(verifier)
                        .find(),
                "The raw verifier compatibility resolution must never expose a parsed "
                        + "workspace-operation plan.");

        String sessionStore = codeOnly(sources.get(
                "dev/talos/runtime/JsonSessionStore.java"));
        assertFalse(
                Pattern.compile(
                                "TaskContractResolver\\s*\\.\\s*resolveFromUserRequest"
                                        + "\\s*\\([^;]*?\\)\\s*\\.\\s*"
                                        + "workspaceOperationPlan\\s*\\(",
                                Pattern.DOTALL)
                        .matcher(sessionStore)
                        .find(),
                "Durable lexical-manifest validation must not directly derive a workspace "
                        + "operation plan from reparsed prose.");
        Pattern durableSemanticJoin = Pattern.compile(
                "!\\s*observed\\s*\\.\\s*matches\\s*\\(\\s*"
                        + "authoritative\\s*\\.\\s*workspaceOperationPlan"
                        + "\\s*\\(\\s*\\)\\s*\\)");
        assertEquals(
                1,
                matcherCount(durableSemanticJoin, sessionStore),
                "The session store may compare reparsed workspace semantics only at "
                        + "the one fail-closed durable evidence join.");
        assertEquals(
                1,
                matcherCount(
                        Pattern.compile(
                                "\\bauthoritative\\s*\\.\\s*"
                                        + "workspaceOperationPlan\\s*\\("),
                        sessionStore),
                "No other session-store path may consume reparsed workspace authority.");
        assertFalse(
                Pattern.compile(
                                "TurnPolicyTrace\\s*\\.\\s*from\\s*\\(\\s*authoritative\\b",
                                Pattern.DOTALL)
                        .matcher(sessionStore)
                        .find(),
                "The session store must not route a reparsed lexical TaskResolution through "
                        + "TurnPolicyTrace.from, which consumes workspaceOperationPlan().");
    }

    @Test
    void production_consumers_never_build_checkpoint_plans_from_an_unbound_tool_call()
            throws Exception {
        Map<String, String> sources = productionSources();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String code = codeOnly(source.getValue());
            if (code.contains("import static dev.talos.runtime.workspace."
                    + "WorkspaceOperationPlanner.checkpointPlan")) {
                violations.add(source.getKey() + ": static import bypasses the owner ratchet");
            }
            for (Invocation invocation : invocations(
                    code,
                    "WorkspaceOperationPlanner.checkpointPlan")) {
                if (invocation.argumentCount() != 2) {
                    violations.add(source.getKey() + ": checkpointPlan call at byte "
                            + invocation.startOffset() + " has "
                            + invocation.argumentCount() + " argument(s)");
                }
            }
        }
        assertEquals(
                List.of(),
                violations,
                "Every production checkpoint plan consumer must bind the tool call to the "
                        + "captured WorkspaceOperationAuthorityPlan.");
    }

    @Test
    void only_t1196_and_t1204_lexical_owners_can_create_workspace_obligations()
            throws Exception {
        Map<String, String> sources = productionSources();

        assertEquals(
                EnumSet.of(
                        WorkspaceOperationObligation.Owner.COORDINATED_TAIL,
                        WorkspaceOperationObligation.Owner.SEQUENTIAL_CLAUSE),
                EnumSet.allOf(WorkspaceOperationObligation.Owner.class),
                "Adding another workspace-operation authority owner requires an explicit "
                        + "ticketed ownership decision.");

        assertEquals(
                Map.of("dev/talos/runtime/intent/CanonicalMutationClauseMatcher.java", 1),
                occurrenceOwners(
                        sources,
                        Pattern.compile(
                                "\\bWorkspaceOperationObligation\\s*\\.\\s*create\\s*\\(")),
                "The canonical matcher must remain the sole obligation factory caller.");

        assertEquals(
                Map.of("dev/talos/runtime/intent/WorkspaceOperationObligation.java", 1),
                occurrenceOwners(
                        sources,
                        Pattern.compile(
                                "\\bnew\\s+WorkspaceOperationObligation\\s*\\(")),
                "No production class may construct workspace-operation authority outside "
                        + "the obligation's private factory boundary.");

        Map<String, String> expectedLexicalOwners = Map.of(
                "dev/talos/runtime/intent/CoordinatedMutationTailParser.java",
                "WorkspaceOperationObligation.Owner.COORDINATED_TAIL",
                "dev/talos/runtime/intent/SequentialMutationClauseParser.java",
                "WorkspaceOperationObligation.Owner.SEQUENTIAL_CLAUSE");
        Map<String, List<Invocation>> bindOwners = invocationOwners(
                sources,
                "CanonicalMutationClauseMatcher.bindWorkspaceOperation");
        assertEquals(
                expectedLexicalOwners.keySet(),
                bindOwners.keySet(),
                "Only the canonical T1196 coordinated-tail and T1204 sequential-clause "
                        + "parsers may bind workspace-operation obligations.");
        for (Map.Entry<String, String> expected : expectedLexicalOwners.entrySet()) {
            List<Invocation> calls = bindOwners.get(expected.getKey());
            assertEquals(
                    1,
                    calls.size(),
                    expected.getKey() + " must own exactly one canonical bind edge.");
            Invocation call = calls.getFirst();
            assertEquals(
                    7,
                    call.argumentCount(),
                    expected.getKey() + " must use the complete canonical binding contract.");
            assertTrue(
                    call.arguments().replaceAll("\\s+", "")
                            .contains(expected.getValue()),
                    expected.getKey() + " must bind its exact declared lexical owner.");
        }
    }

    private static Map<String, String> productionSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (var paths = Files.walk(MAIN_SOURCE)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList()) {
                String relative = MAIN_SOURCE.relativize(path)
                        .toString()
                        .replace('\\', '/');
                sources.put(relative, Files.readString(path));
            }
        }
        return sources;
    }

    private static Map<String, Integer> occurrenceOwners(
            Map<String, String> sources,
            Pattern occurrence
    ) {
        Map<String, Integer> owners = new TreeMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            int count = matcherCount(occurrence, codeOnly(source.getValue()));
            if (count > 0) owners.put(source.getKey(), count);
        }
        return owners;
    }

    private static int matcherCount(Pattern pattern, String source) {
        int count = 0;
        var matcher = pattern.matcher(source);
        while (matcher.find()) count++;
        return count;
    }

    private static Map<String, List<Invocation>> invocationOwners(
            Map<String, String> sources,
            String qualifiedMethod
    ) {
        Map<String, List<Invocation>> owners = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            List<Invocation> calls = invocations(
                    codeOnly(source.getValue()),
                    qualifiedMethod);
            if (!calls.isEmpty()) owners.put(source.getKey(), calls);
        }
        return owners;
    }

    private static List<Invocation> invocations(
            String source,
            String qualifiedMethod
    ) {
        List<Invocation> calls = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom < source.length()) {
            int methodStart = source.indexOf(qualifiedMethod, searchFrom);
            if (methodStart < 0) break;
            int cursor = methodStart + qualifiedMethod.length();
            while (cursor < source.length()
                    && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= source.length() || source.charAt(cursor) != '(') {
                searchFrom = methodStart + qualifiedMethod.length();
                continue;
            }
            int close = matchingParen(source, cursor);
            String arguments = source.substring(cursor + 1, close);
            calls.add(new Invocation(
                    methodStart,
                    arguments,
                    topLevelArgumentCount(arguments)));
            searchFrom = close + 1;
        }
        return List.copyOf(calls);
    }

    private static int matchingParen(String source, int open) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '(') depth++;
            if (current == ')' && --depth == 0) return index;
        }
        throw new IllegalArgumentException("unbalanced invocation in production source");
    }

    private static int topLevelArgumentCount(String arguments) {
        if (arguments.isBlank()) return 0;
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        int commas = 0;
        for (int index = 0; index < arguments.length(); index++) {
            switch (arguments.charAt(index)) {
                case '(' -> parens++;
                case ')' -> parens--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '{' -> braces++;
                case '}' -> braces--;
                case ',' -> {
                    if (parens == 0 && brackets == 0 && braces == 0) commas++;
                }
                default -> {
                    // Nothing to do.
                }
            }
        }
        return commas + 1;
    }

    /** Replaces comments and literals with spaces while preserving source offsets. */
    private static String codeOnly(String source) {
        char[] out = new char[source.length()];
        java.util.Arrays.fill(out, ' ');
        LexState state = LexState.CODE;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length()
                    ? source.charAt(index + 1)
                    : '\0';
            char third = index + 2 < source.length()
                    ? source.charAt(index + 2)
                    : '\0';
            if (current == '\n' || current == '\r') out[index] = current;
            switch (state) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        state = LexState.LINE_COMMENT;
                        index++;
                    } else if (current == '/' && next == '*') {
                        state = LexState.BLOCK_COMMENT;
                        index++;
                    } else if (current == '"' && next == '"' && third == '"') {
                        state = LexState.TEXT_BLOCK;
                        index += 2;
                    } else if (current == '"') {
                        state = LexState.STRING;
                        escaped = false;
                    } else if (current == '\'') {
                        state = LexState.CHARACTER;
                        escaped = false;
                    } else {
                        out[index] = current;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n' || current == '\r') state = LexState.CODE;
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        index++;
                        state = LexState.CODE;
                    }
                }
                case STRING -> {
                    if (current == '"' && !escaped) state = LexState.CODE;
                    escaped = current == '\\' && !escaped;
                    if (current != '\\') escaped = false;
                }
                case CHARACTER -> {
                    if (current == '\'' && !escaped) state = LexState.CODE;
                    escaped = current == '\\' && !escaped;
                    if (current != '\\') escaped = false;
                }
                case TEXT_BLOCK -> {
                    if (current == '"' && next == '"' && third == '"') {
                        index += 2;
                        state = LexState.CODE;
                    }
                }
            }
        }
        return new String(out);
    }

    private record Invocation(
            int startOffset,
            String arguments,
            int argumentCount
    ) {}

    private enum LexState {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        CHARACTER,
        TEXT_BLOCK
    }
}
