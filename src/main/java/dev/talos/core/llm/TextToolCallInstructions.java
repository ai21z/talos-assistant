package dev.talos.core.llm;

import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;

import java.util.ArrayList;
import java.util.List;

/** Shared generation guidance for the existing textual tool-call format. */
public final class TextToolCallInstructions {
    private static final String RESOURCE = "prompts/sections/tools-text-format.txt";
    private static final String FALLBACK = """
            Textual tool-call format:
            - Put each tool call in its own closed JSON code block, opened with ```json and closed with ```.
            - Each block must contain exactly one complete JSON object with "name" and "parameters" keys.
            - For multiple calls, use separate closed JSON code blocks, one per call.
            - Do not put multiple top-level JSON objects or an array of calls in one block.
            """;

    private TextToolCallInstructions() {}

    public static String formatContract() {
        String resource = SystemPromptBuilder.readResource(RESOURCE);
        return (resource == null ? FALLBACK : resource).replace("\r\n", "\n").strip();
    }

    /** Adds generation syntax on a request copy, before its ordinary context-budget check. */
    static List<ChatMessage> forRequest(List<ChatMessage> messages,
                                        ProviderResponseCapture.ToolAuthorityMode authority,
                                        List<ToolSpec> selectedTools) {
        if (authority != ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON
                || selectedTools == null || selectedTools.isEmpty()) return messages;
        String contract = formatContract();
        int system = -1;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (!"system".equals(message.role()) || !message.contentRestrictions().modelAllowed()) continue;
            if (message.content() != null && message.content().contains(contract)) return messages;
            if (system < 0 || (!ContentRestrictions.UNRESTRICTED.equals(messages.get(system).contentRestrictions())
                    && ContentRestrictions.UNRESTRICTED.equals(message.contentRestrictions()))) {
                system = i;
            }
        }
        List<ChatMessage> copy = new ArrayList<>(messages);
        if (system < 0) {
            copy.add(0, ChatMessage.system(contract));
        } else {
            ChatMessage prior = copy.get(system);
            String content = prior.content() == null || prior.content().isEmpty()
                    ? contract : prior.content() + "\n\n" + contract;
            copy.set(system, new ChatMessage(prior.role(), content, prior.toolCalls(),
                    prior.toolCallId(), prior.contentRestrictions()));
        }
        return List.copyOf(copy);
    }
}
