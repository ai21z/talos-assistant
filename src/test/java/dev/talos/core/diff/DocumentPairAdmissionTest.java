package dev.talos.core.diff;

import dev.talos.core.Config;
import dev.talos.core.privacy.DocumentContentDecision.Use;
import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.core.security.Sandbox;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.talos.core.diff.DocumentPairAdmission.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentPairAdmissionTest {
    @TempDir Path workspace;
    private static final String PRIVATE_PROSE = "The blue orchard meeting was moved to Tuesday.";

    @Test
    void defaultServiceReadsRealFilesWithoutAnInjectedAccess() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var cfg = config(false);
        var targets = pair("left.txt", "right.txt");
        var result = new DocumentPairAdmission().capture(workspace, sandbox(cfg), cfg, targets, targets);
        assertEquals(Status.COMPLETE, result.status());
        var view = result.pair().view(Use.LOCAL_DISPLAY).orElseThrow();
        assertEquals("left", view.left().text());
        assertEquals("right", view.right().text());
    }

    @Test
    void realFilesAreCapturedExactlyWithBothIdentitiesAndBeforeEitherRead() throws Exception {
        byte[] left = "\ufeffalpha\r\nbeta\r".getBytes(StandardCharsets.UTF_8);
        byte[] right = "alpha\nbeta".getBytes(StandardCharsets.UTF_8);
        Files.write(workspace.resolve("left.txt"), left);
        Files.write(workspace.resolve("right.txt"), right);
        var access = new TrackedAccess();
        var result = capture(access, config(false), pair("left.txt", "right.txt"));

        assertEquals(Status.COMPLETE, result.status());
        assertEquals(List.of("left.txt", "right.txt"), access.opened);
        assertEquals(List.of("left.txt", "right.txt", "left.txt", "right.txt", "left.txt"),
                access.beforeFirstOpen);
        assertEquals(hash(left), result.pair().leftIdentity().rawSha256());
        assertEquals(hash(right), result.pair().rightIdentity().rawSha256());
        assertEquals("left.txt", result.pair().leftIdentity().relativePath());
        assertEquals("right.txt", result.pair().rightIdentity().relativePath());
        var view = result.pair().view(Use.LOCAL_DISPLAY).orElseThrow();
        assertEquals(new String(left, StandardCharsets.UTF_8), view.left().text());
        assertEquals(new String(right, StandardCharsets.UTF_8), view.right().text());
        assertFalse(DocumentTextDiff.compare(view.left(), view.right()).hunks().isEmpty());
        assertTrue(result.pair().view(Use.MODEL_CONTEXT).isPresent());
        assertTrue(result.pair().view(Use.RAG_INDEX).isPresent());
        assertTrue(result.pair().view(Use.RAW_ARTIFACT).isEmpty());
    }

    @Test
    void identicalEmptyPathPairIsNotDeduplicated() throws Exception {
        Files.write(workspace.resolve("empty.txt"), new byte[0]);
        var access = new TrackedAccess();
        var result = capture(access, config(false), pair("empty.txt", "empty.txt"));
        assertEquals(Status.COMPLETE, result.status());
        assertEquals(List.of("empty.txt", "empty.txt"), access.opened);
        assertEquals(result.pair().leftIdentity(), result.pair().rightIdentity());
        assertEquals(hash(new byte[0]), result.pair().leftIdentity().rawSha256());
    }

    @Test
    void swappedOrSubstitutedProposalDoesNotReadAnything() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        text("other.txt", "other");
        var requested = pair("left.txt", "right.txt");
        for (var proposed : List.of(pair("right.txt", "left.txt"), pair("other.txt", "right.txt"),
                pair("left.txt", "other.txt"))) {
            var access = new TrackedAccess();
            var result = service(access).capture(workspace, sandbox(config(false)), config(false), requested, proposed);
            assertEquals(Status.REQUEST_BINDING_MISMATCH, result.status());
            assertNull(result.pair());
            assertTrue(access.opened.isEmpty());
        }
    }

    @Test
    void lexicalEquivalentTargetsAreBoundWithoutBasenameMatching() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var requested = pair("left.txt", "./right.txt");
        var proposed = pair(workspace.resolve("left.txt").toString(), "right.txt");
        var result = service(new TrackedAccess()).capture(workspace, sandbox(config(false)), config(false), requested, proposed);
        assertEquals(Status.COMPLETE, result.status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyAdmissionDenialBlocksBothReads(boolean denyLeft) throws Exception {
        text("ordinary.txt", "ordinary");
        text(".env", "hidden");
        text("denied.txt", "denied");
        text("unsupported.doc", "not converted");
        text("sheet.xlsx", "not supported by comparison");
        text("disabled.pdf", "not parsed");
        Files.createDirectory(workspace.resolve("directory.txt"));
        Files.write(workspace.resolve("large.txt"), new byte[2 * 1024 * 1024 + 1]);
        var cfg = config(false);
        cfg.data.put("policy", Map.of("deny", List.of("denied.txt")));
        cfg.data.put("document_extraction", Map.of("enabled", false));
        Map<String, Status> cases = new LinkedHashMap<>();
        cases.put(".env", Status.PROTECTED_INPUT);
        cases.put("missing.txt", Status.PATH_REJECTED);
        cases.put("directory.txt", Status.PATH_REJECTED);
        cases.put("../outside.txt", Status.PATH_REJECTED);
        cases.put("denied.txt", Status.PATH_REJECTED);
        cases.put("unsupported.doc", Status.UNSUPPORTED_FORMAT);
        cases.put("sheet.xlsx", Status.UNSUPPORTED_FORMAT);
        cases.put("disabled.pdf", Status.UNSUPPORTED_FORMAT);
        cases.put("large.txt", Status.INPUT_LIMIT);
        for (var entry : cases.entrySet()) {
            var access = new TrackedAccess();
            var targets = denyLeft ? pair(entry.getKey(), "ordinary.txt") : pair("ordinary.txt", entry.getKey());
            var result = capture(access, cfg, targets);
            assertEquals(entry.getValue(), result.status(), entry.getKey());
            assertEquals(denyLeft ? Operand.LEFT : Operand.RIGHT, result.operand());
            assertNull(result.pair());
            assertTrue(access.opened.isEmpty(), entry.getKey());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void symlinkOperandIsRejectedBeforeEitherRead(boolean denyLeft) throws Exception {
        text("ordinary.txt", "ordinary");
        Files.createSymbolicLink(workspace.resolve("alias.txt"), Path.of("ordinary.txt"));
        var access = new TrackedAccess();
        var result = capture(access, config(false), denyLeft
                ? pair("alias.txt", "ordinary.txt") : pair("ordinary.txt", "alias.txt"));
        assertEquals(Status.PATH_REJECTED, result.status());
        assertNull(result.pair());
        assertTrue(access.opened.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void linkedParentIsRejectedBeforeBothReads(boolean linkedLeft) throws Exception {
        text("ordinary.txt", "ordinary");
        Files.createDirectory(workspace.resolve("real"));
        text("real/inside.txt", "inside");
        Files.createSymbolicLink(workspace.resolve("linked"), Path.of("real"));
        var access = new TrackedAccess();
        var result = capture(access, config(false), linkedLeft
                ? pair("linked/inside.txt", "ordinary.txt") : pair("ordinary.txt", "linked/inside.txt"));
        assertEquals(Status.PATH_REJECTED, result.status());
        assertTrue(access.opened.isEmpty());
    }

    @Test
    void enabledButOutOfScopeFormatDoesNotReachAParser() throws Exception {
        text("ordinary.txt", "ordinary");
        text("sheet.xlsx", "not a spreadsheet");
        var cfg = config(false);
        cfg.data.put("document_extraction", Map.of("enabled", true, "excel", Map.of("enabled", true)));
        var access = new TrackedAccess();
        var result = capture(access, cfg, pair("ordinary.txt", "sheet.xlsx"));
        assertEquals(Status.UNSUPPORTED_FORMAT, result.status());
        assertEquals("FORMAT_OUTSIDE_COMPARISON_SCOPE", result.detail());
        assertTrue(access.opened.isEmpty());
    }

    @ParameterizedTest
    @EnabledOnOs(OS.WINDOWS)
    @ValueSource(strings = {"record.pdf.", "record.docx."})
    void windowsFormatAliasesAreRejectedBeforeEitherRead(String alias) throws Exception {
        Files.write(workspace.resolve("record.pdf"), document("pdf"));
        Files.write(workspace.resolve("record.docx"), document("docx"));
        text("ordinary.txt", "ordinary");
        String canonical = alias.strip().replaceAll("\\.$", "");
        assertTrue(Files.isSameFile(workspace.resolve(alias), workspace.resolve(canonical)));
        var cfg = config(true);
        cfg.data.put("document_extraction", Map.of("enabled", false));
        for (boolean aliasLeft : new boolean[]{false, true}) {
            var access = new TrackedAccess();
            var result = capture(access, cfg, aliasLeft ? pair(alias, "ordinary.txt") : pair("ordinary.txt", alias));
            assertEquals(Status.PATH_REJECTED, result.status());
            assertNull(result.pair());
            assertTrue(access.opened.isEmpty());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDirectoryAliasIsRejectedBeforeEitherRead() throws Exception {
        Files.createDirectory(workspace.resolve("reports"));
        text("reports/record.txt", "body");
        text("ordinary.txt", "ordinary");
        assertTrue(Files.isSameFile(workspace.resolve("reports./record.txt"), workspace.resolve("reports/record.txt")));
        var access = new TrackedAccess();
        var result = capture(access, config(false), pair("ordinary.txt", "reports./record.txt"));
        assertEquals(Status.PATH_REJECTED, result.status());
        assertEquals("NON_CANONICAL_SOURCE", result.detail());
        assertTrue(access.opened.isEmpty());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsTrailingSpaceIsAnInvalidRequestBeforeAnyRead() throws Exception {
        text("ordinary.txt", "ordinary");
        var access = new TrackedAccess();
        var result = capture(access, config(false), pair("ordinary.txt", "record.pdf "));
        assertEquals(Status.INVALID_REQUEST, result.status());
        assertTrue(access.opened.isEmpty());
    }

    @Test
    void requestedTargetCaseIsNotSilentlyChangedByAProposal() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var cfg = config(false);
        var access = new TrackedAccess();
        var result = service(access).capture(workspace, sandbox(cfg), cfg,
                pair("left.txt", "right.txt"), pair("LEFT.txt", "right.txt"));
        assertEquals(Status.REQUEST_BINDING_MISMATCH, result.status());
        assertTrue(access.opened.isEmpty());
    }

    @Test
    void missingContextAndTargetsFailClosedWithoutOpening() throws Exception {
        var access = new TrackedAccess();
        var cfg = config(false);
        var service = service(access);
        var valid = pair("left.txt", "right.txt");
        assertEquals(Status.INVALID_REQUEST, service.capture(null, sandbox(cfg), cfg, valid, valid).status());
        assertEquals(Status.INVALID_REQUEST, service.capture(workspace, null, cfg, valid, valid).status());
        assertEquals(Status.INVALID_REQUEST, service.capture(workspace, sandbox(cfg), null, valid, valid).status());
        assertEquals(Status.INVALID_REQUEST, service.capture(workspace, sandbox(cfg), cfg, null, valid).status());
        assertEquals(Status.INVALID_REQUEST, service.capture(workspace, sandbox(cfg), cfg, valid, null).status());
        assertEquals(Status.INVALID_REQUEST, capture(access, cfg, pair(null, "right.txt")).status());
        assertEquals(Status.INVALID_REQUEST, capture(access, cfg, pair("", "right.txt")).status());
        assertEquals(Status.INVALID_REQUEST, capture(access, cfg, pair("bad\0.txt", "right.txt")).status());
        assertTrue(access.opened.isEmpty());
    }

    @Test
    void disabledSandboxDoesNotPermitWorkspaceEscape() throws Exception {
        var cfg = config(false);
        cfg.data.put("policy", Map.of("enabled", false));
        var access = new TrackedAccess();
        assertEquals(Status.PATH_REJECTED, capture(access, cfg, pair("../outside.txt", "ordinary.txt")).status());
        assertTrue(access.opened.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompleteTextNeverReturnsAPartialPair(boolean failLeft) throws Exception {
        text("ordinary.txt", "ordinary");
        for (byte[] invalid : List.of(new byte[]{(byte) 0xc3, 0x28}, new byte[]{0},
                "password=unexposed-value".getBytes(StandardCharsets.UTF_8),
                "x".repeat(64_001).getBytes(StandardCharsets.UTF_8))) {
            Files.write(workspace.resolve("invalid.txt"), invalid);
            var result = capture(new TrackedAccess(), config(false), failLeft
                    ? pair("invalid.txt", "ordinary.txt") : pair("ordinary.txt", "invalid.txt"));
            assertEquals(Status.INCOMPLETE_INPUT, result.status());
            assertNull(result.pair());
            assertEquals(failLeft ? Operand.LEFT : Operand.RIGHT, result.operand());
            assertFalse(result.toString().contains("unexposed-value"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "docx"})
    void realExtractedDocumentAndTextPairPreservePrivatePolicyInBothOrders(String extension) throws Exception {
        Files.write(workspace.resolve("record." + extension), document(extension));
        text("ordinary.txt", "public body");
        var cfg = config(true);
        for (boolean privateLeft : new boolean[]{false, true}) {
            var result = capture(new TrackedAccess(), cfg, privateLeft
                    ? pair("record." + extension, "ordinary.txt") : pair("ordinary.txt", "record." + extension));
            assertEquals(Status.COMPLETE, result.status(), result.detail());
            var pair = result.pair();
            assertTrue(pair.contentDecision().privateDocumentContent());
            assertTrue(pair.view(Use.MODEL_CONTEXT).isEmpty());
            assertTrue(pair.view(Use.RAW_ARTIFACT).isEmpty());
            assertTrue(pair.view(Use.RAG_INDEX).isEmpty());
            var local = pair.view(Use.LOCAL_DISPLAY).orElseThrow();
            assertTrue((privateLeft ? local.left() : local.right()).text().contains(PRIVATE_PROSE));
            var identity = privateLeft ? pair.leftIdentity() : pair.rightIdentity();
            assertEquals(DocumentTextDiff.Representation.EXTRACTED_DOCUMENT_TEXT, identity.representation());
            assertTrue(identity.contentDecision().privateDocumentContent());
            assertFalse(pair.toString().contains(PRIVATE_PROSE));
            assertFalse(local.toString().contains(PRIVATE_PROSE));
            assertFalse(result.toString().contains(PRIVATE_PROSE));
        }
    }

    @Test
    void configuredHandoffDoesNotPromotePersistenceAndSourceDecisionsRemainSeparate() throws Exception {
        Files.write(workspace.resolve("record.docx"), document("docx"));
        text("ordinary.txt", "ordinary");
        var cfg = config(true);
        cfg.data.put("privacy", Map.of("mode", "private", "document_extraction", Map.of("allow_send_to_model", true)));
        var result = capture(new TrackedAccess(), cfg, pair("record.docx", "ordinary.txt"));
        assertEquals(Status.COMPLETE, result.status());
        assertTrue(result.pair().view(Use.MODEL_CONTEXT).orElseThrow().left().text().contains(PRIVATE_PROSE));
        assertTrue(result.pair().view(Use.RAW_ARTIFACT).isEmpty());
        assertTrue(result.pair().view(Use.RAG_INDEX).isEmpty());
        assertTrue(result.pair().leftIdentity().contentDecision().privateDocumentContent());
        assertFalse(result.pair().rightIdentity().contentDecision().privateDocumentContent());
    }

    @Test
    void explicitArtifactAndIndexOptInsAreStillIndependentOfHandoff() throws Exception {
        Files.write(workspace.resolve("one.docx"), document("docx"));
        Files.write(workspace.resolve("two.docx"), document("docx"));
        var cfg = config(true);
        cfg.data.put("privacy", Map.of("mode", "private", "rag", Map.of("enabled_in_private_mode", true),
                "document_extraction", Map.of("persist_raw_artifacts", true, "allow_rag_indexing", true)));
        var result = capture(new TrackedAccess(), cfg, pair("one.docx", "two.docx"));
        assertEquals(Status.COMPLETE, result.status());
        assertTrue(result.pair().view(Use.MODEL_CONTEXT).isEmpty());
        assertTrue(result.pair().view(Use.RAW_ARTIFACT).isPresent());
        assertTrue(result.pair().view(Use.RAG_INDEX).isPresent());
    }

    @Test
    void corruptDocumentReturnsNoPairOrDependencyErrorText() throws Exception {
        text("broken.docx", PRIVATE_PROSE);
        text("ordinary.txt", "ordinary");
        var result = capture(new TrackedAccess(), config(true), pair("ordinary.txt", "broken.docx"));
        assertEquals(Status.INCOMPLETE_INPUT, result.status());
        assertEquals("EXTRACTION_INVALID_DOCUMENT", result.detail());
        assertNull(result.pair());
        assertFalse(result.toString().contains(PRIVATE_PROSE));
    }

    @Test
    void changeAfterAdmissionBeforeFirstOpenRejectsWithoutReading() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var access = new TrackedAccess() {
            int rightChecks;
            @Override public BasicFileAttributes attributes(Path path) throws IOException {
                if (path.getFileName().toString().equals("right.txt") && ++rightChecks == 2) {
                    Files.writeString(path, "changed length");
                }
                return super.attributes(path);
            }
        };
        var result = capture(access, config(false), pair("left.txt", "right.txt"));
        assertEquals(Status.SOURCE_CHANGED, result.status());
        assertNull(result.pair());
        assertTrue(access.opened.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changeBetweenOperandReadsIsRejected(boolean changeLeft) throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var access = new TrackedAccess() {
            @Override public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) throws IOException {
                if (path.endsWith("right.txt")) {
                    Path changed = workspace.resolve(changeLeft ? "left.txt" : "right.txt");
                    Files.setLastModifiedTime(changed, FileTime.fromMillis(Files.getLastModifiedTime(changed).toMillis() + 10_000));
                }
                return super.open(root, path);
            }
        };
        var result = capture(access, config(false), pair("left.txt", "right.txt"));
        assertEquals(Status.SOURCE_CHANGED, result.status());
        assertNull(result.pair());
    }

    @Test
    void changedPrivacyDecisionCannotReturnAnEarlierPermissivePair() throws Exception {
        Files.write(workspace.resolve("left.docx"), document("docx"));
        text("right.txt", "right");
        var cfg = config(false);
        var access = new TrackedAccess() {
            @Override public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) throws IOException {
                cfg.data.put("privacy", Map.of("mode", "private"));
                return super.open(root, path);
            }
        };
        var result = capture(access, cfg, pair("left.docx", "right.txt"));
        assertEquals(Status.SOURCE_CHANGED, result.status());
        assertNull(result.pair());
    }

    @Test
    void growingReadIsBoundedAndIncompleteContentIsDiscarded() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var access = new TrackedAccess() {
            @Override public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) {
                opened.add(path.getFileName().toString());
                return new ByteArrayInputStream(new byte[2 * 1024 * 1024 + 1]);
            }
        };
        var result = capture(access, config(false), pair("left.txt", "right.txt"));
        assertEquals(Status.INPUT_LIMIT, result.status());
        assertNull(result.pair());
        assertEquals(List.of("left.txt"), access.opened);
    }

    @Test
    void shortReadAndFailureDiagnosticsExposeNoSourceOrExceptionText() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var shortRead = new TrackedAccess() {
            @Override public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) {
                return new ByteArrayInputStream(new byte[0]);
            }
        };
        assertEquals(Status.SOURCE_CHANGED, capture(shortRead, config(false), pair("left.txt", "right.txt")).status());
        var failed = new TrackedAccess() {
            @Override public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) throws IOException {
                throw new IOException(PRIVATE_PROSE);
            }
        };
        var result = capture(failed, config(false), pair("left.txt", "right.txt"));
        assertEquals(Status.READ_FAILED, result.status());
        assertNull(result.pair());
        assertFalse(result.detail().contains(PRIVATE_PROSE));
    }

    @Test
    void cancellationBeforeAndDuringReadRetainsInterruptAndNoPair() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var access = new TrackedAccess();
        try {
            Thread.currentThread().interrupt();
            assertEquals(Status.CANCELLED, capture(access, config(false), pair("left.txt", "right.txt")).status());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(access.opened.isEmpty());
        } finally {
            Thread.interrupted();
        }
        var interrupting = new TrackedAccess() {
            @Override public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) throws IOException {
                var stream = super.open(root, path);
                Thread.currentThread().interrupt();
                return stream;
            }
        };
        try {
            var result = capture(interrupting, config(false), pair("left.txt", "right.txt"));
            assertEquals(Status.CANCELLED, result.status());
            assertNull(result.pair());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("left.txt"), interrupting.opened);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionAfterActualBytesClosesTheStreamAndDiscardsThePair() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var closed = new AtomicBoolean();
        var consumed = new AtomicInteger();
        var access = new TrackedAccess() {
            @Override public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) throws IOException {
                return new java.io.FilterInputStream(super.open(root, path)) {
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        int count = super.read(buffer, offset, length);
                        if (count > 0) consumed.addAndGet(count);
                        Thread.currentThread().interrupt();
                        return count;
                    }
                    @Override public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
            }
        };
        try {
            var result = capture(access, config(false), pair("left.txt", "right.txt"));
            assertEquals(Status.CANCELLED, result.status());
            assertNull(result.pair());
            assertTrue(consumed.get() > 0);
            assertTrue(closed.get());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("left.txt"), access.opened);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void optionalFileKeyChangeIsDetectedWhenSuppliedByTheProvider() throws Exception {
        text("left.txt", "left");
        text("right.txt", "right");
        var changed = new AtomicBoolean();
        var access = new TrackedAccess() {
            @Override public BasicFileAttributes attributes(Path path) throws IOException {
                var base = super.attributes(path);
                String key = path.endsWith("left.txt") && changed.get() ? "replacement-key" : "original-key";
                return new BasicFileAttributes() {
                    public FileTime lastModifiedTime() { return base.lastModifiedTime(); }
                    public FileTime lastAccessTime() { return base.lastAccessTime(); }
                    public FileTime creationTime() { return base.creationTime(); }
                    public boolean isRegularFile() { return base.isRegularFile(); }
                    public boolean isDirectory() { return base.isDirectory(); }
                    public boolean isSymbolicLink() { return base.isSymbolicLink(); }
                    public boolean isOther() { return base.isOther(); }
                    public long size() { return base.size(); }
                    public Object fileKey() { return key; }
                };
            }
            @Override public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) throws IOException {
                if (path.endsWith("right.txt")) changed.set(true);
                return super.open(root, path);
            }
        };
        var result = capture(access, config(false), pair("left.txt", "right.txt"));
        assertEquals(Status.SOURCE_CHANGED, result.status());
        assertNull(result.pair());
    }

    private Result capture(TrackedAccess access, Config cfg, RequestedPair targets) {
        return service(access).capture(workspace, sandbox(cfg), cfg, targets, targets);
    }
    private DocumentPairAdmission service(TrackedAccess access) { return new DocumentPairAdmission(access); }
    private Sandbox sandbox(Config cfg) { return new Sandbox(workspace, cfg.data); }
    private static RequestedPair pair(String left, String right) { return new RequestedPair(left, right); }
    private void text(String name, String value) throws IOException { Files.writeString(workspace.resolve(name), value); }
    private static Config config(boolean privateMode) {
        var cfg = new Config(null);
        cfg.data.put("document_extraction", Map.of("enabled", true, "pdf", Map.of("enabled", true), "word", Map.of("enabled", true)));
        cfg.data.put("privacy", Map.of("mode", privateMode ? "private" : "developer"));
        return cfg;
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] document(String extension) throws IOException {
        var bytes = new ByteArrayOutputStream();
        if (extension.equals("docx")) {
            try (var document = new XWPFDocument()) {
                document.createParagraph().createRun().setText(PRIVATE_PROSE);
                document.write(bytes);
            }
        } else {
            try (var document = new PDDocument()) {
                var page = new PDPage();
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(20, 700);
                    content.showText(PRIVATE_PROSE);
                    content.endText();
                }
                document.save(bytes);
            }
        }
        return bytes.toByteArray();
    }
    private static class TrackedAccess implements Access {
        final List<String> opened = new ArrayList<>();
        final List<String> beforeFirstOpen = new ArrayList<>();
        @Override public BasicFileAttributes attributes(Path path) throws IOException {
            if (opened.isEmpty()) beforeFirstOpen.add(path.getFileName().toString());
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        @Override public InputStream open(NoFollowPathPolicy.GuardedRoot root, Path path) throws IOException {
            opened.add(path.getFileName().toString());
            return root.openRegularFile(path);
        }
    }
}
