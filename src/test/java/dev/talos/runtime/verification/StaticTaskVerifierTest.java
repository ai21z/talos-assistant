package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticTaskVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void noSuccessfulMutationDoesNotRunVerification() {
        ToolCallLoop.LoopResult loopResult = loopResult(List.of());

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace, "Check the website.", loopResult, 0);

        assertEquals(TaskVerificationStatus.NOT_RUN, result.status());
    }

    @Test
    void selectorRepairFailsWhenMutationLeavesReferencedClassMissing() throws Exception {
        writeWebFiles("""
                <!DOCTYPE html>
                <html><body><main id="hero"><p>No CTA yet</p></main></body></html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix index.html so the CSS and JavaScript .cta-button selector has a matching element.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("`.cta-button`")));
    }

    @Test
    void selectorRepairPassesWhenHtmlProvidesReferencedClass() throws Exception {
        writeWebFiles("""
                <!DOCTYPE html>
                <html><body><main id="hero"><a class="cta-button">Listen</a></main></body></html>
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Fix index.html so the CSS and JavaScript .cta-button selector has a matching element.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertTrue(result.facts().stream().anyMatch(f -> f.contains("selector coherence passed")));
    }

    @Test
    void cssHexColorsAreNotTreatedAsIdSelectors() throws Exception {
        writeWebFiles("""
                <!DOCTYPE html>
                <html><body><main id="hero"><a class="cta-button">Listen</a></main></body></html>
                """);
        Files.writeString(workspace.resolve("style.css"), """
                body { background: #140014; color: #f8eaff; }
                #hero { padding: 48px; }
                .cta-button { color: #ffffff; }
                """);

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Check selector linkage and the .cta-button fix.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.PASSED, result.status());
    }

    @Test
    void placeholderOnlyMutationFailsVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<updated_index_html_content>");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update index.html.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.PASS))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("template placeholder"));
    }

    @Test
    void fileLevelVerificationWarningFailsTaskVerification() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<html><body><main></main></body></html>");

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace,
                "Update index.html.",
                loopResult(List.of(successfulEdit("index.html", VerificationStatus.WARN))),
                0);

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertTrue(result.summary().contains("file-level verification reported warning"));
    }

    private void writeWebFiles(String html) throws Exception {
        Files.writeString(workspace.resolve("index.html"), html);
        Files.writeString(workspace.resolve("style.css"), """
                body { background: #140014; }
                #hero { padding: 48px; }
                .cta-button { display: inline-block; }
                """);
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.cta-button');
                """);
    }

    private static ToolCallLoop.ToolOutcome successfulEdit(String path, VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file", path, true, true, false,
                "edited " + path, "", verificationStatus);
    }

    private static ToolCallLoop.LoopResult loopResult(List<ToolCallLoop.ToolOutcome> outcomes) {
        int successes = (int) outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::success)
                .count();
        return new ToolCallLoop.LoopResult(
                "Done.", 1, outcomes.size(), List.of("talos.edit_file"), List.of(),
                0, 0, false, successes, List.of(),
                0, 0, 0, 0, outcomes);
    }
}
