package dev.talos.runtime.intent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * One parser-owned, exact workspace-operation obligation.
 *
 * <p>The obligation is created only by the canonical T1196/T1204 lexical
 * owners. It retains operand roles and exact source coordinates so downstream
 * runtime code never needs to recover authority from user prose.
 */
public final class WorkspaceOperationObligation {
    private static final String IDENTITY_VERSION =
            "talos-workspace-operation-obligation-v1";

    public enum Owner {
        COORDINATED_TAIL,
        SEQUENTIAL_CLAUSE
    }

    public enum Kind {
        COPY_PATH("talos.copy_path"),
        MOVE_PATH("talos.move_path"),
        RENAME_PATH("talos.rename_path"),
        MKDIR("talos.mkdir"),
        DELETE_PATH("talos.delete_path");

        private final String toolName;

        Kind(String toolName) {
            this.toolName = toolName;
        }

        public String toolName() {
            return toolName;
        }
    }

    public enum Role {
        SOURCE,
        DESTINATION,
        TARGET
    }

    public enum OverwritePolicy {
        FAIL_IF_EXISTS,
        OVERWRITE_EXPLICIT,
        NOT_APPLICABLE
    }

    public enum RecursivePolicy {
        NON_RECURSIVE,
        RECURSIVE_EXPLICIT,
        NOT_APPLICABLE
    }

    public enum ParentPolicy {
        REQUIRE_EXISTING_PARENT,
        NOT_APPLICABLE
    }

    public enum Status {
        AUTHORIZED
    }

    public record Operand(
            Role role,
            String lexicalValue,
            String effectivePath,
            int valueStartOffset,
            int valueEndOffset
    ) {
        public Operand {
            if (role == null) {
                throw new IllegalArgumentException(
                        "workspace operand role must not be null");
            }
            lexicalValue = Objects.toString(lexicalValue, "");
            effectivePath = normalizePath(effectivePath);
            if (lexicalValue.isBlank()
                    || effectivePath.isBlank()
                    || valueStartOffset < 0
                    || valueEndOffset <= valueStartOffset) {
                throw new IllegalArgumentException(
                        "workspace operand must retain an exact nonempty span");
            }
        }
    }

    private final String stableId;
    private final Owner owner;
    private final Kind kind;
    private final List<Operand> operands;
    private final OverwritePolicy overwritePolicy;
    private final RecursivePolicy recursivePolicy;
    private final ParentPolicy parentPolicy;
    private final String canonicalRequestSha256;
    private final int canonicalRequestUtf8Bytes;
    private final int actionStartOffset;
    private final int actionEndOffset;
    private final int sourceStartOffset;
    private final int sourceEndOffset;
    private final String sourceText;
    private final String sourceSpanSha256;

    private WorkspaceOperationObligation(
            String stableId,
            Owner owner,
            Kind kind,
            List<Operand> operands,
            OverwritePolicy overwritePolicy,
            RecursivePolicy recursivePolicy,
            ParentPolicy parentPolicy,
            String canonicalRequestSha256,
            int canonicalRequestUtf8Bytes,
            int actionStartOffset,
            int actionEndOffset,
            int sourceStartOffset,
            int sourceEndOffset,
            String sourceText,
            String sourceSpanSha256
    ) {
        if (owner == null || kind == null) {
            throw new IllegalArgumentException(
                    "workspace operation owner and kind must be explicit");
        }
        this.owner = owner;
        this.kind = kind;
        this.operands = List.copyOf(operands == null ? List.of() : operands);
        this.overwritePolicy = Objects.requireNonNull(
                overwritePolicy,
                "overwrite policy must be explicit");
        this.recursivePolicy = Objects.requireNonNull(
                recursivePolicy,
                "recursive policy must be explicit");
        this.parentPolicy = Objects.requireNonNull(
                parentPolicy,
                "parent policy must be explicit");
        this.canonicalRequestSha256 = requireSha256(
                canonicalRequestSha256,
                "canonical request");
        if (canonicalRequestUtf8Bytes < 0) {
            throw new IllegalArgumentException(
                    "canonical request byte count must not be negative");
        }
        this.canonicalRequestUtf8Bytes = canonicalRequestUtf8Bytes;
        if (sourceStartOffset < 0
                || sourceEndOffset <= sourceStartOffset
                || actionStartOffset < sourceStartOffset
                || actionEndOffset <= actionStartOffset
                || actionEndOffset > sourceEndOffset) {
            throw new IllegalArgumentException(
                    "workspace operation source/action span is invalid");
        }
        this.actionStartOffset = actionStartOffset;
        this.actionEndOffset = actionEndOffset;
        this.sourceStartOffset = sourceStartOffset;
        this.sourceEndOffset = sourceEndOffset;
        this.sourceText = Objects.toString(sourceText, "");
        if (this.sourceText.isEmpty()
                || this.sourceText.length() != sourceEndOffset - sourceStartOffset) {
            throw new IllegalArgumentException(
                    "workspace operation must retain its exact source text");
        }
        this.sourceSpanSha256 = requireSha256(
                sourceSpanSha256,
                "source span");
        if (!sha256(this.sourceText).equals(this.sourceSpanSha256)) {
            throw new IllegalArgumentException(
                    "workspace operation source text/hash mismatch");
        }
        validateOperands();
        String expectedId = computeStableId();
        String suppliedId = Objects.toString(stableId, "").strip().toLowerCase();
        if (!suppliedId.isEmpty() && !expectedId.equals(suppliedId)) {
            throw new IllegalArgumentException(
                    "workspace operation stable identity mismatch");
        }
        this.stableId = expectedId;
    }

    static WorkspaceOperationObligation create(
            Owner owner,
            Kind kind,
            List<Operand> operands,
            OverwritePolicy overwritePolicy,
            RecursivePolicy recursivePolicy,
            ParentPolicy parentPolicy,
            String canonicalRequest,
            int actionStartOffset,
            int actionEndOffset,
            int sourceStartOffset,
            int sourceEndOffset
    ) {
        String request = Objects.toString(canonicalRequest, "");
        if (sourceEndOffset > request.length()) {
            throw new IllegalArgumentException(
                    "workspace operation span exceeds canonical request");
        }
        for (Operand operand : operands == null ? List.<Operand>of() : operands) {
            if (operand == null
                    || operand.valueStartOffset() < 0
                    || operand.valueEndOffset() > request.length()
                    || !request.substring(
                                    operand.valueStartOffset(),
                                    operand.valueEndOffset())
                            .equals(operand.lexicalValue())) {
                throw new IllegalArgumentException(
                        "workspace operand lexical value differs from its canonical request span");
            }
        }
        String sourceText = request.substring(sourceStartOffset, sourceEndOffset);
        return new WorkspaceOperationObligation(
                "",
                owner,
                kind,
                operands,
                overwritePolicy,
                recursivePolicy,
                parentPolicy,
                sha256(request),
                request.getBytes(StandardCharsets.UTF_8).length,
                actionStartOffset,
                actionEndOffset,
                sourceStartOffset,
                sourceEndOffset,
                sourceText,
                sha256(sourceText));
    }

    private void validateOperands() {
        List<Role> roles = operands.stream().map(Operand::role).toList();
        List<Role> expected = switch (kind) {
            case COPY_PATH, MOVE_PATH, RENAME_PATH ->
                    List.of(Role.SOURCE, Role.DESTINATION);
            case MKDIR, DELETE_PATH -> List.of(Role.TARGET);
        };
        if (!roles.equals(expected)) {
            throw new IllegalArgumentException(
                    "workspace operation operand roles do not match its kind");
        }
        int priorEnd = actionEndOffset;
        for (Operand operand : operands) {
            if (operand.valueStartOffset() < priorEnd
                    || operand.valueEndOffset() > sourceEndOffset) {
                throw new IllegalArgumentException(
                        "workspace operands must be ordered inside the source span");
            }
            priorEnd = operand.valueEndOffset();
        }
        if ((kind == Kind.COPY_PATH
                        || kind == Kind.MOVE_PATH
                        || kind == Kind.RENAME_PATH)
                && operands.getFirst().effectivePath().equals(
                        operands.getLast().effectivePath())) {
            throw new IllegalArgumentException(
                    "workspace operation source and destination must be distinct");
        }
        switch (kind) {
            case COPY_PATH -> requirePolicies(
                    false,
                    true,
                    true);
            case MOVE_PATH, RENAME_PATH -> requirePolicies(
                    false,
                    false,
                    true);
            case MKDIR -> requirePolicies(
                    true,
                    false,
                    true);
            case DELETE_PATH -> {
                if (overwritePolicy != OverwritePolicy.NOT_APPLICABLE
                        || parentPolicy != ParentPolicy.NOT_APPLICABLE) {
                    throw new IllegalArgumentException(
                            "delete policies do not match deterministic tool semantics");
                }
            }
        }
        if (kind == Kind.RENAME_PATH) {
            String source = operands.getFirst().effectivePath();
            String destination = operands.getLast().effectivePath();
            int slash = source.lastIndexOf('/');
            String parent = slash < 0 ? "" : source.substring(0, slash + 1);
            if (!destination.startsWith(parent)
                    || destination.substring(parent.length()).contains("/")) {
                throw new IllegalArgumentException(
                        "rename destination must remain in the source parent");
            }
        }
    }

    private void requirePolicies(
            boolean overwriteNotApplicable,
            boolean recursionApplicable,
            boolean parentRequired
    ) {
        if (overwriteNotApplicable
                ? overwritePolicy != OverwritePolicy.NOT_APPLICABLE
                : overwritePolicy == OverwritePolicy.NOT_APPLICABLE) {
            throw new IllegalArgumentException(
                    "workspace overwrite policy does not match the operation");
        }
        if (recursionApplicable
                ? recursivePolicy == RecursivePolicy.NOT_APPLICABLE
                : recursivePolicy != RecursivePolicy.NOT_APPLICABLE) {
            throw new IllegalArgumentException(
                    "workspace recursion policy does not match the operation");
        }
        if (parentRequired
                && parentPolicy != ParentPolicy.REQUIRE_EXISTING_PARENT) {
            throw new IllegalArgumentException(
                    "workspace destination-parent policy must be explicit");
        }
    }

    private String computeStableId() {
        StringBuilder canonical = new StringBuilder(IDENTITY_VERSION);
        append(canonical, owner.name());
        append(canonical, kind.name());
        append(canonical, canonicalRequestSha256);
        append(canonical, Integer.toString(canonicalRequestUtf8Bytes));
        append(canonical, Integer.toString(actionStartOffset));
        append(canonical, Integer.toString(actionEndOffset));
        append(canonical, Integer.toString(sourceStartOffset));
        append(canonical, Integer.toString(sourceEndOffset));
        append(canonical, sourceSpanSha256);
        append(canonical, overwritePolicy.name());
        append(canonical, recursivePolicy.name());
        append(canonical, parentPolicy.name());
        append(canonical, Integer.toString(operands.size()));
        for (Operand operand : operands) {
            append(canonical, operand.role().name());
            append(canonical, sha256(operand.lexicalValue()));
            append(canonical, sha256(operand.effectivePath()));
            append(canonical, Integer.toString(operand.valueStartOffset()));
            append(canonical, Integer.toString(operand.valueEndOffset()));
        }
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        String safe = Objects.toString(value, "");
        target.append(safe.length()).append(':').append(safe);
    }

    private static String requireSha256(String value, String label) {
        String normalized = Objects.toString(value, "").strip().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " identity must be SHA-256");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            Objects.toString(value, "")
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String normalizePath(String value) {
        String input = Objects.toString(value, "").strip().replace('\\', '/');
        if (input.isEmpty()) return "";
        boolean rooted = input.startsWith("/");
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : input.split("/+")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (!segments.isEmpty() && !"..".equals(segments.peekLast())) {
                    segments.removeLast();
                } else {
                    segments.addLast(segment);
                }
            } else {
                segments.addLast(segment);
            }
        }
        String normalized = String.join("/", segments);
        return rooted ? "/" + normalized : normalized;
    }

    public String stableId() {
        return stableId;
    }

    public String operationId() {
        return stableId;
    }

    public Owner owner() {
        return owner;
    }

    public Kind kind() {
        return kind;
    }

    public String toolName() {
        return kind.toolName();
    }

    public List<Operand> operands() {
        return operands;
    }

    public OverwritePolicy overwritePolicy() {
        return overwritePolicy;
    }

    public RecursivePolicy recursivePolicy() {
        return recursivePolicy;
    }

    public ParentPolicy parentPolicy() {
        return parentPolicy;
    }

    public Status status() {
        return Status.AUTHORIZED;
    }

    public String canonicalRequestSha256() {
        return canonicalRequestSha256;
    }

    public int canonicalRequestUtf8Bytes() {
        return canonicalRequestUtf8Bytes;
    }

    public int actionStartOffset() {
        return actionStartOffset;
    }

    public int actionEndOffset() {
        return actionEndOffset;
    }

    public int sourceStartOffset() {
        return sourceStartOffset;
    }

    public int sourceEndOffset() {
        return sourceEndOffset;
    }

    public String sourceText() {
        return sourceText;
    }

    public String sourceSpanSha256() {
        return sourceSpanSha256;
    }

    public String sourcePath() {
        return operandPath(Role.SOURCE);
    }

    public String destinationPath() {
        return operandPath(Role.DESTINATION);
    }

    public String targetPath() {
        return operandPath(Role.TARGET);
    }

    private String operandPath(Role role) {
        return operands.stream()
                .filter(operand -> operand.role() == role)
                .map(Operand::effectivePath)
                .findFirst()
                .orElse("");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkspaceOperationObligation obligation
                && stableId.equals(obligation.stableId);
    }

    @Override
    public int hashCode() {
        return stableId.hashCode();
    }

    @Override
    public String toString() {
        return "WorkspaceOperationObligation[stableId=" + stableId
                + ", owner=" + owner
                + ", kind=" + kind
                + ", operands=" + operands
                + ", overwritePolicy=" + overwritePolicy
                + ", recursivePolicy=" + recursivePolicy
                + ", parentPolicy=" + parentPolicy
                + ", sourceStartOffset=" + sourceStartOffset
                + ", sourceEndOffset=" + sourceEndOffset + ']';
    }
}
