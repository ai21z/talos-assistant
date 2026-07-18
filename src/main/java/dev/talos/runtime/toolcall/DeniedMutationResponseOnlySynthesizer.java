package dev.talos.runtime.toolcall;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallParser;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DeniedMutationResponseOnlySynthesizer {
    private static final Logger LOG = LoggerFactory.getLogger(DeniedMutationResponseOnlySynthesizer.class);
    private static final String POLICY_STOP_PROMPT_PREFIX = "[Tool policy stop]";

    private DeniedMutationResponseOnlySynthesizer() {}

    record Result(
            String text,
            ProviderResponseCapture.Receipt receipt,
            boolean capturedGeneration) {
        Result {
            text = text == null ? "" : text;
            receipt = receipt == null ? ProviderResponseCapture.Receipt.none() : receipt;
        }

        static Result deterministicStop() {
            return new Result(stopMessage(), ProviderResponseCapture.Receipt.none(), false);
        }
    }

    static Result synthesize(LoopState state) {
        if (state == null || state.ctx == null || state.ctx.llm() == null) {
            return Result.deterministicStop();
        }

        state.messages.add(ChatMessage.system(
                POLICY_STOP_PROMPT_PREFIX + " The latest mutating tool call was rejected by Talos policy. "
                        + "Do not call any more tools in this turn. Answer the user's request using only "
                        + "the tool results already gathered. If the gathered evidence is insufficient, "
                        + "say exactly what was inspected and what remains unknown."));
        int anchorIndex = state.messages.size() - 1;

        try {
            LlmClient.StreamResult terminal =
                    state.ctx.llm().chatFull(state.messages, state.ctx.nativeToolSpecs());
            String text = terminal.text() == null ? "" : terminal.text();
            if (terminal.hasToolCalls()) {
                return Result.deterministicStop();
            }
            String stripped = ToolCallParser.stripToolCalls(text).strip();
            if (stripped.isBlank() || ToolCallParser.containsToolCalls(text)) {
                return Result.deterministicStop();
            }
            return new Result(stripped, terminal.provenance(), true);
        } catch (Exception e) {
            LOG.warn("Response-only synthesis after denied mutation failed: {}", SafeLogFormatter.throwableMessage(e));
            return Result.deterministicStop();
        } finally {
            if (anchorIndex < state.messages.size()) {
                ChatMessage m = state.messages.get(anchorIndex);
                if ("system".equals(m.role())
                        && m.content() != null
                        && m.content().startsWith(POLICY_STOP_PROMPT_PREFIX)) {
                    state.messages.remove(anchorIndex);
                }
            }
        }
    }

    static String stopMessage() {
        return "[Tool loop stopped because a mutating tool was not allowed for this turn.]";
    }
}
