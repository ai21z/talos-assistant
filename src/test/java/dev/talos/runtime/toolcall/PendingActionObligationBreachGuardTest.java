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

    /** HC-F6: an exact edit of an expected target that this turn already changed is in scope. */
    @Test
    void expectedTargetProgressAdmitsAnExactEditOfAChangedTarget() {
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("inventory.py", "orders.py"));

        PendingActionObligationBreachGuard.Decision decision =
                PendingActionObligationBreachGuard.assess(obligation, List.of(edit("orders.py")));

        assertFalse(decision.breach(), decision.detail());
        assertFalse(decision.deferToPolicy());
    }

    @Test
    void expectedTargetProgressAdmitsAnExactEditOfAChangedTargetBesideTheRemainingTarget() {
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                obligation, List.of(edit("orders.py"), edit("report.py")));

        assertFalse(decision.breach(), decision.detail());
    }

    /** D26 option A (HC4-F1): a complete write with content of a changed target is in scope like an exact edit. */
    @Test
    void expectedTargetProgressAdmitsACompleteWriteOfAChangedTarget() {
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        for (ToolCall write : List.of(
                new ToolCall("talos.write_file", Map.of("path", "orders.py", "content", "x\n")),
                new ToolCall("file_utils:write_file", Map.of("file_path", "orders.py", "text", "x\n")))) {
            PendingActionObligationBreachGuard.Decision decision =
                    PendingActionObligationBreachGuard.assess(obligation, List.of(write));

            assertFalse(decision.breach(), write + " " + decision.detail());
            assertFalse(decision.deferToPolicy(), write.toString());
        }
    }

    @Test
    void expectedTargetProgressStillRejectsAWriteOfAChangedTargetWithoutContent() {
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        for (Map<String, String> arguments : List.<Map<String, String>>of(
                Map.of("path", "orders.py"),
                Map.of("path", "orders.py", "content", ""),
                Map.of("path", "orders.py", "content", " \n"))) {
            PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                    obligation, List.of(new ToolCall("talos.write_file", arguments)));

            assertTrue(decision.breach(), arguments.toString());
            assertTrue(decision.detail().contains("talos.write_file(orders.py) [changed in this turn, only an exact "
                    + "edit with arguments or a complete write with content is accepted]"), decision.detail());
            assertTrue(decision.detail().contains("report.py"), decision.detail());
        }
    }

    @Test
    void expectedTargetProgressStillRejectsAnExactEditOutsideTheExpectedTargets() {
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                obligation, List.of(edit("orders.py"), edit("notes.py")));

        assertTrue(decision.breach());
        assertTrue(decision.detail().contains("talos.edit_file(notes.py)"), decision.detail());
        assertFalse(decision.detail().contains("talos.edit_file(orders.py)"), decision.detail());
    }

    @Test
    void expectedTargetsWithoutChangedTargetsKeepRejectingAnEditOfAnotherPath() {
        PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                PendingActionObligation.expectedTargets(List.of("report.py")), List.of(edit("orders.py")));

        assertTrue(decision.breach());
        assertTrue(decision.detail().contains("talos.edit_file(orders.py)"), decision.detail());
    }

    @Test
    void changedTargetAdmitsAnExactEditUnderItsAbsoluteSpelling() throws IOException {
        Files.writeString(workspace.resolve("orders.py"), "from inventory import quantity_on_hand\n");
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                obligation, List.of(edit(workspace.resolve("orders.py").toString())), workspace);

        assertFalse(decision.breach(), decision.detail());
    }

    @Test
    void obligationCarriesChangedTargetsOnlyForExpectedTargetProgressAndNeverRepeatsARequiredTarget() {
        PendingActionObligation progress = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of(" orders.py ", "report.py", "orders.py", ""));
        PendingActionObligation repair = new PendingActionObligation(
                PendingActionObligation.Kind.OLD_STRING_MISS_TARGET_REPAIR,
                List.of("report.py"), "", List.of("orders.py"));

        assertEquals(List.of("report.py"), progress.targets());
        assertEquals(List.of("orders.py"), progress.changedTargets());
        assertEquals(List.of(), repair.changedTargets());
        assertEquals(List.of(), PendingActionObligation.expectedTargets(List.of("report.py")).changedTargets());
        assertTrue(progress.failureReason().contains("Remaining target(s): report.py."), progress.failureReason());
        assertFalse(progress.failureReason().contains("orders.py"), progress.failureReason());
    }

    /** An empty edit of a changed target could lead to the edit-recovery full rewrite, so it still breaches. */
    @Test
    void expectedTargetProgressStillRejectsAnEditOfAChangedTargetWithEmptyArguments() {
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        for (Map<String, String> arguments : List.<Map<String, String>>of(
                Map.of("path", "orders.py", "old_string", "", "new_string", "x"),
                Map.of("path", "orders.py", "old_string", "  ", "new_string", "x"),
                Map.of("path", "orders.py", "new_string", "x"),
                Map.of("path", "orders.py", "old_string", "get_qty"))) {
            PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                    obligation, List.of(new ToolCall("talos.edit_file", arguments)));

            assertTrue(decision.breach(), arguments.toString());
            assertTrue(decision.detail().contains("talos.edit_file(orders.py)"), decision.detail());
        }
    }

    @Test
    void changedTargetAdmitsAnAcceptedEditAliasWithAliasedArguments() {
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                obligation, List.of(new ToolCall("file_utils:edit_file",
                        Map.of("file_path", "orders.py", "oldString", "get_qty", "newString", "quantity_on_hand"))));

        assertFalse(decision.breach(), decision.detail());
    }

    @Test
    void changedTargetDoesNotAdmitAnEditUnderARejectedNamespaceOrAnotherWorkspaceMutation() {
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        ToolCall foreign = new ToolCall("foo.edit_file",
                Map.of("path", "orders.py", "old_string", "a", "new_string", "b"));
        PendingActionObligationBreachGuard.Decision foreignDecision =
                PendingActionObligationBreachGuard.assess(obligation, List.of(foreign));
        assertTrue(foreignDecision.breach() || !ToolCallSupport.isMutatingTool(foreign.toolName()),
                "A mutating foreign namespace must breach: " + foreignDecision.detail());

        for (ToolCall call : List.of(
                new ToolCall("talos.delete_path", Map.of("path", "orders.py")),
                new ToolCall("talos.move_path", Map.of("from", "orders.py", "to", "orders_old.py")))) {
            assertTrue(ToolCallSupport.isMutatingTool(call.toolName()), call.toolName());
            PendingActionObligationBreachGuard.Decision decision =
                    PendingActionObligationBreachGuard.assess(obligation, List.of(call));

            assertTrue(decision.breach(), call.toolName() + " " + decision.detail());
        }
    }

    /**
     * HC-F6b and D26 option A: only an admitted exact edit or complete write of a changed target counts as answering
     * the progress request.
     */
    @Test
    void changedTargetChangeIsRecognizedOnlyForAnExactEditOrACompleteWriteOfAChangedTarget() {
        PendingActionObligation progress = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        assertTrue(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(progress, edit("orders.py"), null));
        assertFalse(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(progress, edit("report.py"), null),
                "an edit of a remaining target is ordinary progress");
        assertFalse(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(progress, edit("notes.py"), null));
        assertTrue(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(progress,
                new ToolCall("talos.write_file", Map.of("path", "orders.py", "content", "x\n")), null));
        assertFalse(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(progress,
                new ToolCall("talos.write_file", Map.of("path", "report.py", "content", "x\n")), null),
                "a write of a remaining target is ordinary progress");
        assertFalse(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(progress,
                new ToolCall("talos.write_file", Map.of("path", "orders.py")), null));
        assertFalse(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(progress,
                new ToolCall("talos.read_file", Map.of("path", "orders.py")), null));
        assertFalse(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(progress,
                new ToolCall("talos.edit_file", Map.of("path", "orders.py", "old_string", "", "new_string", "x")),
                null));
        assertFalse(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(
                PendingActionObligation.expectedTargets(List.of("report.py")), edit("orders.py"), null));
        assertFalse(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(null, edit("orders.py"), null));
        assertFalse(PendingActionObligationBreachGuard.admitsAsChangedTargetChange(
                new PendingActionObligation(PendingActionObligation.Kind.OLD_STRING_MISS_TARGET_REPAIR,
                        List.of("report.py"), "", List.of("orders.py")),
                edit("orders.py"), null));
    }

    @Test
    void breachDetailStatesWhyAMutationOfAChangedTargetWasRefused() {
        PendingActionObligation obligation = PendingActionObligation.expectedTargetProgress(
                List.of("report.py"), List.of("orders.py"));

        PendingActionObligationBreachGuard.Decision decision = PendingActionObligationBreachGuard.assess(
                obligation, List.of(
                        new ToolCall("talos.delete_path", Map.of("path", "orders.py")),
                        edit("notes.py")));

        assertTrue(decision.breach());
        assertTrue(decision.detail().contains("talos.delete_path(orders.py) [changed in this turn, only an exact "
                + "edit with arguments or a complete write with content is accepted]"), decision.detail());
        assertTrue(decision.detail().contains("talos.edit_file(notes.py),")
                || decision.detail().contains("talos.edit_file(notes.py)."), decision.detail());
        assertFalse(decision.detail().contains("talos.edit_file(notes.py) ["), decision.detail());
    }

    private static ToolCall edit(String path) {
        return new ToolCall("talos.edit_file", Map.of("path", path, "old_string", "get_qty", "new_string",
                "quantity_on_hand"));
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
