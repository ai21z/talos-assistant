package dev.talos.core.diff;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static dev.talos.core.diff.DocumentTextDiff.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentTextDiffTest {
    @Test void reconstructsAnExactReplacementWithOrderedChanges() {
        Snapshot left = snapshot("first\nsecond\nthird\nlast\n");
        Snapshot right = snapshot("inserted\nfirst\nSECOND\nlast\ntrailing");
        Result result = compare(left, right);
        assertTrue(result.complete());
        assertEquals(Equality.DIFFERENT, result.rawEquality());
        assertEquals(Equality.DIFFERENT, result.textEquality());
        assertEquals(right.text(), reconstruct(left, right, result));
        assertTrue(result.hunks().stream().anyMatch(hunk -> !hunk.removed().isEmpty()));
        assertTrue(result.hunks().stream().anyMatch(hunk -> !hunk.added().isEmpty()));
        assertEquals(result.addedLines(), result.hunks().stream().mapToInt(hunk -> hunk.added().size()).sum());
        assertEquals(result.removedLines(), result.hunks().stream().mapToInt(hunk -> hunk.removed().size()).sum());
    }

    @Test void identicalAndEmptyDocumentsHaveCompleteExactIdentity() {
        for (String text : List.of("", "a", "a\n", "\n", "\r\n", "\uFEFF")) {
            Snapshot value = snapshot(text);
            Result result = compare(value, snapshot(text));
            assertTrue(result.complete(), text);
            assertEquals(Equality.EQUAL, result.rawEquality());
            assertEquals(Equality.EQUAL, result.textEquality());
            assertEquals(Equality.EQUAL, result.formatEquality());
            assertEquals(Boolean.FALSE, result.bomChanged());
            assertEquals(0, result.addedLines());
            assertEquals(0, result.removedLines());
            assertTrue(result.hunks().isEmpty());
            assertEquals(text, reconstructedSnapshot(value));
        }
        assertTrue(snapshot("").lines().isEmpty());
        assertEquals(1, snapshot("\n").lines().size());
    }

    @Test void insertionAndDeletionAgainstEmptyDocumentsAreExact() {
        for (String text : List.of("a", "a\n", "\n\n", "a\r\nb\rc\n")) {
            Snapshot empty = snapshot("");
            Snapshot populated = snapshot(text);
            Result forward = compare(empty, populated);
            Result backward = compare(populated, empty);
            assertEquals(text, reconstruct(empty, populated, forward));
            assertEquals("", reconstruct(populated, empty, backward));
            assertEquals(forward.addedLines(), backward.removedLines());
            assertEquals(0, forward.removedLines());
            assertEquals(0, backward.addedLines());
        }
    }

    @Test void newlineChangesAreNotSilentlyNormalized() {
        List<String> versions = List.of("one\ntwo\n", "one\r\ntwo\r\n", "one\rtwo\r", "one\ntwo");
        for (String before : versions) for (String after : versions) {
            Snapshot left = snapshot(before), right = snapshot(after);
            Result result = compare(left, right);
            assertEquals(before.equals(after) ? Equality.EQUAL : Equality.DIFFERENT, result.textEquality());
            assertEquals(before.equals(after) ? Equality.EQUAL : Equality.DIFFERENT, result.formatEquality());
            assertEquals(after, reconstruct(left, right, result));
        }
    }

    @Test void mixedTerminatorOrderIsNotReducedToCounts() {
        Result result = compare(snapshot("a\nb\r\n"), snapshot("a\r\nb\n"));
        assertTrue(result.complete());
        assertEquals(Equality.DIFFERENT, result.formatEquality());
        assertEquals(2, result.removedLines());
        assertEquals(2, result.addedLines());
    }

    @Test void bomOnlyChangesArePreservedInLineHunksAndFormatMetadata() {
        Snapshot left = snapshot("a\n"), right = snapshot("\uFEFFa\n");
        Result result = compare(left, right);
        assertTrue(result.complete());
        assertEquals(Equality.DIFFERENT, result.rawEquality());
        assertEquals(Equality.DIFFERENT, result.textEquality());
        assertEquals(Equality.DIFFERENT, result.formatEquality());
        assertEquals(Boolean.TRUE, result.bomChanged());
        assertEquals(1, result.hunks().size());
        assertEquals("\uFEFFa", result.hunks().getFirst().added().getFirst().text());
        assertEquals(right.text(), reconstruct(left, right, result));
        assertNotEquals(left.textSha256(), right.textSha256());
        assertTrue(right.utf8Bom());
    }

    @Test void mixedRepresentationsAlwaysReconstructTheirExactTextIncludingBom() {
        for (String leftText : List.of("", "x", "\uFEFFx", "\uFEFF")) {
            for (String rightText : List.of("", "x", "\uFEFFx", "\uFEFF")) {
                Snapshot plain = snapshot(leftText);
                Snapshot projected = extracted(new byte[] {1}, rightText, provenance(true, false)).snapshot();
                Result forward = compare(plain, projected), reverse = compare(projected, plain);
                assertEquals(rightText, reconstruct(plain, projected, forward));
                assertEquals(leftText, reconstruct(projected, plain, reverse));
                assertEquals(leftText.equals(rightText) ? Equality.EQUAL : Equality.DIFFERENT, forward.textEquality());
                assertEquals(Equality.NOT_APPLICABLE, forward.formatEquality());
                assertNull(forward.bomChanged());
                assertEquals(leftText.equals(rightText), forward.hunks().isEmpty());
            }
        }
    }

    @Test void contentChangesCanKeepTheSameFormat() {
        Result result = compare(snapshot("alpha\nbeta\n"), snapshot("ALPHA\nBETA\n"));
        assertEquals(Equality.DIFFERENT, result.textEquality());
        assertEquals(Equality.EQUAL, result.formatEquality());
    }

    @Test void unicodeAndWhitespaceAreNotNormalized() {
        for (String text : List.of("a\t \n\n", "\uD83D\uDE80\n", "e\u0301\n", "\u00e9\n", "a\uFEFFb\n")) {
            Snapshot value = snapshot(text);
            assertEquals(text, value.text());
            assertEquals(text, reconstructedSnapshot(value));
        }
        assertEquals(Equality.DIFFERENT, compare(snapshot("e\u0301"), snapshot("\u00e9")).textEquality());
        assertEquals(Equality.DIFFERENT, compare(snapshot("x \n"), snapshot("x\n")).textEquality());
    }

    @Test void repeatedLinesAndReversalRemainDeterministicAndReconstructable() {
        Snapshot left = snapshot("same\nx\nsame\ny\nsame\n");
        Snapshot right = snapshot("same\ny\nsame\nx\nsame\n");
        Result first = compare(left, right);
        for (int i = 0; i < 8; i++) assertEquals(first, compare(left, right));
        assertEquals(right.text(), reconstruct(left, right, first));
        Result reverse = compare(right, left);
        assertEquals(left.text(), reconstruct(right, left, reverse));
        assertEquals(first.addedLines(), reverse.removedLines());
    }

    @Test void snapshotBytesAndListsAreDefensivelyRetained() {
        byte[] bytes = "original\n".getBytes(StandardCharsets.UTF_8);
        Snapshot snapshot = utf8(bytes).snapshot();
        String hash = snapshot.rawSha256();
        bytes[0] = 'X';
        assertEquals("original\n", snapshot.text());
        assertEquals(hash, snapshot.rawSha256());
        assertEquals(Equality.EQUAL, compare(snapshot, snapshot("original\n")).rawEquality());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.lines().clear());
        Result result = compare(snapshot, snapshot("changed\n"));
        assertThrows(UnsupportedOperationException.class, () -> result.hunks().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.hunks().getFirst().added().clear());
        byte[] extractedBytes = {1, 2};
        Snapshot projected = extracted(extractedBytes, "projection", provenance(true, false)).snapshot();
        extractedBytes[0] = 9;
        assertEquals(Equality.EQUAL, compare(projected,
                extracted(new byte[] {1, 2}, "projection", provenance(true, false)).snapshot()).rawEquality());
    }

    @Test void rawIdentityAndExtractedTextIdentityAreDifferentClaims() {
        Snapshot left = extracted(new byte[] {1, 2}, "same\n", provenance(true, false)).snapshot();
        Snapshot right = extracted(new byte[] {3, 4}, "same\n", provenance(true, false)).snapshot();
        Result result = compare(left, right);
        assertEquals(Equality.DIFFERENT, result.rawEquality());
        assertEquals(Equality.EQUAL, result.textEquality());
        assertEquals(Equality.NOT_APPLICABLE, result.formatEquality());
        assertNull(result.bomChanged());
        assertEquals(left.textSha256(), right.textSha256());
        assertNotEquals(left.rawSha256(), right.rawSha256());
        Snapshot differentProjection = extracted(new byte[] {1, 2}, "changed\n", provenance(true, false)).snapshot();
        Result changed = compare(left, differentProjection);
        assertEquals(Equality.EQUAL, changed.rawEquality());
        assertEquals(Equality.DIFFERENT, changed.textEquality());
        assertEquals(differentProjection.text(), reconstruct(left, differentProjection, changed));
    }

    @Test void extractedLeadingBomCharacterIsDataNotOriginalDocumentFormat() {
        Snapshot value = extracted(new byte[] {1}, "\uFEFFprojected\n", provenance(true, false)).snapshot();
        assertFalse(value.utf8Bom());
        assertEquals("\uFEFFprojected", value.lines().getFirst().text());
        assertEquals(value.text(), reconstructedSnapshot(value));
        assertEquals(Equality.NOT_APPLICABLE, compare(snapshot(value.text()), value).formatEquality());
    }

    @Test void extractionWarningsArePreservedWithoutClaimingCompleteOriginalDocumentCoverage() {
        List<String> warnings = new ArrayList<>(List.of("layout omitted"));
        ExtractionProvenance metadata = new ExtractionProvenance("pdfbox", "3", "policy-v1", warnings, true, false);
        Snapshot value = extracted(new byte[] {1}, "text", metadata).snapshot();
        warnings.clear();
        assertEquals(List.of("layout omitted"), value.extraction().warnings());
        assertThrows(UnsupportedOperationException.class, () -> value.extraction().warnings().clear());
        assertEquals(Representation.EXTRACTED_DOCUMENT_TEXT, value.representation());
    }

    @Test void incompleteOrRedactedExtractionProducesNoUsableSnapshot() {
        for (ExtractionProvenance metadata : List.of(provenance(false, false), provenance(true, true))) {
            SnapshotResult result = extracted(new byte[] {1}, "text", metadata);
            assertEquals(Status.INCOMPLETE_INPUT, result.status());
            assertNull(result.snapshot());
        }
    }

    @Test void malformedEncodingAndNonTextNulFailExplicitly() {
        for (byte[] bytes : List.of(new byte[] {(byte) 0xc3, 0x28},
                new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, new byte[] {(byte) 0xff, (byte) 0xfe})) {
            assertEquals(Status.INVALID_ENCODING, utf8(bytes).status());
            assertNull(utf8(bytes).snapshot());
        }
        assertEquals(Status.INVALID_ENCODING, extracted(new byte[] {1}, "\ud800", provenance(true, false)).status());
        assertEquals(Status.INVALID_ENCODING, extracted(new byte[] {1}, "\udc00", provenance(true, false)).status());
        assertEquals(Status.UNSUPPORTED_TEXT, utf8(new byte[] {0}).status());
        assertEquals(Status.UNSUPPORTED_TEXT, extracted(new byte[] {1}, "a\0b", provenance(true, false)).status());
    }

    @Test void invalidFactoryInputsAndMetadataAreNotSuccessfulSnapshots() {
        assertEquals(Status.INVALID_INPUT, utf8(null).status());
        assertEquals(Status.INVALID_INPUT, extracted(null, "text", provenance(true, false)).status());
        assertEquals(Status.INVALID_INPUT, extracted(new byte[0], null, provenance(true, false)).status());
        assertEquals(Status.INVALID_INPUT, extracted(new byte[0], "text", null).status());
        ExtractionProvenance missing = new ExtractionProvenance("", "1", "v1", List.of(), true, false);
        assertEquals(Status.INVALID_INPUT, extracted(new byte[0], "text", missing).status());
        ExtractionProvenance huge = new ExtractionProvenance("pdf", "1", "v1", List.of("x".repeat(8193)), true, false);
        assertEquals(Status.INVALID_INPUT, extracted(new byte[0], "text", huge).status());
        assertThrows(IllegalArgumentException.class, () -> new Limits(-1, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(Integer.MAX_VALUE, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, Integer.MAX_VALUE, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, Integer.MAX_VALUE, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, Long.MAX_VALUE, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 1, Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 1, 1, Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 1, 1, 1, Integer.MAX_VALUE));
        assertEquals(DEFAULT_LIMITS, Limits.defaults());
    }

    @Test void inputByteCharacterAndLineLimitsAreIndependentAndInclusive() {
        Limits tiny = new Limits(4, 3, 1, 100, 100, 10, 100);
        assertTrue(utf8("a\r\n".getBytes(StandardCharsets.UTF_8), tiny).complete());
        assertEquals(Status.INPUT_LIMIT, utf8("abcde".getBytes(StandardCharsets.UTF_8), tiny).status());
        assertEquals(Status.INPUT_LIMIT, utf8("abcd".getBytes(StandardCharsets.UTF_8), tiny).status());
        assertEquals(Status.INPUT_LIMIT, utf8("a\nb".getBytes(StandardCharsets.UTF_8), tiny).status());
        assertEquals(Status.INPUT_LIMIT, extracted(new byte[5], "x", provenance(true, false), tiny).status());
        assertEquals(Status.INPUT_LIMIT, extracted(new byte[1], "abcd", provenance(true, false), tiny).status());
        assertFailed(compare(snapshot("one\ntwo\n"), snapshot("x"), tiny), Status.INPUT_LIMIT);
    }

    @Test void comparisonCharacterBudgetFailsBeforeAnOverBudgetEquality() {
        Limits tight = limits(100, 3, 10, 100);
        Result result = compare(snapshot("abc"), snapshot("abd"), tight);
        assertFailed(result, Status.WORK_LIMIT);
        assertEquals(0, result.work().characterComparisons());
        assertTrue(compare(snapshot("abc"), snapshot("abc"), limits(0, 4, 0, 0)).complete());
    }

    @Test void frontierBudgetFailsSynchronouslyWithoutReturningPartialDeltas() {
        Result result = compare(snapshot("a\n"), snapshot("b\n"), limits(1, 100, 10, 100));
        assertFailed(result, Status.WORK_LIMIT);
        assertEquals(1, result.work().frontierSteps());
        assertTrue(result.work().characterComparisons() <= 100);
    }

    @Test void exactOutputBudgetIncludesChangedTerminators() {
        Snapshot left = snapshot("a\n"), right = snapshot("b\n");
        assertTrue(compare(left, right, limits(100, 100, 1, 4)).complete());
        assertFailed(compare(left, right, limits(100, 100, 1, 3)), Status.OUTPUT_LIMIT);
        assertFailed(compare(left, right, limits(100, 100, 0, 100)), Status.OUTPUT_LIMIT);
        assertFailed(compare(snapshot("a"), snapshot("\uFEFFa"), limits(100, 100, 1, 0)), Status.OUTPUT_LIMIT);
    }

    @Test void interruptedCallsPreserveTheInterruptFlagAndMakeNoSuccessClaim() {
        Snapshot left = snapshot("a"), right = snapshot("b");
        Thread.currentThread().interrupt();
        try {
            assertFailed(compare(left, right), Status.CANCELLED);
            assertEquals(Status.CANCELLED, utf8(new byte[0]).status());
            assertEquals(Status.CANCELLED, extracted(new byte[0], "", provenance(true, false)).status());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void absentSnapshotsAndForgedSuccessShapesAreRejected() {
        assertFailed(compare(null, snapshot("")), Status.INVALID_INPUT);
        assertThrows(IllegalArgumentException.class, () -> new SnapshotResult(Status.COMPLETE, null));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotResult(Status.INPUT_LIMIT, snapshot("")));
        assertThrows(IllegalArgumentException.class, () -> new Result(Status.WORK_LIMIT, Equality.EQUAL,
                Equality.UNESTABLISHED, Equality.UNESTABLISHED, null, List.of(), null, null, new Work(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Line("bad\nline", Terminator.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Hunk(-1, 0, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Work(-1, 0));
    }

    @Test void diagnosticStringsDoNotExposeSourceOrExtractedContent() {
        String secret = "distinctive private content";
        Snapshot left = snapshot(secret), right = snapshot("replacement private content");
        assertFalse(left.toString().contains(secret));
        assertFalse(utf8(secret.getBytes(StandardCharsets.UTF_8)).toString().contains(secret));
        assertFalse(left.lines().toString().contains(secret));
        assertFalse(compare(left, right).toString().contains(secret));
        assertFalse(new ExtractionProvenance(secret, secret, secret, List.of(secret), true, false).toString().contains(secret));
    }

    private static Snapshot snapshot(String value) {
        SnapshotResult result = utf8(value.getBytes(StandardCharsets.UTF_8));
        assertEquals(Status.COMPLETE, result.status());
        return result.snapshot();
    }

    private static ExtractionProvenance provenance(boolean complete, boolean redacted) {
        return new ExtractionProvenance("adapter", "1", "policy-v1", List.of(), complete, redacted);
    }

    private static Limits limits(long frontier, long chars, int hunks, int output) {
        return new Limits(1024, 1024, 100, frontier, chars, hunks, output);
    }

    private static void assertFailed(Result result, Status status) {
        assertEquals(status, result.status());
        assertFalse(result.complete());
        assertEquals(Equality.UNESTABLISHED, result.rawEquality());
        assertEquals(Equality.UNESTABLISHED, result.textEquality());
        assertEquals(Equality.UNESTABLISHED, result.formatEquality());
        assertNull(result.bomChanged());
        assertNull(result.addedLines());
        assertNull(result.removedLines());
        assertTrue(result.hunks().isEmpty());
    }

    private static String reconstructedSnapshot(Snapshot snapshot) {
        return snapshot.lines().stream().map(Line::exactText).collect(java.util.stream.Collectors.joining());
    }

    private static String reconstruct(Snapshot left, Snapshot right, Result result) {
        assertTrue(result.complete(), result.toString());
        List<Line> lines = new ArrayList<>();
        int cursor = 0;
        for (Hunk hunk : result.hunks()) {
            assertTrue(hunk.beforeStart() >= cursor);
            lines.addAll(left.lines().subList(cursor, hunk.beforeStart()));
            assertEquals(hunk.afterStart(), lines.size());
            assertEquals(left.lines().subList(hunk.beforeStart(), hunk.beforeStart() + hunk.removed().size()), hunk.removed());
            lines.addAll(hunk.added());
            cursor = hunk.beforeStart() + hunk.removed().size();
        }
        lines.addAll(left.lines().subList(cursor, left.lines().size()));
        assertEquals(right.lines(), lines);
        return lines.stream().map(Line::exactText).collect(java.util.stream.Collectors.joining());
    }
}
