package dev.talos.runtime.verification;

import dev.talos.runtime.capability.ArtifactOperation;
import dev.talos.runtime.capability.CapabilityProfile;
import dev.talos.runtime.capability.TargetSurface;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import dev.talos.testsupport.CaseSensitiveFolders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TargetScopeStaticVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void expectedAndForbiddenTargetsUseSameTargetScopeMatching() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("script.js"),
                Set.of("scripts.js"),
                "Replace .missing-button with #submit in script.js. Do not edit scripts.js.");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract,
                workspace,
                CapabilityProfile.none(),
                Set.of("script.js", "scripts.js"),
                Set.of(),
                Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("scripts.js: forbidden mutation target was changed")),
                result.problems().toString());
        assertFalse(result.facts().stream()
                        .anyMatch(f -> f.contains("Expected mutation target(s) were updated")),
                result.facts().toString());
    }

    @Test
    void onlyTargetRequestFailsWhenAdditionalMutationDoesNotMatchExpectedTarget() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("script.js"),
                Set.of(),
                "Only change script.js.");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract,
                workspace,
                CapabilityProfile.none(),
                Set.of("script.js", "scripts.js"),
                Set.of(),
                Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains(
                                "scripts.js: non-requested mutation target was changed under an only-target request")),
                result.problems().toString());
        assertFalse(result.facts().stream()
                        .anyMatch(f -> f.contains("Expected mutation target(s) were updated")),
                result.facts().toString());
    }

    @Test
    void staticWebRepairContextTargetsCanBeSatisfiedWithoutDirectMutation() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<html><body><script src=\"script.js\"></script></body></html>");
        Files.writeString(workspace.resolve("styles.css"), "body { color: white; }");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('#run-button');");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html", "styles.css", "script.js"),
                Set.of(),
                "Fix the static web button fixture. Keep filenames index.html, styles.css, and script.js.");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract,
                workspace,
                CapabilityProfile.staticWeb(ArtifactOperation.REPAIR, TargetSurface.FUNCTIONAL_WEB),
                Set.of("script.js"),
                Set.of(),
                Set.of());

        assertFalse(result.problems().stream()
                        .anyMatch(p -> p.contains("expected target was not successfully mutated")),
                result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains(
                                "Expected mutation target(s) and static web context target(s) were satisfied")),
                result.facts().toString());
    }

    @Test
    void expectedTargetMatchingPreservesWindowsCaseInsensitiveOption() {
        assertTrue(TargetScopeStaticVerifier.expectedTargetMatches("Index.html", "index.html", true));
        assertTrue(TargetScopeStaticVerifier.expectedTargetMatches(".\\Index.html", "./index.html", true));
        assertFalse(TargetScopeStaticVerifier.expectedTargetMatches("scripts.js", "script.js", true));
        assertFalse(TargetScopeStaticVerifier.expectedTargetMatches("Index.html", "index.html", false));
    }

    @Test
    void expectedTargetWrittenThroughItsAbsolutePathIsSatisfied() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("notes.md"), "updated\n");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract(Set.of("notes.md"), Set.of(), "Update notes.md."),
                root,
                CapabilityProfile.none(),
                Set.of(root.resolve("notes.md").toString()),
                Set.of(),
                Set.of());

        assertTrue(result.problems().isEmpty(), result.problems().toString());
    }

    @Test
    void siblingOrSubfolderFileWrittenThroughAnAbsolutePathDoesNotSatisfyTheTarget() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("other.md"), "other\n");
        Files.writeString(root.resolve("docs").resolve("notes.md"), "docs\n");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract(Set.of("notes.md"), Set.of(), "Update notes.md."),
                root,
                CapabilityProfile.none(),
                Set.of(root.resolve("other.md").toString(), root.resolve("docs").resolve("notes.md").toString()),
                Set.of(),
                Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("notes.md: expected target was not successfully mutated")),
                result.problems().toString());
    }

    @Test
    void forbiddenTargetChangedThroughItsAbsolutePathIsReported() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("notes.md"), "updated\n");
        Files.writeString(root.resolve("keep.md"), "changed\n");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract(Set.of("notes.md"), Set.of("keep.md"), "Update notes.md. Do not edit keep.md."),
                root,
                CapabilityProfile.none(),
                Set.of("notes.md", root.resolve("keep.md").toString()),
                Set.of(),
                Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("keep.md: forbidden mutation target was changed")),
                result.problems().toString());
    }

    @Test
    void onlyTargetWrittenThroughItsAbsolutePathIsNotAnotherTarget() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("notes.md"), "updated\n");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract(Set.of("notes.md"), Set.of(), "Only change notes.md."),
                root,
                CapabilityProfile.none(),
                Set.of(root.resolve("notes.md").toString()),
                Set.of(),
                Set.of());

        assertTrue(result.problems().isEmpty(), result.problems().toString());
    }

    private static TaskContract contract(Set<String> expected, Set<String> forbidden, String request) {
        return new TaskContract(TaskType.FILE_EDIT, true, true, true, expected, forbidden, request);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void forbiddenTargetUnderAnUnprovenCaseVariantIsReported() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("notes.md"), "updated\n");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract(Set.of("notes.md"), Set.of("KEEP.md"), "Update notes.md. Do not edit KEEP.md."),
                root,
                CapabilityProfile.none(),
                Set.of("notes.md", root.resolve("keep.md").toString()),
                Set.of(),
                Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("KEEP.md: forbidden mutation target was changed")),
                result.problems().toString());
    }

    @Test
    void forbiddenTargetReachedThroughALinkedFolderIsReported() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("notes.md"), "updated\n");
        Files.createDirectories(root.resolve("real"));
        Files.writeString(root.resolve("real").resolve("keep.md"), "changed\n");
        try {
            Files.createSymbolicLink(root.resolve("linked"), root.resolve("real"));
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "links cannot be created here");
        }

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract(Set.of("notes.md"), Set.of("linked/keep.md"), "Update notes.md. Do not edit linked/keep.md."),
                root,
                CapabilityProfile.none(),
                Set.of("notes.md", root.resolve("linked").resolve("keep.md").toString()),
                Set.of(),
                Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("linked/keep.md: forbidden mutation target was changed")),
                result.problems().toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void forbiddenTargetUnderADriveRelativeSpellingIsReported() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("notes.md"), "updated\n");
        Files.writeString(root.resolve("keep.md"), "changed\n");
        String drive = root.getRoot().toString().substring(0, 2);

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract(Set.of("notes.md"), Set.of("keep.md"), "Update notes.md. Do not edit keep.md."),
                root,
                CapabilityProfile.none(),
                Set.of("notes.md", drive + "keep.md"),
                Set.of(),
                Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("keep.md: forbidden mutation target was changed")),
                result.problems().toString());
    }

    // Uses existing members: @TempDir workspace (:23), contract(Set, Set, String) (:191).
    // Already imported: EnabledOnOs, OS, assumeTrue, Files, Path, Set, CapabilityProfile.

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(60)
    void caseVariantInACaseSensitiveFolderDoesNotSatisfyTheExpectedOrOnlyTarget() throws Exception {
        Path root = workspace.toRealPath();
        Path docs = Files.createDirectories(root.resolve("docs"));
        assumeTrue(CaseSensitiveFolders.enable(docs), "case-sensitive folders cannot be made here");
        assumeTrue(CaseSensitiveFolders.writeBothSpellings(docs, "notes.md", "Notes.md"),
                "the folder is not case-sensitive");

        TargetScopeStaticVerifier.Result expected = TargetScopeStaticVerifier.verify(
                contract(Set.of("docs/notes.md"), Set.of(), "Update docs/notes.md."),
                root, CapabilityProfile.none(), Set.of("docs/Notes.md"), Set.of(), Set.of());
        TargetScopeStaticVerifier.Result only = TargetScopeStaticVerifier.verify(
                contract(Set.of("docs/notes.md"), Set.of(), "Only change docs/notes.md."),
                root, CapabilityProfile.none(), Set.of("docs/notes.md", "docs/Notes.md"), Set.of(), Set.of());

        assertTrue(expected.problems().stream()
                        .anyMatch(p -> p.contains("docs/notes.md: expected target was not successfully mutated")),
                expected.problems().toString());
        assertTrue(only.problems().stream()
                        .anyMatch(p -> p.contains("docs/Notes.md: non-requested mutation target was changed")),
                only.problems().toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(60)
    void forbiddenTargetInACaseSensitiveFolderStillReportsItsCaseVariant() throws Exception {
        Path root = workspace.toRealPath();
        Path docs = Files.createDirectories(root.resolve("docs"));
        assumeTrue(CaseSensitiveFolders.enable(docs), "case-sensitive folders cannot be made here");
        assumeTrue(CaseSensitiveFolders.writeBothSpellings(docs, "notes.md", "Notes.md"),
                "the folder is not case-sensitive");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract(Set.of("docs/Notes.md"), Set.of("docs/notes.md"),
                        "Update docs/Notes.md. Do not edit docs/notes.md."),
                root, CapabilityProfile.none(), Set.of("docs/Notes.md"), Set.of(), Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("docs/notes.md: forbidden mutation target was changed")),
                result.problems().toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void caseVariantOfAnExistingFileInAnOrdinaryFolderStillSatisfiesTheExpectedAndOnlyTarget() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("notes.md"), "updated\n");

        for (String request : java.util.List.of("Update docs/notes.md.", "Only change docs/notes.md.")) {
            TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                    contract(Set.of("docs/notes.md"), Set.of(), request),
                    root, CapabilityProfile.none(), Set.of("docs/NOTES.md"), Set.of(), Set.of());
            assertTrue(result.problems().isEmpty(), request + " " + result.problems());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aBackslashInARecordedPathIsPartOfTheFileNameAndDoesNotSatisfyTheTarget() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("a.md"), "unchanged\n");
        Path literal = Files.writeString(root.resolve("docs\\a.md"), "changed\n");
        assumeTrue(root.equals(literal.getParent()), "this file system reads the backslash as a separator");

        TargetScopeStaticVerifier.Result expected = TargetScopeStaticVerifier.verify(
                contract(Set.of("docs/a.md"), Set.of(), "Update docs/a.md."),
                root, CapabilityProfile.none(), Set.of(literal.toString()), Set.of(), Set.of());
        TargetScopeStaticVerifier.Result only = TargetScopeStaticVerifier.verify(
                contract(Set.of("docs/a.md"), Set.of(), "Only change docs/a.md."),
                root, CapabilityProfile.none(), Set.of("docs/a.md", literal.toString()), Set.of(), Set.of());

        assertTrue(expected.problems().stream()
                        .anyMatch(p -> p.contains("docs/a.md: expected target was not successfully mutated")),
                expected.problems().toString());
        assertTrue(only.problems().stream()
                        .anyMatch(p -> p.contains("non-requested mutation target was changed")),
                only.problems().toString());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void forbiddenTargetStaysReportedForARecordedPathWithABackslash() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("notes.md"), "updated\n");
        Path literal = Files.writeString(root.resolve("docs\\a.md"), "changed\n");
        assumeTrue(root.equals(literal.getParent()), "this file system reads the backslash as a separator");

        TargetScopeStaticVerifier.Result result = TargetScopeStaticVerifier.verify(
                contract(Set.of("notes.md"), Set.of("docs/a.md"), "Update notes.md. Do not edit docs/a.md."),
                root, CapabilityProfile.none(), Set.of("notes.md", literal.toString()), Set.of(), Set.of());

        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("docs/a.md: forbidden mutation target was changed")),
                result.problems().toString());
    }
}
