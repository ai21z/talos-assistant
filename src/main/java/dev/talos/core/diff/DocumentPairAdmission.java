package dev.talos.core.diff;

import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.extract.DocumentSnapshotExtractor;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.privacy.DocumentContentDecision;
import dev.talos.core.privacy.PrivateDocumentContentPolicy;
import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.core.security.Sandbox;
import dev.talos.safety.ProtectedContentSanitizer;
import dev.talos.safety.ProtectedWorkspacePaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Optional;

/** Both requested operands are admitted before content access. No runtime tool is registered here. */
public final class DocumentPairAdmission {
    public static final String VERSION = "document-pair-admission-v1";
    private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;

    public enum Status {
        COMPLETE, INVALID_REQUEST, REQUEST_BINDING_MISMATCH, PATH_REJECTED,
        PROTECTED_INPUT, UNSUPPORTED_FORMAT, INPUT_LIMIT, SOURCE_CHANGED,
        READ_FAILED, INCOMPLETE_INPUT, CANCELLED
    }
    public enum Operand { NONE, LEFT, RIGHT }

    /** This is caller-supplied request binding, not a model-created execution permit. */
    public record RequestedPair(String left, String right) {
        @Override public String toString() { return "RequestedPair[ordered]"; }
    }

    public record SourceIdentity(String relativePath, int byteLength, String rawSha256,
                                 String textSha256, DocumentTextDiff.Representation representation,
                                 DocumentContentDecision contentDecision) {
        @Override public String toString() { return "SourceIdentity[representation=" + representation + "]"; }
    }

    /** Raw snapshots are kept in memory. A purpose view does not establish downstream sink enforcement. */
    public static final class CapturedPair {
        private final SourceIdentity leftIdentity;
        private final SourceIdentity rightIdentity;
        private final DocumentTextDiff.Snapshot left;
        private final DocumentTextDiff.Snapshot right;
        private final DocumentContentDecision contentDecision;

        private CapturedPair(Admitted leftSource, Admitted rightSource,
                             DocumentTextDiff.Snapshot left, DocumentTextDiff.Snapshot right) {
            leftIdentity = identity(leftSource, left);
            rightIdentity = identity(rightSource, right);
            this.left = left;
            this.right = right;
            contentDecision = DocumentContentDecision.forDerivedPair(leftSource.privacy, rightSource.privacy);
        }

        public SourceIdentity leftIdentity() { return leftIdentity; }
        public SourceIdentity rightIdentity() { return rightIdentity; }
        public DocumentContentDecision contentDecision() { return contentDecision; }
        public Optional<View> view(DocumentContentDecision.Use use) {
            return contentDecision.allows(use) ? Optional.of(new View(left, right)) : Optional.empty();
        }
        @Override public String toString() { return "CapturedPair[ordered, content withheld]"; }
    }

    public record View(DocumentTextDiff.Snapshot left, DocumentTextDiff.Snapshot right) {
        public View {
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
        }
        @Override public String toString() { return "DocumentPairView[content withheld]"; }
    }

    public record Result(Status status, Operand operand, String detail, CapturedPair pair) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(operand);
            Objects.requireNonNull(detail);
            if ((status == Status.COMPLETE) != (pair != null)) {
                throw new IllegalArgumentException("Only complete capture carries a pair.");
            }
        }
        @Override public String toString() { return "DocumentPairAdmission[status=" + status + ", operand=" + operand + "]"; }
    }

    private record Stamp(Object fileKey, long size, FileTime modified, FileTime created) {}
    private record Admitted(Path path, String relativePath, Stamp stamp,
                            DocumentSnapshotExtractor.Format format, DocumentContentDecision privacy) {}
    private record Pair(Admitted left, Admitted right) {}

    interface Access {
        BasicFileAttributes attributes(Path path) throws IOException;
        InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) throws IOException;
    }

    private static final Access FILES = new Access() {
        public BasicFileAttributes attributes(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) throws IOException {
            return root.openRegularFile(path);
        }
    };

    private final Access access;
    public DocumentPairAdmission() { this(FILES); }
    DocumentPairAdmission(Access access) { this.access = Objects.requireNonNull(access); }

    /** The ordered requested pair must be supplied by the owning current-request boundary. */
    public Result capture(Path workspace, Sandbox sandbox, Config config,
                          RequestedPair requested, RequestedPair proposed) {
        Operand active = Operand.NONE;
        try {
            checkCancellation();
            if (workspace == null || sandbox == null || config == null || requested == null || proposed == null) {
                throw rejected(Status.INVALID_REQUEST, "MISSING_CONTEXT");
            }
            var root = NoFollowPathPolicy.bindExistingDirectory(workspace);
            active = Operand.LEFT;
            Path left = bind(root.path(), requested.left, proposed.left);
            active = Operand.RIGHT;
            Path right = bind(root.path(), requested.right, proposed.right);
            active = Operand.LEFT;
            Admitted admittedLeft = admit(root, sandbox, config, left);
            active = Operand.RIGHT;
            Admitted admittedRight = admit(root, sandbox, config, right);
            Pair admitted = new Pair(admittedLeft, admittedRight);

            active = Operand.LEFT;
            recheck(root, sandbox, config, admitted.left);
            active = Operand.RIGHT;
            recheck(root, sandbox, config, admitted.right);
            active = Operand.LEFT;
            byte[] leftBytes = read(root, admitted.left);
            active = Operand.RIGHT;
            byte[] rightBytes = read(root, admitted.right);
            active = Operand.LEFT;
            recheck(root, sandbox, config, admitted.left);
            active = Operand.RIGHT;
            recheck(root, sandbox, config, admitted.right);

            active = Operand.LEFT;
            var leftSnapshot = snapshot(leftBytes, admitted.left.format);
            active = Operand.RIGHT;
            var rightSnapshot = snapshot(rightBytes, admitted.right.format);
            checkCancellation();
            return new Result(Status.COMPLETE, Operand.NONE, "CAPTURED_NOT_ATOMIC_PAIR",
                    new CapturedPair(admitted.left, admitted.right, leftSnapshot, rightSnapshot));
        } catch (Rejected denied) {
            return new Result(denied.status, active, denied.getMessage(), null);
        } catch (NoFollowPathPolicy.Violation denied) {
            return new Result(Status.PATH_REJECTED, active, denied.reason().name(), null);
        } catch (IOException | RuntimeException failure) {
            return new Result(Thread.currentThread().isInterrupted() ? Status.CANCELLED : Status.READ_FAILED,
                    active, "SOURCE_UNAVAILABLE", null);
        }
    }

    private static Path bind(Path workspace, String requested, String proposed) throws Rejected {
        Path expected = resolve(workspace, requested);
        Path actual = resolve(workspace, proposed);
        if (!expected.toString().equals(actual.toString())) {
            throw rejected(Status.REQUEST_BINDING_MISMATCH, "ORDERED_TARGET_MISMATCH");
        }
        return actual;
    }

    private static Path resolve(Path workspace, String value) throws Rejected {
        if (value == null || value.isBlank()) throw rejected(Status.INVALID_REQUEST, "MISSING_TARGET");
        try {
            Path parsed = Path.of(value);
            var classified = ProtectedWorkspacePaths.classify(workspace, value);
            if (!classified.insideWorkspace() || classified.workspaceEscape()) {
                throw rejected(Status.PATH_REJECTED, "OUTSIDE_WORKSPACE");
            }
            if (classified.protectedPath()) throw rejected(Status.PROTECTED_INPUT, "PROTECTED_PATH");
            Path path = (parsed.isAbsolute() ? parsed : workspace.resolve(parsed)).normalize();
            if (!path.startsWith(workspace)) throw rejected(Status.PATH_REJECTED, "OUTSIDE_WORKSPACE");
            return path;
        } catch (java.nio.file.InvalidPathException invalid) {
            throw rejected(Status.INVALID_REQUEST, "INVALID_TARGET");
        }
    }

    private Admitted admit(NoFollowPathPolicy.GuardedRoot root, Sandbox sandbox, Config config, Path path)
            throws IOException {
        checkCancellation();
        root.requireRegularFile(path);
        if (!path.toRealPath().equals(path)) {
            throw rejected(Status.PATH_REJECTED, "NON_CANONICAL_SOURCE");
        }
        var classified = ProtectedWorkspacePaths.classify(root.path(), path.toString());
        if (!classified.insideWorkspace() || classified.workspaceEscape()) {
            throw rejected(Status.PATH_REJECTED, "OUTSIDE_WORKSPACE");
        }
        if (classified.protectedPath()) throw rejected(Status.PROTECTED_INPUT, "PROTECTED_PATH");
        if (!sandbox.allowedPath(path)) throw rejected(Status.PATH_REJECTED, "SANDBOX_DENIED");
        var info = FileCapabilityPolicy.describe(path, config).orElse(null);
        DocumentSnapshotExtractor.Format format = null;
        if (info != null) {
            if (!info.enabled()) throw rejected(Status.UNSUPPORTED_FORMAT, "FORMAT_DISABLED_OR_UNSUPPORTED");
            format = switch (info.extension()) {
                case "pdf" -> DocumentSnapshotExtractor.Format.PDF;
                case "docx" -> DocumentSnapshotExtractor.Format.DOCX;
                default -> throw rejected(Status.UNSUPPORTED_FORMAT, "FORMAT_OUTSIDE_COMPARISON_SCOPE");
            };
        }
        BasicFileAttributes attributes = access.attributes(path);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw rejected(Status.PATH_REJECTED, "NON_ORDINARY_SOURCE");
        }
        if (attributes.size() > MAX_SOURCE_BYTES) throw rejected(Status.INPUT_LIMIT, "SOURCE_BYTE_LIMIT");
        var privacy = PrivateDocumentContentPolicy.decide(config,
                DocumentExtractionRequest.read(path, root.path()), info);
        return new Admitted(path, root.path().relativize(path).toString().replace('\\', '/'),
                stamp(attributes), format, privacy);
    }

    private void recheck(NoFollowPathPolicy.GuardedRoot root, Sandbox sandbox, Config config, Admitted before)
            throws IOException {
        Admitted after = admit(root, sandbox, config, before.path);
        if (!before.equals(after)) throw rejected(Status.SOURCE_CHANGED, "SOURCE_METADATA_OR_POLICY_CHANGED");
    }

    private byte[] read(NoFollowPathPolicy.GuardedRoot root, Admitted source) throws IOException {
        checkCancellation();
        if (!source.stamp.equals(stamp(access.attributes(source.path)))) {
            throw rejected(Status.SOURCE_CHANGED, "SOURCE_METADATA_CHANGED");
        }
        try (InputStream input = access.open(root, source.path);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (true) {
                checkCancellation();
                int count = input.read(buffer, 0, Math.min(buffer.length, MAX_SOURCE_BYTES - bytes.size() + 1));
                if (count < 0) break;
                if (count == 0) throw rejected(Status.READ_FAILED, "NON_PROGRESSING_READ");
                if (count > MAX_SOURCE_BYTES - bytes.size()) throw rejected(Status.INPUT_LIMIT, "SOURCE_BYTE_LIMIT");
                bytes.write(buffer, 0, count);
            }
            if (bytes.size() != source.stamp.size) throw rejected(Status.SOURCE_CHANGED, "SOURCE_LENGTH_CHANGED");
            return bytes.toByteArray();
        }
    }

    private static DocumentTextDiff.Snapshot snapshot(byte[] bytes, DocumentSnapshotExtractor.Format format)
            throws Rejected {
        if (format != null) {
            var extracted = DocumentSnapshotExtractor.extract(bytes, format);
            if (extracted.snapshot() == null) throw rejected(
                    extracted.status() == DocumentSnapshotExtractor.Status.CANCELLED ? Status.CANCELLED : Status.INCOMPLETE_INPUT,
                    "EXTRACTION_" + extracted.status().name());
            return extracted.snapshot();
        }
        var decoded = DocumentTextDiff.utf8(bytes);
        if (!decoded.complete()) throw rejected(
                decoded.status() == DocumentTextDiff.Status.CANCELLED ? Status.CANCELLED : Status.INCOMPLETE_INPUT,
                "TEXT_" + decoded.status().name());
        if (!Objects.equals(decoded.snapshot().text(), ProtectedContentSanitizer.sanitizeText(decoded.snapshot().text()))) {
            throw rejected(Status.INCOMPLETE_INPUT, "TEXT_REQUIRES_REDACTION");
        }
        return decoded.snapshot();
    }

    private static SourceIdentity identity(Admitted source, DocumentTextDiff.Snapshot snapshot) {
        return new SourceIdentity(source.relativePath, snapshot.rawByteLength(), snapshot.rawSha256(),
                snapshot.textSha256(), snapshot.representation(), source.privacy);
    }
    private static Stamp stamp(BasicFileAttributes value) {
        return new Stamp(value.fileKey(), value.size(), value.lastModifiedTime(), value.creationTime());
    }
    private static void checkCancellation() throws Rejected {
        if (Thread.currentThread().isInterrupted()) throw rejected(Status.CANCELLED, "INTERRUPTED");
    }
    private static Rejected rejected(Status status, String detail) { return new Rejected(status, detail); }
    private static final class Rejected extends IOException {
        private final Status status;
        private Rejected(Status status, String detail) { super(detail); this.status = status; }
    }
}
