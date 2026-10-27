package dev.talos.core.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.algorithm.myers.MeyersDiff;
import dev.talos.core.io.StrictUtf8;

import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Exact snapshot comparison without file access, extraction, or execution authority. */
public final class DocumentTextDiff {
    public static final String VERSION = "document-text-diff-v1";
    public static final Limits DEFAULT_LIMITS = new Limits(
            2 * 1024 * 1024, 64_000, 4_096, 100_000, 8_000_000, 512, 128_000);

    public enum Status {
        COMPLETE, INVALID_INPUT, INVALID_ENCODING, UNSUPPORTED_TEXT, INCOMPLETE_INPUT,
        INPUT_LIMIT, WORK_LIMIT, OUTPUT_LIMIT, CANCELLED
    }

    public enum Equality { EQUAL, DIFFERENT, UNESTABLISHED, NOT_APPLICABLE }
    public enum Representation { EXACT_UTF8_TEXT, EXTRACTED_DOCUMENT_TEXT }
    public enum Terminator {
        NONE(""), LF("\n"), CRLF("\r\n"), CR("\r");
        private final String text;
        Terminator(String text) { this.text = text; }
        public String text() { return text; }
    }

    /** Limits can tighten the fixed ceilings. Output characters count exact changed payload, not renderer framing. */
    public record Limits(int maxSourceBytes, int maxTextChars, int maxLines,
                         long maxFrontierSteps, long maxCharacterComparisons,
                         int maxHunks, int maxOutputChars) {
        public Limits {
            if (maxSourceBytes < 0 || maxSourceBytes > 2 * 1024 * 1024
                    || maxTextChars < 0 || maxTextChars > 64_000 || maxLines < 0 || maxLines > 4_096
                    || maxFrontierSteps < 0 || maxFrontierSteps > 100_000
                    || maxCharacterComparisons < 0 || maxCharacterComparisons > 8_000_000
                    || maxHunks < 0 || maxHunks > 512 || maxOutputChars < 0 || maxOutputChars > 128_000)
                throw new IllegalArgumentException("Comparison limits are outside the supported bounds.");
        }
        public static Limits defaults() { return DEFAULT_LIMITS; }
    }

    /** Completeness and redaction are supplied by the owning extraction boundary. */
    public record ExtractionProvenance(String adapterName, String adapterVersion, String policyVersion,
                                       List<String> warnings, boolean complete, boolean redacted) {
        public ExtractionProvenance {
            Objects.requireNonNull(adapterName);
            Objects.requireNonNull(adapterVersion);
            Objects.requireNonNull(policyVersion);
            warnings = List.copyOf(warnings);
        }
        @Override public String toString() {
            return "ExtractionProvenance[warningCount=" + warnings.size()
                    + ", complete=" + complete + ", redacted=" + redacted + "]";
        }
    }

    /** A token retains every original character, including a leading BOM and its exact line terminator. */
    public record Line(String text, Terminator terminator) {
        public Line {
            Objects.requireNonNull(text);
            Objects.requireNonNull(terminator);
            if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Line text must not contain a terminator.");
        }
        public String exactText() { return text + terminator.text(); }
        @Override public String toString() {
            return "Line[chars=" + text.length() + ", terminator=" + terminator + "]";
        }
    }

    /** Positions are zero-based line offsets in the original snapshots. */
    public record Hunk(int beforeStart, int afterStart, List<Line> removed, List<Line> added) {
        public Hunk {
            if (beforeStart < 0 || afterStart < 0)
                throw new IllegalArgumentException("Hunk positions must be nonnegative.");
            removed = List.copyOf(removed);
            added = List.copyOf(added);
        }
        @Override public String toString() {
            return "Hunk[beforeStart=" + beforeStart + ", afterStart=" + afterStart
                    + ", removedLines=" + removed.size() + ", addedLines=" + added.size() + "]";
        }
    }

    public record Work(long frontierSteps, long characterComparisons) {
        public Work {
            if (frontierSteps < 0 || characterComparisons < 0)
                throw new IllegalArgumentException("Work counts must be nonnegative.");
        }
    }

    public record SnapshotResult(Status status, Snapshot snapshot) {
        public SnapshotResult {
            Objects.requireNonNull(status);
            if ((status == Status.COMPLETE) != (snapshot != null))
                throw new IllegalArgumentException("Only a complete snapshot result carries a snapshot.");
        }
        public boolean complete() { return status == Status.COMPLETE; }
    }

    /** Failed results carry no established equalities, counts, hunks, or BOM conclusion. */
    public record Result(Status status, Equality rawEquality, Equality textEquality, Equality formatEquality,
                         Boolean bomChanged, List<Hunk> hunks, Integer addedLines, Integer removedLines,
                         Work work) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(rawEquality);
            Objects.requireNonNull(textEquality);
            Objects.requireNonNull(formatEquality);
            Objects.requireNonNull(work);
            hunks = List.copyOf(hunks);
            if (status != Status.COMPLETE && (rawEquality != Equality.UNESTABLISHED
                    || textEquality != Equality.UNESTABLISHED || formatEquality != Equality.UNESTABLISHED
                    || bomChanged != null || !hunks.isEmpty() || addedLines != null || removedLines != null))
                throw new IllegalArgumentException("A failed comparison cannot establish a diff.");
            if (status == Status.COMPLETE && (addedLines == null || removedLines == null
                    || addedLines < 0 || removedLines < 0 || rawEquality == Equality.UNESTABLISHED
                    || textEquality == Equality.UNESTABLISHED || formatEquality == Equality.UNESTABLISHED))
                throw new IllegalArgumentException("A complete comparison requires established results.");
        }
        public boolean complete() { return status == Status.COMPLETE; }
    }

    public static final class Snapshot {
        private final byte[] rawBytes;
        private final String rawSha256;
        private final String text;
        private final String textSha256;
        private final Representation representation;
        private final boolean utf8Bom;
        private final List<Line> lines;
        private final ExtractionProvenance extraction;

        private Snapshot(byte[] ownedBytes, String text, byte[] encodedText, Representation representation,
                         boolean utf8Bom, List<Line> lines, ExtractionProvenance extraction) {
            this.rawBytes = ownedBytes;
            this.rawSha256 = hash(ownedBytes);
            this.text = text;
            this.textSha256 = hash(encodedText);
            this.representation = representation;
            this.utf8Bom = utf8Bom;
            this.lines = List.copyOf(lines);
            this.extraction = extraction;
        }
        public int rawByteLength() { return rawBytes.length; }
        public String rawSha256() { return rawSha256; }
        /** Exact decoded or extracted text, including all original text terminators and any BOM character. */
        public String text() { return text; }
        public String textSha256() { return textSha256; }
        public Representation representation() { return representation; }
        public boolean utf8Bom() { return utf8Bom; }
        public List<Line> lines() { return lines; }
        /** Absent for an ordinary UTF-8 snapshot. */
        public ExtractionProvenance extraction() { return extraction; }
        @Override public String toString() {
            return "Snapshot[representation=" + representation + ", rawBytes=" + rawBytes.length
                    + ", textChars=" + text.length() + ", lines=" + lines.size() + "]";
        }
    }

    private DocumentTextDiff() { }

    public static SnapshotResult utf8(byte[] bytes) { return utf8(bytes, DEFAULT_LIMITS); }

    public static SnapshotResult utf8(byte[] bytes, Limits limits) {
        Objects.requireNonNull(limits);
        if (Thread.currentThread().isInterrupted()) return unavailable(Status.CANCELLED);
        if (bytes == null) return unavailable(Status.INVALID_INPUT);
        if (bytes.length > limits.maxSourceBytes()) return unavailable(Status.INPUT_LIMIT);
        byte[] owned = bytes.clone();
        try {
            String text = StrictUtf8.decode(owned);
            if (text.indexOf('\0') >= 0) return unavailable(Status.UNSUPPORTED_TEXT);
            boolean bom = owned.length >= 3 && owned[0] == (byte) 0xef
                    && owned[1] == (byte) 0xbb && owned[2] == (byte) 0xbf;
            return snapshot(owned, text, owned, Representation.EXACT_UTF8_TEXT, bom, null, limits);
        } catch (CharacterCodingException invalid) {
            return unavailable(Status.INVALID_ENCODING);
        }
    }

    public static SnapshotResult extracted(byte[] sourceBytes, String extractedText,
                                            ExtractionProvenance provenance) {
        return extracted(sourceBytes, extractedText, provenance, DEFAULT_LIMITS);
    }

    /** The caller must extract from these same bytes and supply truthful projection metadata. */
    public static SnapshotResult extracted(byte[] sourceBytes, String extractedText,
                                            ExtractionProvenance provenance, Limits limits) {
        Objects.requireNonNull(limits);
        if (Thread.currentThread().isInterrupted()) return unavailable(Status.CANCELLED);
        if (sourceBytes == null || extractedText == null || provenance == null
                || !validProvenance(provenance)) return unavailable(Status.INVALID_INPUT);
        if (!provenance.complete() || provenance.redacted()) return unavailable(Status.INCOMPLETE_INPUT);
        if (sourceBytes.length > limits.maxSourceBytes() || extractedText.length() > limits.maxTextChars())
            return unavailable(Status.INPUT_LIMIT);
        try {
            byte[] encoded = StrictUtf8.encode(extractedText);
            if (extractedText.indexOf('\0') >= 0) return unavailable(Status.UNSUPPORTED_TEXT);
            return snapshot(sourceBytes.clone(), extractedText, encoded,
                    Representation.EXTRACTED_DOCUMENT_TEXT, false, provenance, limits);
        } catch (CharacterCodingException invalid) {
            return unavailable(Status.INVALID_ENCODING);
        }
    }

    private static boolean validProvenance(ExtractionProvenance value) {
        if (value.adapterName().isBlank() || value.adapterName().length() > 256
                || value.adapterVersion().isBlank() || value.adapterVersion().length() > 256
                || value.policyVersion().isBlank() || value.policyVersion().length() > 256
                || value.warnings().size() > 64) return false;
        long chars = 0;
        for (String warning : value.warnings()) {
            chars += warning.length();
            if (chars > 8_192) return false;
        }
        return true;
    }

    private static SnapshotResult snapshot(byte[] owned, String text, byte[] encoded,
                                            Representation representation, boolean bom,
                                            ExtractionProvenance provenance, Limits limits) {
        try {
            if (text.length() > limits.maxTextChars()) return unavailable(Status.INPUT_LIMIT);
            List<Line> lines = lines(text, limits.maxLines());
            interrupted();
            return new SnapshotResult(Status.COMPLETE,
                    new Snapshot(owned, text, encoded, representation, bom, lines, provenance));
        } catch (Stopped stopped) {
            return unavailable(stopped.status);
        }
    }

    private static List<Line> lines(String text, int maximum) {
        List<Line> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < text.length()) {
            interrupted();
            if (result.size() == maximum) throw new Stopped(Status.INPUT_LIMIT);
            int end = cursor;
            while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') {
                if ((end & 1023) == 0) interrupted();
                end++;
            }
            Terminator terminator = Terminator.NONE;
            int next = end;
            if (end < text.length()) {
                if (text.charAt(end) == '\n') terminator = Terminator.LF;
                else if (end + 1 < text.length() && text.charAt(end + 1) == '\n') terminator = Terminator.CRLF;
                else terminator = Terminator.CR;
                next += terminator.text().length();
            }
            result.add(new Line(text.substring(cursor, end), terminator));
            cursor = next;
        }
        return result;
    }

    public static Result compare(Snapshot left, Snapshot right) { return compare(left, right, DEFAULT_LIMITS); }

    public static Result compare(Snapshot left, Snapshot right, Limits limits) {
        Objects.requireNonNull(limits);
        Budget budget = new Budget(limits);
        try {
            interrupted();
            if (left == null || right == null) return failed(Status.INVALID_INPUT, budget);
            if (!within(left, limits) || !within(right, limits)) return failed(Status.INPUT_LIMIT, budget);
            boolean sameText = budget.sameText(left.text, right.text);
            boolean formatApplicable = left.representation == Representation.EXACT_UTF8_TEXT
                    && right.representation == Representation.EXACT_UTF8_TEXT;
            Boolean bomChanged = formatApplicable ? left.utf8Bom != right.utf8Bom : null;
            boolean sameFormat = formatApplicable && sameFormat(left, right);
            List<Hunk> hunks = new ArrayList<>();
            int added = 0, removed = 0;
            long outputChars = 0;
            if (!sameText) {
                var patch = DiffUtils.diff(left.lines, right.lines,
                        new MeyersDiff<Line>(budget::sameLine), budget);
                var deltas = patch.getDeltas().stream()
                        .sorted(Comparator.comparingInt((com.github.difflib.patch.AbstractDelta<Line> delta)
                                -> delta.getSource().getPosition()).thenComparingInt(delta -> delta.getTarget().getPosition()))
                        .toList();
                if (deltas.size() > limits.maxHunks()) throw new Stopped(Status.OUTPUT_LIMIT);
                for (var delta : deltas) {
                    interrupted();
                    List<Line> before = delta.getSource().getLines();
                    List<Line> after = delta.getTarget().getLines();
                    outputChars += payloadSize(before) + payloadSize(after);
                    if (outputChars > limits.maxOutputChars()) throw new Stopped(Status.OUTPUT_LIMIT);
                    removed += before.size();
                    added += after.size();
                    hunks.add(new Hunk(delta.getSource().getPosition(), delta.getTarget().getPosition(), before, after));
                }
            }
            interrupted();
            return new Result(Status.COMPLETE, equality(Arrays.equals(left.rawBytes, right.rawBytes)),
                    equality(sameText), formatApplicable ? equality(sameFormat) : Equality.NOT_APPLICABLE,
                    bomChanged, hunks, added, removed, budget.work());
        } catch (Stopped stopped) {
            return failed(stopped.status, budget);
        }
    }

    private static boolean within(Snapshot value, Limits limits) {
        return value.rawBytes.length <= limits.maxSourceBytes() && value.text.length() <= limits.maxTextChars()
                && value.lines.size() <= limits.maxLines()
                && (long) value.lines.size() * 4 + 3 <= Integer.MAX_VALUE;
    }

    private static boolean sameFormat(Snapshot left, Snapshot right) {
        if (left.utf8Bom != right.utf8Bom || left.lines.size() != right.lines.size()) return false;
        for (int i = 0; i < left.lines.size(); i++) {
            interrupted();
            if (left.lines.get(i).terminator() != right.lines.get(i).terminator()) return false;
        }
        return true;
    }

    private static long payloadSize(List<Line> lines) {
        long chars = 0;
        for (Line line : lines) {
            interrupted();
            chars += (long) line.text().length() + line.terminator().text().length();
        }
        return chars;
    }

    private static SnapshotResult unavailable(Status status) { return new SnapshotResult(status, null); }
    private static Equality equality(boolean equal) { return equal ? Equality.EQUAL : Equality.DIFFERENT; }
    private static Result failed(Status status, Budget budget) {
        return new Result(status, Equality.UNESTABLISHED, Equality.UNESTABLISHED, Equality.UNESTABLISHED,
                null, List.of(), null, null, budget.work());
    }
    private static void interrupted() {
        if (Thread.currentThread().isInterrupted()) throw new Stopped(Status.CANCELLED);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }

    private static final class Budget implements DiffAlgorithmListener {
        private final Limits limits;
        private long frontiers;
        private long characters;
        private Budget(Limits limits) { this.limits = limits; }
        private boolean sameText(String left, String right) {
            interrupted();
            long charge = 1L + Math.min(left.length(), right.length());
            if (charge > limits.maxCharacterComparisons() - characters) throw new Stopped(Status.WORK_LIMIT);
            characters += charge;
            return left.equals(right);
        }
        private boolean sameLine(Line left, Line right) {
            boolean textEqual = sameText(left.text(), right.text());
            return textEqual && left.terminator() == right.terminator();
        }
        @Override public void diffStart() { interrupted(); }
        @Override public void diffStep(int distance, int maximum) {
            interrupted();
            long charge = (long) distance + 1;
            if (charge > limits.maxFrontierSteps() - frontiers) throw new Stopped(Status.WORK_LIMIT);
            frontiers += charge;
        }
        @Override public void diffEnd() { interrupted(); }
        private Work work() { return new Work(frontiers, characters); }
    }

    private static final class Stopped extends RuntimeException {
        private final Status status;
        private Stopped(Status status) { super(null, null, false, false); this.status = status; }
    }
}
