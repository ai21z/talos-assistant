package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTracePrivateDocumentHandoffTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearTraceCapture() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsPrivateDocumentHandoffPayloadWithoutRawDocumentText() throws Exception {
        ToolCall call = new ToolCall("talos.read_file", Map.of(
                "path", "medical-notes.docx",
                "content", "Patient Name: Eleni Nikolaou"));
        ToolContentMetadata metadata = ToolContentMetadata.extractedDocument(
                "medical-notes.docx",
                true,
                false,
                false,
                false,
                " private document extraction scope ");

        beginTrace();
        LocalTurnTraceCapture.recordPrivateDocumentModelHandoffApprovalGranted(
                "EXECUTE",
                call,
                metadata,
                true);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_GRANTED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();

        assertEquals("EXECUTE", event.phase());
        assertEquals("talos.read_file", event.toolName());
        assertEquals("SEND_TO_MODEL_CONTEXT", event.data().get("scope"));
        assertEquals(true, event.data().get("perTurn"));
        assertEquals(true, event.data().get("rememberIgnored"));
        assertEquals("PRIVATE_DOCUMENT_EXTRACTED_TEXT", event.data().get("privacyClass"));
        assertEquals("DOCUMENT_EXTRACTION", event.data().get("source"));
        assertEquals(false, event.data().get("rawArtifactPersistenceAllowed"));
        assertEquals(false, event.data().get("ragIndexAllowed"));
        assertEquals("private document extraction scope", event.data().get("decisionReason"));
        assertTrue(event.data().containsKey("pathHint"), event.data().toString());
        assertFalse(MAPPER.writeValueAsString(trace).contains("Patient Name:"), trace.toString());
    }

    @Test
    void privateDocumentHandoffTraceEventConstructionIsOwnedByFactory() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/PrivateDocumentHandoffTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "private-document handoff trace event construction should have a dedicated owner");

        String capture = Files.readString(capturePath);
        String factory = Files.readString(factoryPath);
        assertTrue(capture.contains("PrivateDocumentHandoffTraceEventFactory."), capture);
        assertFalse(capture.contains("\"PRIVATE_DOCUMENT_MODEL_HANDOFF_"), capture);
        assertFalse(capture.contains("\"SEND_TO_MODEL_CONTEXT\""), capture);
        assertFalse(capture.contains("rawArtifactPersistenceAllowed"), capture);
        assertFalse(capture.contains("ragIndexAllowed"), capture);
        assertFalse(capture.contains("decisionReason"), capture);
        assertTrue(factory.contains("PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_REQUIRED"), factory);
        assertTrue(factory.contains("PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_GRANTED"), factory);
        assertTrue(factory.contains("PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_DENIED"), factory);
        assertTrue(factory.contains("SEND_TO_MODEL_CONTEXT"), factory);
        assertTrue(factory.contains("rawArtifactPersistenceAllowed"), factory);
        assertTrue(factory.contains("ragIndexAllowed"), factory);
        assertTrue(factory.contains("decisionReason"), factory);
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trc-private-document-handoff",
                "sid-private-document-handoff",
                1,
                "2026-05-28T12:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "Read medical-notes.docx and summarize it.");
    }
}
