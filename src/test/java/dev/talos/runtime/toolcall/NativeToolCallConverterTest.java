package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeToolCallConverterTest {

    @Test
    void convertsContainerArgumentsToJsonAndScalarsToLegacyText() {
        var operation = new LinkedHashMap<String, Object>();
        operation.put("op", "mkdir");
        operation.put("path", "docs");
        var nativeCall = new NativeToolCall(
                "call-1",
                "talos.apply_workspace_batch",
                Map.of(
                        "operations", List.of(operation),
                        "dry_run", false,
                        "retries", 2));

        List<ToolCall> calls = NativeToolCallConverter.convert(List.of(nativeCall));

        assertAll(
                () -> assertEquals(1, calls.size()),
                () -> assertEquals("talos.apply_workspace_batch", calls.get(0).toolName()),
                () -> assertEquals("[{\"op\":\"mkdir\",\"path\":\"docs\"}]",
                        calls.get(0).param("operations")),
                () -> assertEquals("false", calls.get(0).param("dry_run")),
                () -> assertEquals("2", calls.get(0).param("retries")));
    }

    @Test
    void nullNativeArgumentsProduceEmptyParameters() {
        var nativeCall = new NativeToolCall("call-1", "talos.list_dir", null);

        List<ToolCall> calls = NativeToolCallConverter.convert(List.of(nativeCall));

        assertAll(
                () -> assertEquals(1, calls.size()),
                () -> assertEquals("talos.list_dir", calls.get(0).toolName()),
                () -> assertTrue(calls.get(0).parameters().isEmpty()));
    }

    @Test
    void jsonSerializationFailureFallsBackToStringValue() {
        var cyclic = new ArrayList<Object>();
        cyclic.add(cyclic);
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("loop", cyclic);
        var nativeCall = new NativeToolCall("call-1", "talos.write_file", arguments);

        List<ToolCall> calls = NativeToolCallConverter.convert(List.of(nativeCall));

        assertEquals("[(this Collection)]", calls.get(0).param("loop"));
    }

    @Test
    void toolCallSupportDelegatePreservesPublicCompatibility() {
        var nativeCall = new NativeToolCall(
                "call-1",
                "talos.read_file",
                Map.of("path", "README.md"));

        List<ToolCall> calls = ToolCallSupport.convertNativeToolCalls(List.of(nativeCall));

        assertAll(
                () -> assertEquals(1, calls.size()),
                () -> assertEquals("talos.read_file", calls.get(0).toolName()),
                () -> assertEquals("README.md", calls.get(0).param("path")));
    }
}
