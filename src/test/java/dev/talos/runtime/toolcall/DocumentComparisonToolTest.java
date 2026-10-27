package dev.talos.runtime.toolcall;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.task.DocumentComparisonRequest;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.tools.*;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentComparisonToolTest {
    @TempDir Path temporary;
    Path workspace;
    private final Config config = new Config(null);
    private final DocumentComparisonTool tool = new DocumentComparisonTool();
    @BeforeEach void useTheResolvedWorkspace() throws Exception {
        // The launcher resolves the workspace before use, and a temporary folder can be an 8.3 alias.
        workspace = temporary.toRealPath();
    }
    @AfterEach void clear() {
        TurnAuditCapture.end();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
    }
    private TaskContract begin(String request) {
        TurnAuditCapture.begin();
        var contract = TaskContract.unknown(request);
        TurnTaskContractCapture.set(contract);
        TurnUserRequestCapture.set(request);
        return contract;
    }
    private ToolResult compare(String left, String right) {
        return tool.execute(new ToolCall(tool.name(), Map.of("left", left, "right", right)),
                new ToolContext(workspace, new Sandbox(workspace, config.data), config));
    }
    @ParameterizedTest @ValueSource(strings = {
            "Compare a.txt and b.txt.", "Please compare a.txt and b.txt.",
            "What changed between a.txt and b.txt?", "Explain how a.txt differs from b.txt."})
    void ordinaryRequestBindsOrderedPair(String request) throws Exception {
        begin(request);
        Files.writeString(workspace.resolve("a.txt"), "old\r\n");
        Files.writeString(workspace.resolve("b.txt"), "new\n");
        var result = compare("a.txt", "b.txt");
        assertTrue(result.success(), () -> DocumentComparisonRequest.from(request).toString());
        assertTrue(result.output().contains("Removed \"old\" [CRLF]"));
        assertTrue(result.output().contains("Added \"new\" [LF]"));
        assertEquals(ToolContentMetadata.ContentSource.DOCUMENT_COMPARISON, result.contentMetadata().source());
        assertEquals(1, TurnAuditCapture.currentComparisons(request, workspace).size());
    }
    @ParameterizedTest @ValueSource(strings = {
            "Do not compare a.txt and b.txt.", "Example, compare a.txt and b.txt.",
            "If needed compare a.txt and b.txt.", "Read a.txt and b.txt.",
            "`Compare a.txt and b.txt`", "Compare a.txt and b.txt for security."})
    void unsupportedScopeCannotAuthorizeComparison(String request) {
        begin(request);
        var result = compare("a.txt", "b.txt");
        assertFalse(result.success());
        assertEquals(ToolContentMetadata.ContentSource.DOCUMENT_COMPARISON, result.contentMetadata().source());
        assertTrue(TurnAuditCapture.currentComparisons(request, workspace).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {
            "\u201c\nCompare a.txt and b.txt.\n\u201d", "(\nCompare a.txt and b.txt.\n)",
            "An example follows:\nCompare a.txt and b.txt.", "> Compare a.txt and b.txt."})
    void quotedOrConditionalEnvelopeCannotMintARequest(String request) {
        assertFalse(DocumentComparisonRequest.from(request).active());
        assertFalse(DocumentComparisonRequest.from(request).requiresAssessment());
    }
    @ParameterizedTest @ValueSource(strings = {"Compare a.txt with b.txt.", "Explain the differences between a.txt and b.txt.",
            "Compare \"first file.txt\" with \"second file.txt\"."})
    void unsupportedComparisonIsAnObligationButNotExecutableAuthority(String request) {
        var binding = DocumentComparisonRequest.from(request);
        assertTrue(binding.requiresAssessment());
        assertFalse(binding.active());
        var registry = new ToolRegistry();
        registry.register(tool);
        assertFalse(ToolSurfacePlanner.plan(TaskContract.unknown(request), ExecutionPhase.INSPECT, registry)
                .nativeToolNames().contains(tool.name()));
    }
    @ParameterizedTest @ValueSource(strings = {"Explain the difference between 12 and 30.", "Read a.txt and b.txt.",
            "Do not compare a.txt and b.txt.", "Explain what a comparison means."})
    void numericalAndUnrelatedQuestionsDoNotAcquireDocumentObligations(String request) {
        assertFalse(DocumentComparisonRequest.from(request).requiresAssessment());
    }
    @Test void reversedAndSubstitutedTargetsAreRejected() throws Exception {
        begin("Compare a.txt and b.txt.");
        Files.writeString(workspace.resolve("a.txt"), "one");
        Files.writeString(workspace.resolve("b.txt"), "two");
        assertFalse(compare("b.txt", "a.txt").success());
        assertFalse(compare("a.txt", "other.txt").success());
        assertTrue(compare("a.txt", "b.txt").success());
    }
    @Test void onlyRequestedComparisonIsExposed() {
        var registry = new ToolRegistry();
        registry.register(tool);
        registry.register(new ReadFileTool());
        assertFalse(ToolSurfacePlanner.plan(TaskContract.unknown("Read a.txt."), ExecutionPhase.INSPECT, registry)
                .nativeToolNames().contains(tool.name()));
        assertTrue(ToolSurfacePlanner.plan(TaskContract.unknown("Compare a.txt and b.txt."), ExecutionPhase.INSPECT, registry)
                .nativeToolNames().contains(tool.name()));
    }
    @Test void originalCompoundRequestRetainsAllSiblingRequirements() {
        String request = "Read README.md, index.html, script.js and scripts.js. Explain why the Calculate button fails and give the correct selector. For span 12 m and rise 2.5 m, give the panel area according to README.md and the mathematical value of the expression in script.js separately. Explain any disagreement and distinguish the two JavaScript files. Do not edit files or claim tests were run.";
        var binding = DocumentComparisonRequest.from(request);
        assertEquals(1, binding.requirements().size());
        assertEquals("script.js", binding.requirements().getFirst().pair().left());
        assertEquals("scripts.js", binding.requirements().getFirst().pair().right());
        assertTrue(binding.requirements().getFirst().explanationRequired());
        assertTrue(dev.talos.runtime.task.ReadOnlyRequestMeaning.compile(request).requirements().slots().size() > binding.requirements().size());
    }
}
