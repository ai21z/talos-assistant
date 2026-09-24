package dev.talos.runtime.toolcall;

import dev.talos.core.diff.DocumentPairAdmission.RequestedPair;
import dev.talos.core.diff.DocumentTextDiff;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.verification.DocumentComparisonPresentation;
import dev.talos.tools.*;

import java.util.Map;
import java.util.Set;

/** A current request and both admitted operands are required before comparison. */
public final class DocumentComparisonTool implements TalosTool {
    public static final String NAME = "talos.compare_documents";
    @Override public String name() { return NAME; }
    @Override public String description() {
        return "Compare the ordered files explicitly requested in the current turn. Exact UTF-8 text or PDF and DOCX extracted text is compared. Code is not executed. Behavioral equivalence is not established.";
    }
    @Override public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(), """
                {"type":"object","properties":{"left":{"type":"string"},"right":{"type":"string"}},"required":["left","right"],"additionalProperties":false}
                """, ToolRiskLevel.READ_ONLY, ToolOperationMetadata.inspect(NAME,
                Map.of("left", ToolOperationMetadata.PathRole.SOURCE_PATH,
                        "right", ToolOperationMetadata.PathRole.SOURCE_PATH), "DOCUMENTS_COMPARED"));
    }
    @Override public ToolResult execute(ToolCall call, ToolContext context) {
        ToolContentMetadata metadata = new ToolContentMetadata(ToolContentMetadata.ContentPrivacyClass.NORMAL,
                ToolContentMetadata.ContentSource.DOCUMENT_COMPARISON, "", false, false, false, "");
        try {
            if (call == null || context == null || !call.parameters().keySet().equals(Set.of("left", "right")))
                return failure(metadata);
            var observation = TurnAuditCapture.compareDocuments(context, new RequestedPair(call.param("left"), call.param("right")));
            if (observation == null) return failure(metadata);
            metadata = ToolContentMetadata.documentPair(observation.receipt());
            if (observation.result().status() != DocumentTextDiff.Status.COMPLETE) return failure(metadata);
            return ToolResult.ok(DocumentComparisonPresentation.render(observation, 16_000).text(), metadata);
        } catch (RuntimeException unavailable) {
            return failure(metadata);
        }
    }
    private static ToolResult failure(ToolContentMetadata metadata) {
        return new ToolResult(false, null, new ToolError("DOCUMENT_COMPARISON_UNAVAILABLE",
                "The requested document comparison was not established."), null, metadata);
    }
}
