package dev.talos.runtime.context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.types.ChatMessage;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Proposal decoding grants neither content selection nor execution permission. */
public record ContentDraftProposal(String kind, String text) {
    public static final int MAX_BYTES = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["kind","text"],"properties":{
              "kind":{"type":"string","enum":["draft","question"]},
              "text":{"type":"string","minLength":1}}}
            """;

    public static List<ChatMessage> messages(PendingContentDraft pending) {
        return List.of(ChatMessage.system("""
                A proposed text file is being prepared for human review, not execution.
                Return exactly one JSON object matching the supplied schema, without fences.
                For kind draft, text is the complete proposed file content, not a summary or a save claim.
                Use the complete original request, including constraints outside its save clause.
                If the requested content cannot be determined from this request alone, use kind question
                and ask one concise clarification instead of inventing missing source material.
                A missing filename alone does not prevent drafting, it will be requested separately.
                No tools, workspace contents or prior conversation are available. Do not claim otherwise.
                Content selection and normal file approval are handled separately by the runtime.
                The text is limited to 8192 characters and 16384 UTF-8 bytes.
                Required JSON schema:
                """ + SCHEMA), ChatMessage.user(pending.origin().intent().originalUserRequest()));
    }

    public static Optional<ContentDraftProposal> decode(String raw) {
        if (raw == null || raw.length() > 65_536) return Optional.empty();
        try {
            var node = JSON.readTree(raw);
            if (node == null || !node.isObject() || node.size() != 2
                    || !node.path("kind").isTextual() || !node.path("text").isTextual()) return Optional.empty();
            String kind = node.get("kind").textValue();
            String text = node.get("text").textValue();
            if (!Set.of("draft", "question").contains(kind) || !validText(text)) return Optional.empty();
            return Optional.of(new ContentDraftProposal(kind, text));
        } catch (Exception invalid) { return Optional.empty(); }
    }

    public static boolean validText(String text) {
        return dev.talos.runtime.intent.ContentSaveRequest.validDraftContent(text);
    }
}
