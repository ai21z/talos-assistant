package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallParser;
import dev.talos.core.tool.ToolProtocolText;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class ToolCallParseStage {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCallParseStage.class);

    public record ParsedCalls(
            boolean useNativePath,
            boolean useTextPath,
            List<ToolCall> calls,
            List<LocalTurnTraceCapture.ParsedToolCallLineage> lineages
    ) {
        public ParsedCalls {
            calls = calls == null ? List.of() : List.copyOf(calls);
            lineages = lineages == null ? List.of() : List.copyOf(lineages);
            if (calls.size() != lineages.size()) {
                throw new IllegalArgumentException(
                        "every parsed tool call requires one parser-lineage entry");
            }
        }

        public ParsedCalls(
                boolean useNativePath,
                boolean useTextPath,
                List<ToolCall> calls
        ) {
            this(
                    useNativePath,
                    useTextPath,
                    calls,
                    unboundLineages(calls));
        }

        public LocalTurnTraceCapture.ParsedToolCallLineage lineage(int index) {
            return index < 0 || index >= lineages.size() ? null : lineages.get(index);
        }
    }

    public ParsedCalls parse(String currentText, List<NativeToolCall> currentNativeCalls, int iteration) {
        return parse(
                currentText,
                currentNativeCalls,
                ProviderResponseCapture.Receipt.none(),
                LocalTurnTraceCapture.ToolCallSource.UNBOUND,
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.NONE,
                iteration);
    }

    public ParsedCalls parse(LoopState state, int iteration) {
        if (state == null) {
            return new ParsedCalls(false, false, List.of(), List.of());
        }
        return parse(
                state.currentText,
                state.currentNativeCalls,
                state.currentResponseReceipt,
                state.currentToolCallSource,
                state.currentRuntimeToolAuthorityReason,
                iteration);
    }

    private ParsedCalls parse(
            String currentText,
            List<NativeToolCall> currentNativeCalls,
            ProviderResponseCapture.Receipt receipt,
            LocalTurnTraceCapture.ToolCallSource source,
            LocalTurnTraceCapture.RuntimeToolAuthorityReason runtimeReason,
            int iteration
    ) {
        boolean useNativePath = currentNativeCalls != null && !currentNativeCalls.isEmpty();
        boolean useTextPath = !useNativePath && ToolCallParser.containsToolCalls(currentText);
        if (!useNativePath && !useTextPath) {
            return new ParsedCalls(false, false, List.of(), List.of());
        }

        List<ToolCall> calls;
        List<LocalTurnTraceCapture.ParsedToolCallLineage> lineages;
        if (useNativePath) {
            calls = ToolCallSupport.convertNativeToolCalls(new ArrayList<>(currentNativeCalls));
            LOG.debug("Tool-call loop iteration {}: {} native tool call(s)", iteration, calls.size());
            lineages = nativeLineages(
                    calls, currentNativeCalls, receipt, source, runtimeReason);
        } else if (source == LocalTurnTraceCapture.ToolCallSource.PROVIDER_RESPONSE) {
            LocalTurnTraceCapture.ParsedToolCallBatch batch =
                    LocalTurnTraceCapture.captureProviderTextToolCallBatch(
                            receipt, currentText);
            calls = batch.calls();
            lineages = batch.lineages();
            LOG.debug("Tool-call loop iteration {}: {} text tool call(s)", iteration, calls.size());
        } else {
            calls = ToolCallParser.parse(currentText);
            LOG.debug("Tool-call loop iteration {}: {} text tool call(s)", iteration, calls.size());
            lineages = nonProviderTextLineages(
                    calls, currentText, source, runtimeReason);
        }
        return new ParsedCalls(useNativePath, useTextPath, calls, lineages);
    }

    private static List<LocalTurnTraceCapture.ParsedToolCallLineage> nativeLineages(
            List<ToolCall> calls,
            List<NativeToolCall> nativeCalls,
            ProviderResponseCapture.Receipt receipt,
            LocalTurnTraceCapture.ToolCallSource source,
            LocalTurnTraceCapture.RuntimeToolAuthorityReason runtimeReason
    ) {
        List<LocalTurnTraceCapture.ParsedToolCallLineage> lineages =
                new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            ToolCall call = calls.get(index);
            if (source == LocalTurnTraceCapture.ToolCallSource.PROVIDER_RESPONSE) {
                lineages.add(
                        LocalTurnTraceCapture.captureProviderNativeToolCallLineage(
                                receipt,
                                call,
                                index < nativeCalls.size() ? nativeCalls.get(index) : null,
                                index + 1));
            } else if (source
                    == LocalTurnTraceCapture.ToolCallSource.RUNTIME_DETERMINISTIC) {
                lineages.add(LocalTurnTraceCapture.captureRuntimeToolCallLineage(
                        LocalTurnTraceCapture.ToolCallParseFormat.NATIVE,
                        runtimeReason,
                        call,
                        "",
                        index + 1));
            } else {
                lineages.add(LocalTurnTraceCapture.captureUnboundToolCallLineage(
                        call, index + 1));
            }
        }
        return List.copyOf(lineages);
    }

    private static List<LocalTurnTraceCapture.ParsedToolCallLineage>
            nonProviderTextLineages(
                    List<ToolCall> calls,
                    String currentText,
                    LocalTurnTraceCapture.ToolCallSource source,
                    LocalTurnTraceCapture.RuntimeToolAuthorityReason runtimeReason
            ) {
        List<LocalTurnTraceCapture.ParsedToolCallLineage> lineages =
                new ArrayList<>(calls.size());
        LocalTurnTraceCapture.ToolCallParseFormat format =
                ToolProtocolText.containsRecognizedXmlToolCall(currentText)
                        ? LocalTurnTraceCapture.ToolCallParseFormat.XML_COMPAT
                        : LocalTurnTraceCapture.ToolCallParseFormat.JSON_TEXT;
        for (int index = 0; index < calls.size(); index++) {
            ToolCall call = calls.get(index);
            if (source == LocalTurnTraceCapture.ToolCallSource.RUNTIME_DETERMINISTIC) {
                lineages.add(LocalTurnTraceCapture.captureRuntimeToolCallLineage(
                        format,
                        runtimeReason,
                        call,
                        currentText,
                        index + 1));
            } else {
                lineages.add(LocalTurnTraceCapture.captureUnboundToolCallLineage(
                        call, index + 1));
            }
        }
        return List.copyOf(lineages);
    }

    private static List<LocalTurnTraceCapture.ParsedToolCallLineage> unboundLineages(
            List<ToolCall> calls
    ) {
        if (calls == null || calls.isEmpty()) return List.of();
        List<LocalTurnTraceCapture.ParsedToolCallLineage> lineages =
                new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            lineages.add(LocalTurnTraceCapture.captureUnboundToolCallLineage(
                    calls.get(index), index + 1));
        }
        return List.copyOf(lineages);
    }
}
