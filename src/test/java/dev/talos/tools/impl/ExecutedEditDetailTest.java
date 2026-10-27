package dev.talos.tools.impl;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.io.AtomicFileWriter;
import dev.talos.core.security.Sandbox;
import dev.talos.core.util.TextReplacement;
import dev.talos.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutedEditDetailTest {
    @TempDir Path workspace;

    private ToolResult edit(String path, String source, String before, String after) throws IOException {
        Files.writeString(workspace.resolve(path), source);
        return execute(new FileEditTool(), path, before, after);
    }

    private ToolResult execute(FileEditTool tool, String path, String before, String after) {
        var context = new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config());
        return tool.execute(new ToolCall("talos.edit_file", Map.of(
                "path", path, "old_string", before, "new_string", after)), context);
    }

    @Test void actualProducerRetainsExactAppliedFragmentsWithoutChangingRawPersistencePermission() throws Exception {
        var result = edit("page.html", "<h1>Earlier title</h1>\n", "Earlier title", "Revised title");
        assertTrue(result.success());
        var detail = result.executedEditDetail();
        assertTrue(detail.available());
        assertEquals("page.html", detail.target());
        assertEquals("Earlier title", detail.before());
        assertEquals("Revised title", detail.after());
        assertEquals(ExecutedEditDetail.ReadBack.MATCHED_AT_EXECUTION, detail.readBack());
        assertEquals(VerificationStatus.PASS, detail.verification());
        assertTrue(detail.associatedWith(result.mutationEffects()));
        assertFalse(result.contentMetadata().rawArtifactPersistenceAllowed());
        assertEquals("<h1>Revised title</h1>\n", Files.readString(workspace.resolve("page.html")));
    }

    @Test void actualCrLfMatchUsesRawSliceAndEffectiveReplacementNotProposalStrings() throws Exception {
        var result = edit("notes.txt", "first\r\nsecond\r\nlast\r\n", "first\nsecond", "one\ntwo");
        assertTrue(result.success());
        assertTrue(result.executedEditDetail().available());
        assertEquals("first\r\nsecond", result.executedEditDetail().before());
        assertEquals("one\r\ntwo", result.executedEditDetail().after());
        assertEquals("one\r\ntwo\r\nlast\r\n", Files.readString(workspace.resolve("notes.txt")));
    }

    @Test void actualDeletionIsAvailableWithEmptyAfter() throws Exception {
        var result = edit("notes.txt", "first\nremove\nlast\n", "remove\n", "");
        assertTrue(result.success());
        assertTrue(result.executedEditDetail().available());
        assertEquals("remove\n", result.executedEditDetail().before());
        assertEquals("", result.executedEditDetail().after());
    }

    @Test void noOpMissingAndAmbiguousEditsHaveNoDetail() throws Exception {
        for (var pair : List.of(List.of("old", "old"), List.of("absent", "new"), List.of("o", "new"))) {
            var result = edit("notes.txt", "old old", pair.getFirst(), pair.getLast());
            assertFalse(result.success());
            assertFalse(result.executedEditDetail().available());
            assertFalse(result.mutationEffects().hasEffects());
        }
    }

    @Test void atomicFailureBeforeOrAfterCommitCannotMintOptionalDetail() throws Exception {
        for (var stage : List.of(AtomicFileWriter.FaultStage.BEFORE_PROMOTION,
                AtomicFileWriter.FaultStage.AFTER_PROMOTION)) {
            Files.writeString(workspace.resolve("notes.txt"), "old");
            var tool = new FileEditTool(new AtomicFileWriter((actual, target, temp) -> {
                if (actual == stage) throw new IOException("injected failure");
            }));
            var result = execute(tool, "notes.txt", "old", "new");
            assertFalse(result.success());
            assertFalse(result.executedEditDetail().available());
            assertEquals(stage == AtomicFileWriter.FaultStage.AFTER_PROMOTION,
                    result.mutationEffects().hasEffects());
            assertEquals(stage == AtomicFileWriter.FaultStage.AFTER_PROMOTION ? "new" : "old",
                    Files.readString(workspace.resolve("notes.txt")));
        }
    }

    @ParameterizedTest(name = "unsafe context [{index}]")
    @ValueSource(strings = {"password=quiet-value\n", "TALOS_CANARY_DETAIL_CONTEXT\n",
            "PRIVATE_MARKER=not-for-display\n", "location=C:/private/notes.txt\n", "\u001b[31mplain\n",
            "\u202ehidden\n"})
    void secretOrUnsafeContextOutsideFragmentSuppressesOnlyMetadata(String context) throws Exception {
        var result = edit("notes.txt", context + "old", "old", "new");
        assertTrue(result.success());
        assertEquals(List.of("notes.txt"), result.mutationEffects().appliedPaths());
        assertFalse(result.executedEditDetail().available());
        assertEquals(context + "new", Files.readString(workspace.resolve("notes.txt")));
    }

    @Test void newlyIntroducedSecretContextAlsoSuppressesMetadata() throws Exception {
        var result = edit("notes.txt", "old", "old", "password=quiet-value");
        assertTrue(result.success());
        assertFalse(result.executedEditDetail().available());
    }

    @Test void fragmentBoundsDropMetadataWithoutTruncatingOrPreventingEdit() throws Exception {
        var exact = edit("notes.txt", "x".repeat(512), "x".repeat(512), "y".repeat(512));
        assertTrue(exact.success());
        assertTrue(exact.executedEditDetail().available());
        for (var pair : List.of(List.of("x".repeat(513), "y"), List.of("x", "y".repeat(513)))) {
            var result = edit("notes.txt", pair.getFirst(), pair.getFirst(), pair.getLast());
            assertTrue(result.success());
            assertFalse(result.executedEditDetail().available());
            assertEquals(pair.getLast(), Files.readString(workspace.resolve("notes.txt")));
        }
    }

    @Test void oversizedPostimageIsStillAppliedButNotRetained() throws Exception {
        String source = "old\n" + "x".repeat(ExecutedEditDetail.MAX_SOURCE_BYTES - 4);
        var result = edit("notes.txt", source, "old", "larger");
        assertTrue(result.success());
        assertFalse(result.executedEditDetail().available());
        assertEquals(source.length() + 3L, Files.size(workspace.resolve("notes.txt")));
    }

    @Test void formatFailureDoesNotEraseSuccessfulExactReadbackOrAppliedEffect() throws Exception {
        var result = edit("data.json", "{\"value\":1}", "1", "broken");
        assertTrue(result.success());
        assertEquals(VerificationStatus.FAIL, result.verification());
        assertTrue(result.executedEditDetail().available());
        assertEquals(VerificationStatus.FAIL, result.executedEditDetail().verification());
        assertEquals(ExecutedEditDetail.ReadBack.MATCHED_AT_EXECUTION, result.executedEditDetail().readBack());
        assertTrue(result.mutationEffects().hasEffects());
    }

    @Test void strictEffectAssociationAndCompatibilityConstructorsCannotInventDetails() throws Exception {
        var result = edit("notes.txt", "old", "old", "new");
        var detail = result.executedEditDetail();
        assertTrue(detail.available());
        assertSame(detail, result.withMutationEffects(ToolMutationEffects.applied("./notes.txt")).executedEditDetail());
        for (var effects : List.of(ToolMutationEffects.none(), ToolMutationEffects.applied("other.txt"),
                ToolMutationEffects.uncertain("notes.txt"),
                new ToolMutationEffects(List.of("notes.txt", "other.txt"), List.of()),
                new ToolMutationEffects(List.of("notes.txt"), List.of("notes.txt")))) {
            assertFalse(detail.associatedWith(effects));
            assertFalse(result.withMutationEffects(effects).executedEditDetail().available());
            assertFalse(ToolResult.ok("safe").withMutationEffects(effects)
                    .withExecutedEditDetail(detail).executedEditDetail().available());
        }
        assertFalse(new ToolResult(true, "safe", null, null).executedEditDetail().available());
        assertFalse(new ToolResult(true, "safe", null, null, null).executedEditDetail().available());
        assertFalse(new ToolResult(true, "safe", null, null, null, result.mutationEffects())
                .executedEditDetail().available());
        assertFalse(result.withExecutedEditDetail(null).executedEditDetail().available());
    }

    @Test void jsonAndToStringExposeNoLiteralEvenWithFieldVisibilityEnabled() throws Exception {
        var result = edit("notes.txt", "original display marker", "original display marker", "changed display marker");
        assertTrue(result.executedEditDetail().available());
        var mapper = new ObjectMapper();
        for (var candidate : List.of(mapper, new ObjectMapper().setVisibility(PropertyAccessor.FIELD,
                JsonAutoDetect.Visibility.ANY))) {
            String json = candidate.writeValueAsString(result);
            assertFalse(json.contains("executedEditDetail"));
            assertFalse(json.contains("original display marker"));
            assertFalse(json.contains("changed display marker"));
            assertEquals("{\"available\":true}", candidate.writeValueAsString(result.executedEditDetail()));
        }
        assertFalse(result.toString().contains("original display marker"));
        assertFalse(result.toString().contains("changed display marker"));
        assertEquals("ExecutedEditDetail[available=true]", result.executedEditDetail().toString());
    }

    @Test void syntheticCaptureRejectsMalformedUnsafeAndUnboundInputs() {
        var vr = new ContentVerifier.VerifyResult(VerificationStatus.UNKNOWN, "not proof");
        for (String target : List.of("../notes.txt", "/notes.txt", "secret.txt", ".env", "a/../notes.txt",
                "notes.txt ", "a\u202eb.txt", "notes\n.txt")) {
            assertFalse(ExecutedEditDetail.capture(workspace, target, "old", "new",
                    new TextReplacement.Match(1, 0, 3, "new"), vr, ToolMutationEffects.applied(target)).available());
        }
        assertFalse(ExecutedEditDetail.capture(workspace, "notes.txt", "old", "unrelated",
                new TextReplacement.Match(1, 0, 3, "new"), vr, ToolMutationEffects.applied("notes.txt")).available());
        assertFalse(ExecutedEditDetail.capture(workspace, "notes.txt", "\ud800old", "\ud800new",
                new TextReplacement.Match(1, 1, 4, "new"), vr, ToolMutationEffects.applied("notes.txt")).available());
        assertFalse(ExecutedEditDetail.capture(null, null, null, null, null, null, null).available());
    }

    @Test void syntheticCaptureBoundsBytesBeforeFullTextSanitizationAndRejectsSplitSurrogates() {
        var vr = new ContentVerifier.VerifyResult(VerificationStatus.UNKNOWN, "not proof");
        var effects = ToolMutationEffects.applied("notes.txt");
        String oversized = "\u20ac".repeat(ExecutedEditDetail.MAX_SOURCE_BYTES / 3 + 1);
        assertTrue(oversized.length() < ExecutedEditDetail.MAX_SOURCE_BYTES);
        assertFalse(ExecutedEditDetail.capture(workspace, "notes.txt", "old" + oversized, "new" + oversized,
                new TextReplacement.Match(1, 0, 3, "new"), vr, effects).available());
        assertFalse(ExecutedEditDetail.capture(workspace, "notes.txt", "\ud83d\ude00", "\ud83e\ude00",
                new TextReplacement.Match(1, 0, 1, "\ud83e"), vr, effects).available());
    }
}
