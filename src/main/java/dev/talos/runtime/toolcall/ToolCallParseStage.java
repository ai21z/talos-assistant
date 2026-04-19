package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallParser;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class ToolCallParseStage {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallParseStage.class);

    public record ParsedCalls(boolean useNativePath, boolean useTextPath, List<ToolCall> calls) {}

    public ParsedCalls parse(String currentText, List<NativeToolCall> currentNativeCalls, int iteration) {
        boolean useNativePath = currentNativeCalls != null && !currentNativeCalls.isEmpty();
        boolean useTextPath = !useNativePath && ToolCallParser.containsToolCalls(currentText);
        if (!useNativePath && !useTextPath) {
            return new ParsedCalls(false, false, List.of());
        }

        List<ToolCall> calls;
        if (useNativePath) {
            calls = ToolCallSupport.convertNativeToolCalls(new ArrayList<>(currentNativeCalls));
            LOG.debug("Tool-call loop iteration {}: {} native tool call(s)", iteration, calls.size());
        } else {
            calls = ToolCallParser.parse(currentText);
            LOG.debug("Tool-call loop iteration {}: {} text tool call(s)", iteration, calls.size());
        }
        return new ParsedCalls(useNativePath, useTextPath, calls);
    }
}
