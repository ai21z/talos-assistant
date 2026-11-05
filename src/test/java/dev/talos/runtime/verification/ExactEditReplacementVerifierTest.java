package dev.talos.runtime.verification;

import dev.talos.core.util.Hash;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.verification.ExactEditReplacementVerifier.Standing;
import dev.talos.tools.MutationPreimage;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactEditReplacementVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void exactEditReplacementPassesWhenReplacementTextIsObservedAndOldTextIsGone() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=new\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(successfulExactEdit("notes.md", "status=old", "status=new", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertTrue(result.coversAllSuccessfulMutations());
        assertFalse(result.hasProblem());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("notes.md: exact edit replacement observed")),
                result.facts().toString());
    }

    @Test
    void exactEditReplacementFailsWhenReplacementTextIsMissing() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=old\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(successfulExactEdit("notes.md", "status=old", "status=new", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertTrue(result.coversAllSuccessfulMutations());
        assertTrue(result.hasProblem());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("notes.md: exact edit replacement text was not observed")),
                result.problems().toString());
    }

    @Test
    void exactEditReplacementFailsWhenShrinkEditDidNotLand() throws Exception {
        Files.writeString(workspace.resolve("code.java"), "    return x;\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(successfulExactEdit("code.java", "    return x;", "  return x;", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertTrue(result.coversAllSuccessfulMutations());
        assertTrue(result.hasProblem(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("code.java: exact edit replacement old text remained")),
                result.problems().toString());
    }

    @Test
    void exactEditReplacementVerifiesLfVisibleReplacementOnCrLfFile() throws Exception {
        Files.writeString(workspace.resolve("windows.txt"), "alpha\r\nBETA\r\ngamma\r\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(successfulExactEdit("windows.txt", "alpha\nbeta", "alpha\nBETA", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertTrue(result.coversAllSuccessfulMutations());
        assertFalse(result.hasProblem(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("windows.txt: exact edit replacement observed")),
                result.facts().toString());
    }

    @Test
    void exactEditReplacementPassesWhenNewStringContainsOldStringAndOldStringOnlyAppearsInsideReplacement()
            throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "Release 2023-2024\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(successfulExactEdit("notes.md", "2023", "2023-2024", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertFalse(result.hasProblem(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("notes.md: exact edit replacement observed")),
                result.facts().toString());
    }

    @Test
    void exactEditReplacementFailsWhenNewStringContainsOldStringButOldStringAlsoRemainsOutsideReplacement()
            throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "Release 2023\nRelease 2023-2024\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(successfulExactEdit("notes.md", "2023", "2023-2024", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertTrue(result.hasProblem());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("notes.md: exact edit replacement old text remained")),
                result.problems().toString());
    }

    @Test
    void mixedExactEditAndReadbackOnlyMutationDoesNotCoverAllSuccessfulMutations() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=new\n");
        Files.writeString(workspace.resolve("README.md"), "# Talos\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(
                        successfulExactEdit("notes.md", "status=old", "status=new", VerificationStatus.PASS),
                        successfulWrite("README.md", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertFalse(result.coversAllSuccessfulMutations());
        assertFalse(result.hasProblem());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("notes.md: exact edit replacement observed")),
                result.facts().toString());
    }

    // P19, D13 option A: an exact edit that a later change of the same file follows is observed where that change found
    // the file, its producer preimage. The outcomes and preimages below are constructed.

    @Test
    void anEditThatALaterEditReplacedIsStatedWithoutAProblemAndCoversNothing() throws Exception {
        Files.writeString(workspace.resolve("config.ini"), "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001", MutationPreimage.none()),
                edit("config.ini", "[SERVER]\nport=9001", "[SERVER]\nport=9000",
                        preimage("config.ini", "[SERVER]\nport=9001\n[CLIENT]\nport=8080\n"))));

        assertTrue(result.verifiedAny());
        assertFalse(result.hasProblem(), result.problems().toString());
        assertEquals(List.of(), result.problems());
        assertEquals(List.of(Standing.REPLACED_LATER, Standing.KEPT), result.standings());
        assertFalse(result.coversAllSuccessfulMutations());
        assertTrue(result.leftEditUnasserted());
        assertEquals(List.of(
                "config.ini: exact edit replacement observed before a later change of the same file altered it.",
                "config.ini: exact edit replacement observed in post-apply file."), result.facts());
    }

    @Test
    void anEditAbsentWhereTheNextChangeFoundTheFileStaysFailed() throws Exception {
        Files.writeString(workspace.resolve("config.ini"), "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("config.ini", "[CLIENT]\nport=8080", "[CLIENT]\nport=9001", MutationPreimage.none()),
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9000",
                        preimage("config.ini", "[SERVER]\nport=8080\n[CLIENT]\nport=8080\n"))));

        assertTrue(result.hasProblem());
        assertEquals(List.of("config.ini: exact edit replacement text was not observed after apply."),
                result.problems());
        assertEquals(List.of(Standing.NOT_HELD, Standing.KEPT), result.standings());
        assertFalse(result.leftEditUnasserted());
    }

    @Test
    void anOldTextThatTheNextChangeStillFoundStaysFailed() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "Release 2023\nRelease 2023-2024\nend\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "2023", "2023-2024", MutationPreimage.none()),
                edit("notes.md", "end", "end\n", preimage("notes.md", "Release 2023\nRelease 2023-2024\nend\n"))));

        assertEquals(List.of("notes.md: exact edit replacement old text remained after apply."), result.problems());
        assertEquals(List.of(Standing.NOT_HELD, Standing.KEPT), result.standings());
    }

    @Test
    void aLaterChangeWithoutAPreimageLeavesTheEditUnobserved() throws Exception {
        Files.writeString(workspace.resolve("config.ini"), "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001", MutationPreimage.none()),
                write("talos.write_file", "config.ini", ToolMutationEffects.applied("config.ini"),
                        MutationPreimage.none())));

        assertFalse(result.hasProblem(), result.problems().toString());
        assertEquals(List.of(Standing.UNOBSERVED), result.standings());
        assertFalse(result.coversAllSuccessfulMutations());
        assertTrue(result.leftEditUnasserted());
        assertEquals(List.of("config.ini: exact edit replacement could not be confirmed after a later change of the "
                + "same file."), result.facts());
    }

    @Test
    void aLaterChangeThroughItsEffectsAloneIsTheNextChangeOfTheTarget() throws Exception {
        Files.writeString(workspace.resolve("config.ini"), "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001", MutationPreimage.none()),
                write("talos.apply_workspace_batch", "settings", ToolMutationEffects.applied("config.ini"),
                        MutationPreimage.none())));

        assertFalse(result.hasProblem(), result.problems().toString());
        assertEquals(List.of(Standing.UNOBSERVED), result.standings());
    }

    @Test
    void aPreimageOfAnotherTargetIsNotUsed() throws Exception {
        Files.writeString(workspace.resolve("config.ini"), "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001", MutationPreimage.none()),
                edit("config.ini", "[SERVER]\nport=9001", "[SERVER]\nport=9000",
                        preimage("other.ini", "[SERVER]\nport=9001\n[CLIENT]\nport=8080\n"))));

        assertEquals(List.of(Standing.UNOBSERVED, Standing.KEPT), result.standings());
        assertFalse(result.hasProblem(), result.problems().toString());
    }

    @Test
    void aChangeOfAnotherFileIsNotALaterChangeOfTheTarget() throws Exception {
        Files.writeString(workspace.resolve("config.ini"), "[SERVER]\nport=8080\n");
        Files.writeString(workspace.resolve("notes.md"), "a\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9000", MutationPreimage.none()),
                write("talos.write_file", "notes.md", ToolMutationEffects.applied("notes.md"),
                        preimage("notes.md", "[SERVER]\nport=9000\n"))));

        assertEquals(List.of("config.ini: exact edit replacement text was not observed after apply."),
                result.problems());
        assertEquals(List.of(Standing.NOT_HELD), result.standings());
    }

    @Test
    void anEditKeptInTheFinalFileStaysKeptWhenALaterEditOfTheFileFollows() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "title: Final\nstatus=new\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "title: Draft", "title: Final", MutationPreimage.none()),
                edit("notes.md", "status=old", "status=new", preimage("notes.md", "title: Final\nstatus=old\n"))));

        assertEquals(List.of(Standing.KEPT, Standing.KEPT), result.standings());
        assertTrue(result.coversAllSuccessfulMutations());
        assertFalse(result.hasProblem());
        assertFalse(result.leftEditUnasserted());
    }

    @Test
    void aLaterEditThatBringsTheOldTextBackDoesNotFailTheEarlierEdit() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "title: Final\nbody\nprevious title: Draft\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "title: Draft", "title: Final", MutationPreimage.none()),
                edit("notes.md", "body", "body\nprevious title: Draft", preimage("notes.md", "title: Final\nbody\n"))));

        assertEquals(List.of(Standing.REPLACED_LATER, Standing.KEPT), result.standings());
        assertFalse(result.hasProblem(), result.problems().toString());
        assertFalse(result.coversAllSuccessfulMutations());
    }

    @Test
    void anUndoChainIsNeitherFailedNorCovered() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=old\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "status=old", "status=new", MutationPreimage.none()),
                edit("notes.md", "status=new", "status=old", preimage("notes.md", "status=new\n"))));

        assertEquals(List.of(Standing.REPLACED_LATER, Standing.KEPT), result.standings());
        assertFalse(result.hasProblem(), result.problems().toString());
        assertFalse(result.coversAllSuccessfulMutations());
    }

    @Test
    void pathSpellingsOfOneFileResolveToOneTarget() throws Exception {
        // Review finding F5: the later outcome names the file by its absolute path and has no mutation effects, so the
        // next change is found only through the workspace path identity.
        Files.writeString(workspace.resolve("config.ini"), "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n");
        String absolute = workspace.resolve("config.ini").toString();

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("./config.ini", "[SERVER]\nport=8080", "[SERVER]\nport=9001", MutationPreimage.none()),
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", absolute, true, true, false,
                        "Edited config.ini", "", VerificationStatus.PASS, "", null,
                        ToolMutationEvidence.exactEdit("[SERVER]\nport=9001", "[SERVER]\nport=9000"),
                        ToolFailureReason.NONE, List.of(), "call-edit-absolute", ToolMutationEffects.none(), null,
                        preimage("config.ini", "[SERVER]\nport=9001\n[CLIENT]\nport=8080\n"))));

        assertEquals(List.of(Standing.REPLACED_LATER, Standing.KEPT), result.standings());
        assertFalse(result.hasProblem(), result.problems().toString());
    }

    // Review finding F2: a loss is attributed to a recorded change only when that change's own result no longer holds
    // the edit. Otherwise an unrecorded change removed it and the edit stays FAILED.

    @Test
    void anUnrecordedRemovalAfterAnEditOfAnotherRegionStaysFailed() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "title: Draft\nstatus=new\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "title: Draft", "title: Final", MutationPreimage.none()),
                edit("notes.md", "status=old", "status=new", preimage("notes.md", "title: Final\nstatus=old\n"))));

        assertEquals(List.of(Standing.NOT_HELD, Standing.KEPT), result.standings());
        assertEquals(List.of("notes.md: exact edit replacement text was not observed after apply."),
                result.problems());
    }

    @Test
    void aRemovalThatALaterPreimageShowsBeforeAnotherRegionEditStaysFailed() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "title: Draft\nstatus=new\nend.\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "title: Draft", "title: Final", MutationPreimage.none()),
                edit("notes.md", "status=old", "status=new",
                        preimage("notes.md", "title: Final\nstatus=old\nend\n")),
                edit("notes.md", "end", "end.", preimage("notes.md", "title: Draft\nstatus=new\nend\n"))));

        assertEquals(List.of(Standing.NOT_HELD, Standing.KEPT, Standing.KEPT), result.standings());
        assertTrue(result.hasProblem());
    }

    @Test
    void aMiddleEditThatReplacedTheEditMakesItReplacedLater() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "a=3\nb=2\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "a=1", "a=2", MutationPreimage.none()),
                edit("notes.md", "a=2", "a=3", preimage("notes.md", "a=2\nb=1\n")),
                edit("notes.md", "b=1", "b=2", preimage("notes.md", "a=3\nb=1\n"))));

        assertEquals(List.of(Standing.REPLACED_LATER, Standing.KEPT, Standing.KEPT), result.standings());
        assertFalse(result.hasProblem(), result.problems().toString());
    }

    @Test
    void aFullWriteWhoseEvidenceDropsTheEditMakesItReplacedLater() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "title: Other\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "title: Draft", "title: Final", MutationPreimage.none()),
                new ToolCallLoop.ToolOutcome(
                        "talos.write_file", "notes.md", true, true, false,
                        "Updated notes.md", "", VerificationStatus.PASS, "", null,
                        ToolMutationEvidence.fullWriteReplacement("title: Final\n", "title: Other\n"),
                        ToolFailureReason.NONE, List.of(), "call-write", ToolMutationEffects.applied("notes.md"), null,
                        preimage("notes.md", "title: Final\n"))));

        assertEquals(List.of(Standing.REPLACED_LATER), result.standings());
        assertFalse(result.hasProblem(), result.problems().toString());
    }

    @Test
    void aWriteWithoutEvidenceOfItsResultLeavesTheEditUnobserved() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "title: Other\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "title: Draft", "title: Final", MutationPreimage.none()),
                write("talos.write_file", "notes.md", ToolMutationEffects.applied("notes.md"),
                        preimage("notes.md", "title: Final\n"))));

        assertEquals(List.of(Standing.UNOBSERVED), result.standings());
        assertFalse(result.hasProblem(), result.problems().toString());
    }

    @Test
    void anEditThatPassesInTheFinalFileStillPassesWhatEverTheNextChangeFound() throws Exception {
        // Every edit that passes today still passes. The final file decides KEPT before any later preimage is read.
        Files.writeString(workspace.resolve("notes.md"), "a=2\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(workspace, List.of(
                edit("notes.md", "a=1", "a=2", MutationPreimage.none()),
                edit("notes.md", "a=1", "a=2", preimage("notes.md", "a=1\n"))));

        assertEquals(List.of(Standing.KEPT, Standing.KEPT), result.standings());
        assertTrue(result.coversAllSuccessfulMutations());
    }

    @Test
    void theFiveComponentResultHasNoStandings() {
        ExactEditReplacementVerifier.Result result =
                new ExactEditReplacementVerifier.Result(true, true, false, List.of(), List.of());

        assertEquals(List.of(), result.standings());
        assertFalse(result.leftEditUnasserted());
    }

    private static ToolCallLoop.ToolOutcome edit(
            String path, String oldString, String newString, MutationPreimage preimage) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file", path, true, true, false,
                "Edited " + path, "", VerificationStatus.PASS, "", null,
                ToolMutationEvidence.exactEdit(oldString, newString), ToolFailureReason.NONE, List.of(),
                "call-edit-" + oldString.hashCode(), ToolMutationEffects.applied(path), null, preimage);
    }

    private static ToolCallLoop.ToolOutcome write(
            String tool, String path, ToolMutationEffects effects, MutationPreimage preimage) {
        return new ToolCallLoop.ToolOutcome(
                tool, path, true, true, false,
                "Updated " + path, "", VerificationStatus.PASS, "", null,
                ToolMutationEvidence.none(), ToolFailureReason.NONE, List.of(),
                "call-" + tool, effects, null, preimage);
    }

    private static MutationPreimage preimage(String path, String text) {
        return MutationPreimage.existing(path, Hash.sha256Hex(text.getBytes(StandardCharsets.UTF_8)), text);
    }

    private static ToolCallLoop.ToolOutcome successfulExactEdit(
            String path,
            String oldString,
            String newString,
            VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file", path, true, true, false,
                "edited " + path, "", verificationStatus, "",
                null,
                ToolMutationEvidence.exactEdit(oldString, newString));
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(String path, VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", verificationStatus);
    }
}
