package dev.talos.runtime.toolcall;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NativeToolCallConverter {
    private static final ObjectMapper ARGUMENT_MAPPER = new ObjectMapper();

    private NativeToolCallConverter() {}

    static List<ToolCall> convert(List<NativeToolCall> nativeCalls) {
        List<ToolCall> calls = new ArrayList<>(nativeCalls.size());
        for (NativeToolCall nativeCall : nativeCalls) {
            Map<String, String> params = new LinkedHashMap<>();
            if (nativeCall.arguments() != null) {
                for (var entry : nativeCall.arguments().entrySet()) {
                    params.put(entry.getKey(), argumentValue(entry.getValue()));
                }
            }
            calls.add(new ToolCall(nativeCall.name(), params));
        }
        return calls;
    }

    /**
     * Native tool-call arguments are deserialized to Maps/Lists by the
     * transport; {@code String.valueOf} rendered them as Java {@code toString}
     * ({@code [{op=mkdir, path=docs}]}) - not JSON - silently corrupting
     * container-valued params. Containers are re-serialized as JSON; scalars
     * keep the legacy text form (T744).
     */
    private static String argumentValue(Object value) {
        if (value instanceof Map || value instanceof List) {
            try {
                return ARGUMENT_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }
}
