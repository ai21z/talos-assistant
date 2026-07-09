package dev.talos.cli.tune;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuneConfigEditorTest {

    private static final String OWNER_SHAPED_CONFIG = """
            llm:
              transport: "engine"
              default_backend: "llama_cpp"
              model: "qwen2.5-coder-14b"

            engines:
              llama_cpp:
                mode: "managed"
                server_path: "C:/Users/user/.talos/runtimes/llama.cpp/b9918/cpu-x64/llama-server.exe"
                model_path: "C:/Users/user/models/qwen2.5-coder-14b-instruct-q4_k_m.gguf"
                hf_repo: ""
                hf_file: ""
                hf_cache_dir: ""
                model: "qwen2.5-coder-14b"
                host: "http://127.0.0.1"
                port: 18115
                context: 8192
                jinja: true
                server_args:
                  - "-ngl"
                  - "99"

            tools:
              native_calling: true

            rag:
              vectors:
                enabled: false
            """;

    @Test
    void surgicalEditReplacesOnlyLaneContextAndServerArgs() {
        TuneConfigEditor.Edit edit = TuneConfigEditor.propose(
                OWNER_SHAPED_CONFIG,
                Path.of("C:/Users/user/.talos/engines/llama.cpp/b9918/win-x64-cuda-13.3/llama-server.exe"),
                16_384,
                "16384 selected: measured VRAM meets the 16 GB lane floor");

        String updated = edit.updatedYaml();
        assertTrue(updated.contains(
                "server_path: \"C:/Users/user/.talos/engines/llama.cpp/b9918/win-x64-cuda-13.3/llama-server.exe\""),
                updated);
        assertTrue(updated.contains("context: 16384"), updated);
        assertTrue(updated.contains(
                "context_reason: \"16384 selected: measured VRAM meets the 16 GB lane floor\""), updated);
        assertTrue(updated.contains("server_args: []"), updated);
        assertTrue(!updated.contains("-ngl"), "old server_args list items must be removed:\n" + updated);
        assertTrue(!updated.contains("\"99\""), updated);

        assertTrue(updated.contains("model_path: \"C:/Users/user/models/qwen2.5-coder-14b-instruct-q4_k_m.gguf\""),
                "model identity must be preserved verbatim");
        assertTrue(updated.contains("port: 18115"), updated);
        assertTrue(updated.contains("native_calling: true"), updated);
        assertTrue(updated.contains("enabled: false"), updated);
    }

    @Test
    void editIsIdempotentOnAlreadyTunedConfig() {
        TuneConfigEditor.Edit first = TuneConfigEditor.propose(
                OWNER_SHAPED_CONFIG,
                Path.of("C:/x/llama-server.exe"),
                16_384,
                "reason");
        TuneConfigEditor.Edit second = TuneConfigEditor.propose(
                first.updatedYaml(),
                Path.of("C:/x/llama-server.exe"),
                16_384,
                "reason");

        assertEquals(first.updatedYaml(), second.updatedYaml());
        assertTrue(second.diff().isBlank(), "an already tuned config must produce an empty diff");
    }

    @Test
    void diffShowsRemovedAndAddedLinesOnly() {
        TuneConfigEditor.Edit edit = TuneConfigEditor.propose(
                OWNER_SHAPED_CONFIG,
                Path.of("C:/x/llama-server.exe"),
                16_384,
                "reason");

        String diff = edit.diff();
        assertTrue(diff.contains("- ") && diff.contains("+ "), diff);
        assertTrue(diff.contains("+     context: 16384"), diff);
        assertTrue(diff.contains("-     context: 8192"), diff);
        assertTrue(diff.contains("+     server_args: []"), diff);
        assertTrue(!diff.contains("model_path"), "unchanged lines must not appear in the diff:\n" + diff);
    }

    @Test
    void portIsReadFromTheConfigForVerifyLogLookup() {
        assertEquals(18_115, TuneConfigEditor.configuredPort(OWNER_SHAPED_CONFIG, 9999));
        assertEquals(9_999, TuneConfigEditor.configuredPort("llm:\n  model: x\n", 9_999));
    }
}
