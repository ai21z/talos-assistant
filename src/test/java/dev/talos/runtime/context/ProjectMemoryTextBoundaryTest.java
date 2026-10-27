package dev.talos.runtime.context;

import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectMemoryTextBoundaryTest {
    @TempDir Path temp;

    @AfterEach void clearLedger() {
        ContextLedgerCapture.clear();
    }

    @ParameterizedTest
    @CsvSource({"2,1", "3,1", "3,2", "4,1", "4,2", "4,3"})
    void byteCapOmitsOnlyIncompleteValidCodePoint(int width, int retainedBytes) throws Exception {
        String prefix = "a".repeat(256 - retainedBytes);
        byte[] source = (prefix + codePoint(width) + "tail").getBytes(StandardCharsets.UTF_8);

        var context = load(source, 256, 12000, 200);

        assertEquals(ProjectMemoryStatus.LOADED, context.status());
        var included = context.includedSources().getFirst();
        assertEquals(prefix, included.content());
        assertTrue(included.truncated());
        assertEquals(prefix.length(), included.bytes());
        assertFalse(context.renderForPrompt().contains("\ufffd"));
        assertTrue(context.decisions().stream().noneMatch(d -> d.decisionReason().equals("NON_UTF8_TEXT")));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void alignedCodePointFitsAtByteCap(int width) throws Exception {
        String prefix = "a".repeat(256 - width) + codePoint(width);

        var context = load((prefix + "tail").getBytes(StandardCharsets.UTF_8), 256, 12000, 200);

        assertEquals(ProjectMemoryStatus.LOADED, context.status());
        assertEquals(prefix, context.includedSources().getFirst().content());
        assertTrue(context.includedSources().getFirst().truncated());
    }

    @Test
    void completeCodePointAtActualEofIsNotMarkedTruncated() throws Exception {
        String source = "a".repeat(252) + codePoint(4);
        var context = load(source.getBytes(StandardCharsets.UTF_8), 256, 12000, 200);

        assertEquals(source, context.includedSources().getFirst().content());
        assertFalse(context.includedSources().getFirst().truncated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0800", "\ud7ff", "\ud800\udc00", "\udbff\udfff"})
    void incompleteValidScalarRangeEdgesRemainAdmissible(String codePoint) throws Exception {
        int retainedBytes = codePoint.getBytes(StandardCharsets.UTF_8).length - 1;
        String prefix = "a".repeat(256 - retainedBytes);

        var context = load((prefix + codePoint).getBytes(StandardCharsets.UTF_8), 256, 12000, 200);

        assertEquals(ProjectMemoryStatus.LOADED, context.status());
        assertEquals(prefix, context.includedSources().getFirst().content());
        assertTrue(context.includedSources().getFirst().truncated());
    }

    @Test
    void defaultByteLimitAlsoAdmitsValidSplitPrefix() throws Exception {
        int cap = ProjectMemoryLimits.defaults().maxBytesPerFile();
        String source = "a".repeat(cap - 1) + codePoint(2) + "tail";

        var context = load(source.getBytes(StandardCharsets.UTF_8), cap, 12000, 200);

        assertEquals(ProjectMemoryStatus.LOADED, context.status());
        assertEquals("a".repeat(12000), context.includedSources().getFirst().content());
        assertTrue(context.includedSources().getFirst().truncated());
    }

    @ParameterizedTest
    @MethodSource("invalidRetainedSequences")
    void malformedRetainedBytesAreRejectedAtEofAndAtCap(String hex, boolean truncated) throws Exception {
        byte[] invalid = HexFormat.of().parseHex(hex);
        byte[] source = new byte[truncated ? 257 : 256];
        Arrays.fill(source, (byte) 'a');
        System.arraycopy(invalid, 0, source, 256 - invalid.length, invalid.length);

        assertMalformed(load(source, 256, 12000, 200));
    }

    private static Stream<Arguments> invalidRetainedSequences() {
        return Stream.of("80", "c0", "c328", "e080", "eda0", "f080", "f490", "f580", "ff")
                .flatMap(hex -> Stream.of(Arguments.of(hex, false), Arguments.of(hex, true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"c3", "e2", "e282", "f0", "f09f", "f09f98"})
    void unfinishedSequenceAtActualEofIsRejected(String hex) throws Exception {
        byte[] partial = HexFormat.of().parseHex(hex);
        byte[] source = new byte[20 + partial.length];
        Arrays.fill(source, (byte) 'a');
        System.arraycopy(partial, 0, source, 20, partial.length);

        assertMalformed(load(source, 256, 12000, 200));
    }

    @Test
    void malformedInteriorIsNotHiddenByValidIncompleteTail() throws Exception {
        byte[] source = new byte[257];
        Arrays.fill(source, (byte) 'a');
        source[20] = (byte) 0xff;
        source[255] = (byte) 0xc3;
        source[256] = (byte) 0xa9;

        assertMalformed(load(source, 256, 12000, 200));
    }

    @Test
    void characterCapDoesNotSplitUtf16SurrogatePair() throws Exception {
        String prefix = "a".repeat(127);
        var context = load((prefix + codePoint(4) + "tail").getBytes(StandardCharsets.UTF_8),
                4096, 128, 200);

        assertEquals(prefix, context.includedSources().getFirst().content());
        assertEquals(127, context.includedSources().getFirst().chars());
        assertTrue(context.includedSources().getFirst().truncated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "byte", "character", "line"})
    void truncationDoesNotExposeSyntheticPrivateKeyBody(String cut) throws Exception {
        String body = "SYNTHETIC_PRIVATE_BODY";
        String source = "Project note\n-----BEGIN " + "PRIVATE KEY-----\n" + body + "\n"
                + "A".repeat(300) + "\n-----END " + "PRIVATE KEY-----\nPublic ending.";
        int bytes = cut.equals("byte") ? 256 : 4096;
        int chars = cut.equals("character") ? 128 : 12000;
        int lines = cut.equals("line") ? 3 : 200;

        var context = load(source.getBytes(StandardCharsets.UTF_8), bytes, chars, lines);

        assertEquals(ProjectMemoryStatus.LOADED, context.status());
        assertTrue(context.renderForPrompt().contains("Project note"));
        assertFalse(context.renderForPrompt().contains(body));
        assertFalse(context.renderForPrompt().contains("A".repeat(20)));
        assertTrue(context.renderForPrompt().contains("[redacted]"));
        if (!cut.equals("byte")) assertTrue(context.renderForPrompt().contains("Public ending."));
        else assertTrue(context.includedSources().getFirst().truncated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RSA", "EC", "OPENSSH", "ENCRYPTED"})
    void byteTruncationRedactsTypedPrivateKeyBlocks(String type) throws Exception {
        String source = "Public note\n-----BEGIN " + type + " PRIVATE KEY-----\n"
                + "SYNTHETIC_PRIVATE_BODY\n" + "A".repeat(300)
                + "\n-----END " + type + " PRIVATE KEY-----\n";

        var context = load(source.getBytes(StandardCharsets.UTF_8), 256, 12000, 200);

        assertEquals("Public note\n[redacted]", context.includedSources().getFirst().content());
        assertTrue(context.includedSources().getFirst().truncated());
    }

    @Test
    void sanitizationPrecedesCutsForSecretAssignmentsToo() throws Exception {
        String source = "Public note\npassword=\"SYNTHETIC_VALUE " + "more ".repeat(80) + "\"\nPublic ending.";

        var context = load(source.getBytes(StandardCharsets.UTF_8), 4096, 128, 2);

        assertEquals("Public note\npassword=[redacted]", context.includedSources().getFirst().content());
        assertTrue(context.includedSources().getFirst().truncated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"", "'", "`"})
    void byteCapCannotExposeUnterminatedMultiwordSecretAssignment(String quote) throws Exception {
        String source = "Public note\npassword=" + quote + "first word\nSYNTHETIC_PRIVATE_BODY "
                + "more words ".repeat(50) + quote + "\nPublic ending.";

        var context = load(source.getBytes(StandardCharsets.UTF_8), 256, 12000, 200);

        assertEquals("Public note\npassword=[redacted]", context.includedSources().getFirst().content());
        assertTrue(context.includedSources().getFirst().truncated());
        assertFalse(context.renderForPrompt().contains("SYNTHETIC_PRIVATE_BODY"));
    }

    private ProjectMemoryContext load(byte[] bytes, int byteCap, int charCap, int lineCap) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        Files.write(workspace.resolve("TALOS.md"), bytes);
        var contract = new TaskContract(TaskType.WORKSPACE_EXPLAIN, false, false, false,
                Set.of(), Set.of(), "Explain this project.");
        return new ProjectMemoryLoader(new ProjectMemoryLimits(8, 0, byteCap, charCap, lineCap, 16000))
                .load(new ProjectMemoryRequest(workspace, home, contract));
    }

    private static void assertMalformed(ProjectMemoryContext context) {
        assertTrue(context.includedSources().isEmpty());
        assertTrue(context.decisions().stream().anyMatch(d -> d.decisionReason().equals("NON_UTF8_TEXT")));
    }

    private static String codePoint(int width) {
        return switch (width) {
            case 2 -> "\u00e9";
            case 3 -> "\u20ac";
            case 4 -> "\ud83d\ude00";
            default -> throw new IllegalArgumentException("Unsupported width: " + width);
        };
    }
}
