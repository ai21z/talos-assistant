package dev.talos.cli.tune;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final String LEGACY_CONFIG_WITHOUT_CONTEXT = """
            llm:
              transport: "engine"
              default_backend: "llama_cpp"
              model: "qwen2.5-coder-14b"

            engines:
              llama_cpp:
                mode: "managed"
                server_path: "C:/old/llama-server.exe"
                model_path: ""
                model: "qwen2.5-coder-14b"
                host: "http://127.0.0.1"
                port: 18115
                jinja: true
                server_args: []

            tools:
              native_calling: true
            """;

    @Test
    void legacyConfigWithoutContextKeyGetsContextAndReasonInsertedAfterPort() {
        TuneConfigEditor.Edit edit = TuneConfigEditor.propose(
                LEGACY_CONFIG_WITHOUT_CONTEXT,
                Path.of("C:/x/llama-server.exe"),
                16_384,
                "reason");

        String updated = edit.updatedYaml();
        assertTrue(updated.contains("    context: 16384"), updated);
        assertTrue(updated.contains("    context_reason: \"reason\""), updated);
        int portIdx = updated.indexOf("port: 18115");
        int contextIdx = updated.indexOf("context: 16384");
        int reasonIdx = updated.indexOf("context_reason:");
        int jinjaIdx = updated.indexOf("jinja: true");
        assertTrue(portIdx >= 0 && portIdx < contextIdx && contextIdx < reasonIdx && reasonIdx < jinjaIdx,
                "context/context_reason must be inserted after port:\n" + updated);
        assertTrue(edit.diff().contains("+     context: 16384"), edit.diff());
        assertTrue(TuneConfigEditor.appliesProposal(
                updated, Path.of("C:/x/llama-server.exe"), 16_384, "reason"), updated);
    }

    @Test
    void reasonWithoutContextKeyGetsRealContextInsertedNotJustTheReason() {
        String reasonOnly = LEGACY_CONFIG_WITHOUT_CONTEXT.replace(
                "    port: 18115\n",
                "    port: 18115\n    context_reason: \"stale old reason\"\n");

        TuneConfigEditor.Edit edit = TuneConfigEditor.propose(
                reasonOnly, Path.of("C:/x/llama-server.exe"), 16_384, "new reason");

        String updated = edit.updatedYaml();
        assertTrue(updated.contains("context: 16384"),
                "a proposed reason must never be written without its context value:\n" + updated);
        assertTrue(updated.contains("context_reason: \"new reason\""), updated);
        assertFalse(updated.contains("stale old reason"), updated);
        assertTrue(TuneConfigEditor.appliesProposal(
                updated, Path.of("C:/x/llama-server.exe"), 16_384, "new reason"), updated);
    }

    @Test
    void serverArgsListWithBlankLinesInsideIsFullyReplaced() {
        String blanksInside = OWNER_SHAPED_CONFIG.replace(
                "    server_args:\n      - \"-ngl\"\n      - \"99\"\n",
                "    server_args:\n      - \"-ngl\"\n\n      - \"99\"\n");

        TuneConfigEditor.Edit edit = TuneConfigEditor.propose(
                blanksInside, Path.of("C:/x/llama-server.exe"), 16_384, "reason");

        String updated = edit.updatedYaml();
        assertTrue(updated.contains("server_args: []"), updated);
        assertFalse(updated.contains("-ngl"), "orphaned list items must not survive:\n" + updated);
        assertFalse(updated.contains("\"99\""), updated);
        assertTrue(updated.contains("\ntools:"), "the section after the list must survive:\n" + updated);
    }

    @Test
    void missingServerArgsKeyIsInserted() {
        String noServerArgs = LEGACY_CONFIG_WITHOUT_CONTEXT.replace("    server_args: []\n", "");

        TuneConfigEditor.Edit edit = TuneConfigEditor.propose(
                noServerArgs, Path.of("C:/x/llama-server.exe"), 16_384, "reason");

        assertTrue(edit.updatedYaml().contains("server_args: []"), edit.updatedYaml());
        assertTrue(TuneConfigEditor.appliesProposal(
                edit.updatedYaml(), Path.of("C:/x/llama-server.exe"), 16_384, "reason"),
                edit.updatedYaml());
    }

    @Test
    void appliesProposalIsSemanticNotLexical() {
        assertFalse(TuneConfigEditor.appliesProposal(
                LEGACY_CONFIG_WITHOUT_CONTEXT, Path.of("C:/x/llama-server.exe"), 16_384, "reason"),
                "a config missing the proposed context must not count as applied");
        assertFalse(TuneConfigEditor.appliesProposal(
                OWNER_SHAPED_CONFIG, Path.of("C:/x/llama-server.exe"), 16_384, "reason"),
                "different server_path and non-empty server_args must not count as applied");
    }

    @Test
    void editableReasonRejectsUnmanagedConfigs() {
        assertTrue(TuneConfigEditor.editableReason(OWNER_SHAPED_CONFIG).isBlank(),
                TuneConfigEditor.editableReason(OWNER_SHAPED_CONFIG));
        assertFalse(TuneConfigEditor.editableReason(
                OWNER_SHAPED_CONFIG.replace("mode: \"managed\"", "mode: \"connect-only\"")).isBlank(),
                "connect-only configs are not tune-editable");
        assertFalse(TuneConfigEditor.editableReason("llm:\n  model: \"x\"\n").isBlank(),
                "configs without an engines.llama_cpp block are not tune-editable");
        String noServerPath = OWNER_SHAPED_CONFIG.replace(
                "    server_path: \"C:/Users/user/.talos/runtimes/llama.cpp/b9918/cpu-x64/llama-server.exe\"\n",
                "");
        assertFalse(TuneConfigEditor.editableReason(noServerPath).isBlank(),
                "configs without a server_path key are not tune-editable");
    }
}
