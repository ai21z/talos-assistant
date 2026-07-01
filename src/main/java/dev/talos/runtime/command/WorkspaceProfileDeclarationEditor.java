package dev.talos.runtime.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds proposed {@code .talos/profiles.yaml} bytes for terminal-side profile
 * declaration. This helper deliberately does not write trust pins: declaration
 * ergonomics stay separate from the SHA-256 approval chain.
 */
public final class WorkspaceProfileDeclarationEditor {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern ID_SHAPE = Pattern.compile("[a-z0-9_-]{1,32}");

    private WorkspaceProfileDeclarationEditor() {}

    public record Request(
            String id,
            String executable,
            List<String> args,
            Long timeoutMs,
            List<String> expectedWrites
    ) {
        public Request {
            id = safe(id).toLowerCase(Locale.ROOT);
            executable = safe(executable);
            args = args == null ? List.of() : List.copyOf(args);
            expectedWrites = expectedWrites == null ? List.of() : List.copyOf(expectedWrites);
        }
    }

    public record Proposal(
            String profileId,
            String content,
            String declarationSha256,
            int profileCount,
            boolean replacedExisting
    ) {}

    public static Proposal propose(Path workspace, Request request) {
        Objects.requireNonNull(workspace, "workspace is required");
        validateRequest(request);

        WorkspaceCommandProfilesLoader.Loaded loaded =
                WorkspaceCommandProfilesLoader.load(workspace);
        if (loaded.profiles().declared() && !loaded.profiles().valid()) {
            throw new CommandPlanRejectedException(
                    "existing declaration is invalid; fix .talos/profiles.yaml before configuring it: "
                            + loaded.profiles().rejectionReason());
        }

        List<Declaration> declarations = readExistingDeclarations(workspace, loaded);
        Declaration next = new Declaration(
                request.id(),
                request.executable(),
                request.args(),
                request.timeoutMs(),
                request.expectedWrites());

        boolean replaced = false;
        for (int i = 0; i < declarations.size(); i++) {
            if (declarations.get(i).id().equals(next.id())) {
                declarations.set(i, next);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            declarations.add(next);
        }
        if (declarations.size() > WorkspaceCommandProfilesLoader.MAX_PROFILES) {
            throw new CommandPlanRejectedException(
                    "at most " + WorkspaceCommandProfilesLoader.MAX_PROFILES
                            + " profiles may be declared");
        }

        String content = render(declarations);
        byte[] raw = content.getBytes(StandardCharsets.UTF_8);
        WorkspaceCommandProfilesLoader.Loaded proposed =
                WorkspaceCommandProfilesLoader.loadBytes(raw, workspace);
        if (!proposed.profiles().valid()) {
            throw new CommandPlanRejectedException(
                    "proposed declaration is invalid: "
                            + proposed.profiles().rejectionReason());
        }
        return new Proposal(
                WorkspaceCommandProfilesLoader.PROFILE_ID_PREFIX + next.id(),
                content,
                proposed.declarationSha256(),
                proposed.profiles().profiles().size(),
                replaced);
    }

    private static void validateRequest(Request request) {
        if (request == null) {
            throw new CommandPlanRejectedException("profile request is required");
        }
        if (!ID_SHAPE.matcher(request.id()).matches()) {
            throw new CommandPlanRejectedException(
                    "profile id must match [a-z0-9_-]{1,32}");
        }
        if (request.executable().isBlank()) {
            throw new CommandPlanRejectedException("profile executable is required");
        }
        for (String arg : request.args()) {
            if (arg == null || arg.isBlank()) {
                throw new CommandPlanRejectedException("profile args must be non-blank");
            }
        }
        for (String expectedWrite : request.expectedWrites()) {
            if (expectedWrite == null || expectedWrite.isBlank()) {
                throw new CommandPlanRejectedException("expected writes must be non-blank");
            }
        }
    }

    private static List<Declaration> readExistingDeclarations(
            Path workspace,
            WorkspaceCommandProfilesLoader.Loaded loaded
    ) {
        if (loaded == null || !loaded.profiles().declared()) {
            return new ArrayList<>();
        }
        Path declaration = workspace.toAbsolutePath().normalize()
                .resolve(WorkspaceCommandProfilesLoader.DECLARATION_RELATIVE_PATH);
        try {
            JsonNode root = YAML.readTree(Files.readString(declaration, StandardCharsets.UTF_8));
            JsonNode profiles = root.path("profiles");
            List<Declaration> out = new ArrayList<>();
            for (JsonNode node : profiles) {
                out.add(new Declaration(
                        safe(node.path("id").asText()).toLowerCase(Locale.ROOT),
                        safe(node.path("executable").asText()),
                        textList(node.path("args")),
                        node.path("timeout_ms").canConvertToLong()
                                ? node.path("timeout_ms").asLong()
                                : null,
                        textList(node.path("expected_writes"))));
            }
            return out;
        } catch (Exception e) {
            throw new CommandPlanRejectedException(
                    "existing declaration could not be read: " + e.getMessage());
        }
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            out.add(safe(item.asText()));
        }
        return List.copyOf(out);
    }

    private static String render(List<Declaration> declarations) {
        StringBuilder sb = new StringBuilder();
        sb.append("profiles:\n");
        for (Declaration declaration : declarations) {
            sb.append("  - id: ").append(declaration.id()).append('\n');
            sb.append("    executable: ").append(quote(declaration.executable())).append('\n');
            if (!declaration.args().isEmpty()) {
                sb.append("    args:\n");
                for (String arg : declaration.args()) {
                    sb.append("      - ").append(quote(arg)).append('\n');
                }
            }
            if (declaration.timeoutMs() != null) {
                sb.append("    timeout_ms: ").append(declaration.timeoutMs()).append('\n');
            }
            if (!declaration.expectedWrites().isEmpty()) {
                sb.append("    expected_writes:\n");
                for (String expectedWrite : declaration.expectedWrites()) {
                    sb.append("      - ").append(quote(expectedWrite)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String quote(String value) {
        String safeValue = safe(value);
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < safeValue.length(); i++) {
            char ch = safeValue.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record Declaration(
            String id,
            String executable,
            List<String> args,
            Long timeoutMs,
            List<String> expectedWrites
    ) {
        private Declaration {
            args = args == null ? List.of() : List.copyOf(args);
            expectedWrites = expectedWrites == null ? List.of() : List.copyOf(expectedWrites);
        }
    }
}
