package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PendingActionObligationBreachGuardTest {

    @Test
    void expectedTargetWrongMutationReturnsBreachDetail() {
        PendingActionObligation obligation =
                PendingActionObligation.expectedTargets(List.of("scripts.js"));
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation,
                        List.of(call("talos.write_file", "script.js")));

        assertTrue(decision.breach());
        assertFalse(decision.deferToPolicy());
        assertTrue(decision.detail().contains("expected-target progress required mutation"),
                decision.detail());
        assertTrue(decision.detail().contains("scripts.js"), decision.detail());
        assertTrue(decision.detail().contains("talos.write_file(script.js)"), decision.detail());
    }

    @Test
    void expectedTargetStaticWebPolicyViolationCanDeferToNormalPolicy() {
        PendingActionObligation obligation =
                PendingActionObligation.expectedTargets(List.of("scripts.js"));
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation,
                        List.of(call("talos.write_file", "src/script.js")));

        assertFalse(decision.breach());
        assertTrue(decision.deferToPolicy());
        assertEquals("", decision.detail());
    }

    @Test
    void staticRepairReadOnlyContinuationReturnsBreachDetail() {
        PendingActionObligation obligation =
                PendingActionObligation.staticRepairTargets(List.of("styles.css"));
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation,
                        List.of(call("talos.read_file", "styles.css")));

        assertTrue(decision.breach());
        assertFalse(decision.deferToPolicy());
        assertTrue(decision.detail().contains("Static web repair requires talos.write_file"),
                decision.detail());
        assertTrue(decision.detail().contains("styles.css"), decision.detail());
        assertTrue(decision.detail().contains("talos.read_file(styles.css)"), decision.detail());
    }

    @Test
    void compactTargetRepairWrongToolReturnsBreachDetail() {
        PendingActionObligation obligation =
                PendingActionObligation.oldStringMissTargets(List.of("README.md"));
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation,
                        List.of(call("talos.read_file", "README.md")));

        assertTrue(decision.breach());
        assertFalse(decision.deferToPolicy());
        assertTrue(decision.detail().contains("old-string miss compact repair required"),
                decision.detail());
        assertTrue(decision.detail().contains("README.md"), decision.detail());
        assertTrue(decision.detail().contains("talos.read_file(README.md)"), decision.detail());
    }

    @Test
    void compactTargetRepairAcceptsWriteFileAliasForPendingTarget() {
        PendingActionObligation obligation =
                PendingActionObligation.oldStringMissTargets(List.of("README.md"));
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation,
                        List.of(call("file_utils:write_file", "README.md")));

        assertFalse(decision.breach(), decision.detail());
        assertFalse(decision.deferToPolicy());
    }

    @Test
    void loopStateDelegatesInvalidToolClassificationToGuard() throws Exception {
        String loopState = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/LoopState.java"));

        assertTrue(loopState.contains("PendingActionObligationBreachGuard.assess("), loopState);
        assertFalse(loopState.contains("private static String invalidExpectedTargetMutationDetail"),
                loopState);
        assertFalse(loopState.contains("private static boolean shouldPolicyHandleStaticWebExpectedTargetViolation"),
                loopState);
        assertFalse(loopState.contains("private static String targetRepairInvalidToolDetail"),
                loopState);
        assertFalse(loopState.contains("private static String staticRepairInvalidToolDetail"),
                loopState);
    }

    private static ToolCall call(String toolName, String path) {
        return new ToolCall(toolName, Map.of("path", path));
    }

    @TempDir
    Path workspace;

    @ParameterizedTest
    @EnumSource(value = PendingActionObligation.Kind.class, names = {
            "OLD_STRING_MISS_TARGET_REPAIR", "APPEND_LINE_TARGET_REPAIR", "EXPECTED_TARGET_SCOPE_REPAIR"})
    void targetRepairAcceptsAMutationOfThePendingTargetUnderItsAbsoluteSpelling(
            PendingActionObligation.Kind kind) throws IOException {
        Path root = workspaceWith("docs/notes.md", "archive/notes.md");
        PendingActionObligation obligation = new PendingActionObligation(kind, List.of("docs/notes.md"));
        String absolute = root.resolve("docs").resolve("notes.md").toString();

        for (String tool : List.of("talos.write_file", "talos.edit_file")) {
            PendingActionObligationBreachGuard.Decision decision =
                    PendingActionObligationBreachGuard.assess(obligation, List.of(call(tool, absolute)), root);
            assertFalse(decision.breach(), kind + " " + tool + ": " + decision.detail());
            assertFalse(decision.deferToPolicy());
        }
    }

    @ParameterizedTest
    @EnumSource(value = PendingActionObligation.Kind.class, names = {
            "OLD_STRING_MISS_TARGET_REPAIR", "APPEND_LINE_TARGET_REPAIR", "EXPECTED_TARGET_SCOPE_REPAIR"})
    void targetRepairStillBreachesOnASameNamedFileElsewhere(
            PendingActionObligation.Kind kind) throws IOException {
        Path root = workspaceWith("docs/notes.md", "archive/notes.md");
        PendingActionObligation obligation = new PendingActionObligation(kind, List.of("docs/notes.md"));
        String other = root.resolve("archive").resolve("notes.md").toString();

        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation, List.of(call("talos.write_file", other)), root);

        assertTrue(decision.breach(), kind.name());
        assertFalse(decision.deferToPolicy());
        assertTrue(decision.detail().contains(
                "required talos.write_file or talos.edit_file for target(s): docs/notes.md"),
                decision.detail());
    }

    @Test
    void loopStateKeepsATargetRepairObligationWhenThePendingTargetIsEditedUnderItsAbsoluteSpelling()
            throws IOException {
        Path root = workspaceWith("docs/notes.md", "archive/notes.md");
        LoopState state = new LoopState("", List.of(), new ArrayList<>(), root, null, null, 5, 0);
        state.setPendingActionObligation(PendingActionObligation.oldStringMissTargets(List.of("docs/notes.md")));

        assertFalse(state.failPendingActionObligationAfterInvalidToolCalls(List.of(
                call("talos.edit_file", root.resolve("docs").resolve("notes.md").toString()))));
        assertTrue(state.hasPendingActionObligation());

        assertTrue(state.failPendingActionObligationAfterInvalidToolCalls(List.of(
                call("talos.edit_file", root.resolve("archive").resolve("notes.md").toString()))));
        assertFalse(state.hasPendingActionObligation());
        assertTrue(state.currentText.contains(
                "[Action obligation failed: pending old-string miss repair was not satisfied.]"),
                state.currentText);
    }

    @Test
    void staticRepairAcceptsAValidWriteOfTheTargetUnderItsAbsoluteSpelling() throws IOException {
        Path root = workspaceWith("web/styles.css", "archive/styles.css");
        PendingActionObligation obligation = PendingActionObligation.staticRepairTargets(List.of("web/styles.css"));
        String absolute = root.resolve("web").resolve("styles.css").toString();

        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation, List.of(writeFile(absolute, ".button { color: red; }\n")), root);

        assertFalse(decision.breach(), decision.detail());
        assertFalse(decision.deferToPolicy());
    }

    @Test
    void staticRepairStillBreachesOnAValidWriteOfASameNamedFileElsewhere() throws IOException {
        Path root = workspaceWith("web/styles.css", "archive/styles.css");
        PendingActionObligation obligation = PendingActionObligation.staticRepairTargets(List.of("web/styles.css"));
        String other = root.resolve("archive").resolve("styles.css").toString();

        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(
                        obligation, List.of(writeFile(other, ".button { color: red; }\n")), root);

        assertTrue(decision.breach());
        assertFalse(decision.deferToPolicy());
        assertTrue(decision.detail().contains(
                "Static web repair requires talos.write_file for remaining target(s): web/styles.css"),
                decision.detail());
    }

    @Test
    void staticRepairRejectsInvalidContentUnderEitherSpellingOfTheTarget() throws IOException {
        Path root = workspaceWith("web/styles.css");
        PendingActionObligation obligation = PendingActionObligation.staticRepairTargets(List.of("web/styles.css"));
        String absolute = root.resolve("web").resolve("styles.css").toString();

        for (String spelling : List.of("web/styles.css", absolute)) {
            assertRejectedContent(obligation, root, writeFile(spelling, "   "),
                    "before apply because empty or blank content.");
            assertRejectedContent(obligation, root, writeFile(spelling, "<updated_style_css_content>"),
                    "before apply because literal template-placeholder content.");
            assertRejectedContent(obligation, root, call("talos.write_file", spelling),
                    "before apply because missing required `content` argument.");
        }
    }

    /** T223: a repair write must name the pending target in its own case, even where case is ignored. */
    @ParameterizedTest
    @EnumSource(value = PendingActionObligation.Kind.class, names = {
            "OLD_STRING_MISS_TARGET_REPAIR", "APPEND_LINE_TARGET_REPAIR", "EXPECTED_TARGET_SCOPE_REPAIR"})
    void targetRepairRejectsACaseVariantOfThePendingTarget(PendingActionObligation.Kind kind) throws IOException {
        Path root = workspaceWith("docs/notes.md");
        PendingActionObligation obligation = new PendingActionObligation(kind, List.of("docs/notes.md"));

        for (String spelling : List.of("docs/Notes.md", root.resolve("docs").resolve("Notes.md").toString())) {
            PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                    obligation, List.of(call("talos.write_file", spelling)), root);
            assertTrue(decision.breach(), kind + " " + spelling);
            assertFalse(decision.deferToPolicy());
        }
    }

    @Test
    void staticRepairRejectsACaseVariantOfTheTarget() throws IOException {
        Path root = workspaceWith("web/styles.css");
        PendingActionObligation obligation = PendingActionObligation.staticRepairTargets(List.of("web/styles.css"));

        for (String spelling : List.of("web/Styles.css", root.resolve("web").resolve("Styles.css").toString())) {
            PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                    obligation, List.of(writeFile(spelling, ".button { color: red; }\n")), root);
            assertTrue(decision.breach(), spelling);
            assertTrue(decision.detail().contains(
                    "Static web repair requires talos.write_file for remaining target(s): web/styles.css"),
                    decision.detail());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void repairAcceptsTheTargetUnderAnotherCaseOfTheWorkspaceFolder() throws IOException {
        Path root = workspaceWith("docs/notes.md", "web/styles.css");
        String upperRoot = root.toString().toUpperCase(java.util.Locale.ROOT);

        var targetRepair = PendingActionObligationBreachGuard.assess(
                PendingActionObligation.oldStringMissTargets(List.of("docs/notes.md")),
                List.of(call("talos.write_file", upperRoot + "\\docs\\notes.md")),
                root);
        assertFalse(targetRepair.breach(), targetRepair.detail());

        var staticRepair = PendingActionObligationBreachGuard.assess(
                PendingActionObligation.staticRepairTargets(List.of("web/styles.css")),
                List.of(writeFile(upperRoot + "\\web\\styles.css", ".button { color: red; }\n")),
                root);
        assertFalse(staticRepair.breach(), staticRepair.detail());
    }

    /**
     * A case variant of a target that does not exist yet is an attempt at the target, not a breach.
     * The pre-approval rule still refuses the write, and the scope repair names the exact target.
     */
    @Test
    void expectedTargetCaseVariantOfAMissingTargetIsNotABreach() throws IOException {
        Path root = workspaceWith("docs/notes.md");
        PendingActionObligation obligation = PendingActionObligation.expectedTargets(List.of("docs/More.md"));

        for (String spelling : List.of("docs/more.md", root.resolve("docs").resolve("more.md").toString())) {
            var decision = PendingActionObligationBreachGuard.assess(
                    obligation, List.of(writeFile(spelling, "x\n")), root);
            assertFalse(decision.breach(), spelling + ": " + decision.detail());
        }
        assertTrue(PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile("docs/other.md", "x\n")), root).breach(),
                "another file stays a breach");
        assertTrue(PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile("docs/more.md", "x\n")), null).breach(),
                "without a workspace the case variant stays a breach");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void expectedTargetCaseVariantIsAnotherFileWhereCaseIsSignificant() throws IOException {
        Path root = workspaceWith("docs/notes.md");
        assumeTrue(!Files.exists(root.resolve("docs").resolve("Notes.md")), "this file system ignores case");
        PendingActionObligation obligation = PendingActionObligation.expectedTargets(List.of("docs/notes.md"));

        var variant = PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile("docs/Notes.md", "x\n")), root);
        assertTrue(variant.breach(), "a case variant is another file here");
        assertFalse(variant.deferToPolicy());
        assertTrue(variant.detail().contains("talos.write_file(docs/Notes.md)"), variant.detail());

        var absoluteVariant = PendingActionObligationBreachGuard.assess(
                obligation,
                List.of(writeFile(root.resolve("docs").resolve("Notes.md").toString(), "x\n")),
                root);
        assertTrue(absoluteVariant.breach(), absoluteVariant.detail());

        assertFalse(PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile("docs/notes.md", "x\n")), root).breach());
        assertFalse(PendingActionObligationBreachGuard.assess(
                obligation,
                List.of(writeFile(root.resolve("docs").resolve("notes.md").toString(), "x\n")),
                root).breach());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @Timeout(60)
    void expectedTargetCaseVariantIsAnotherFileInACaseSensitiveWindowsFolder() throws Exception {
        Path root = workspace.toRealPath();
        Path docs = Files.createDirectories(root.resolve("docs"));
        assumeTrue(madeCaseSensitive(docs), "case-sensitive folders cannot be made here");
        Path lower = Files.writeString(docs.resolve("notes.md"), "lower\n");
        Path upper = Files.writeString(docs.resolve("Notes.md"), "upper\n");
        assumeTrue(!Files.readString(lower).equals(Files.readString(upper)), "the folder is not case-sensitive");
        PendingActionObligation obligation = PendingActionObligation.expectedTargets(List.of("docs/notes.md"));

        var variant = PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile("docs/Notes.md", "x\n")), root);
        assertTrue(variant.breach(), "docs/Notes.md is another file in this folder");
        assertFalse(variant.deferToPolicy());
        assertTrue(variant.detail().contains("talos.write_file(docs/Notes.md)"), variant.detail());

        assertTrue(PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile(upper.toString(), "x\n")), root).breach());
        assertFalse(PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile("docs/notes.md", "x\n")), root).breach());
        assertFalse(PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile(lower.toString(), "x\n")), root).breach());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void expectedTargetCaseVariantOfAnExistingFileInAnOrdinaryWindowsFolderStaysAccepted() throws IOException {
        Path root = workspaceWith("docs/notes.md");
        assumeTrue(Files.exists(root.resolve("docs").resolve("Notes.md")), "this folder is case-sensitive");
        PendingActionObligation obligation = PendingActionObligation.expectedTargets(List.of("docs/notes.md"));

        var decision = PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile("docs/Notes.md", "x\n")), root);

        assertFalse(decision.breach(), decision.detail());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void expectedTargetIsNotMatchedByARootFileWhoseNameContainsABackslash() throws IOException {
        Path root = workspaceWith("docs/a.md");
        Path literal = Files.writeString(root.resolve("docs\\a.md"), "literal backslash name\n");
        assumeTrue(Files.isRegularFile(literal) && literal.getParent().equals(root),
                "this file system cannot hold a backslash in a file name");
        PendingActionObligation obligation = PendingActionObligation.expectedTargets(List.of("docs/a.md"));

        var decision = PendingActionObligationBreachGuard.assess(
                obligation, List.of(writeFile(root + "/docs\\a.md", "x\n")), root);

        assertTrue(decision.breach(), "a root file named docs\\a.md is not docs/a.md: " + decision.detail());
        assertFalse(decision.deferToPolicy());
    }

    private static void assertRejectedContent(
            PendingActionObligation obligation, Path root, ToolCall call, String reason) {
        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(obligation, List.of(call), root);
        assertTrue(decision.breach(), call.param("path", ""));
        assertFalse(decision.deferToPolicy());
        assertTrue(decision.detail().startsWith("Static web repair rejected talos.write_file("),
                decision.detail());
        assertTrue(decision.detail().contains(reason), decision.detail());
    }

    private Path workspaceWith(String... files) throws IOException {
        Path root = workspace.toRealPath();
        for (String file : files) {
            Path path = root.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, file + "\n");
        }
        return root;
    }

    private static ToolCall writeFile(String path, String content) {
        return new ToolCall("talos.write_file", Map.of("path", path, "content", content));
    }

    private static boolean madeCaseSensitive(Path folder) {
        try {
            Process process = new ProcessBuilder(
                    "fsutil.exe", "file", "setCaseSensitiveInfo", folder.toString(), "enable")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void creatingAnExistingParentFolderUnderACaseVariantIsNotABreachInAnOrdinaryWindowsFolder() throws IOException {
        Path root = workspaceWith("docs/keep.md");
        assumeTrue(Files.exists(root.resolve("Docs")), "this folder is case-sensitive");

        var decision = PendingActionObligationBreachGuard.assess(
                PendingActionObligation.expectedTargets(List.of("docs/a.md")),
                List.of(call("talos.mkdir", "Docs")), root);

        assertFalse(decision.breach(), decision.detail());
    }
}
