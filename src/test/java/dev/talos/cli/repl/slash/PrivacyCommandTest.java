package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivacyCommandTest {

    @Test
    void privacy_status_reports_current_mode(@TempDir Path workspace) throws Exception {
        Config cfg = new Config(null);
        PrivacyCommand command = new PrivacyCommand(workspace);

        Result result = command.execute("status", Context.builder(cfg).build());

        Result.Info info = assertInstanceOf(Result.Info.class, result);
        assertTrue(info.text.contains("mode: developer"), info.text);
        assertTrue(info.text.contains("protected read default scope: SEND_TO_MODEL_CONTEXT"), info.text);
        assertTrue(info.text.contains("approved protected reads can enter model context: yes"), info.text);
        assertTrue(info.text.contains("raw artifact persistence: off"), info.text);
        assertTrue(info.text.contains("private-mode document extraction model-context opt-in: disabled"), info.text);
        assertTrue(info.text.contains("private-mode document extraction raw artifact persistence: off"), info.text);
        assertTrue(info.text.contains("private-mode document extraction RAG indexing: disabled"), info.text);
    }

    @Test
    void privacy_private_on_switches_scope_to_local_display_only(@TempDir Path workspace) throws Exception {
        Config cfg = new Config(null);
        PrivacyCommand command = new PrivacyCommand(workspace);

        Result result = command.execute("private on", Context.builder(cfg).build());

        assertInstanceOf(Result.Info.class, result);
        assertTrue(ProtectedReadScopePolicy.privateMode(cfg));
        assertFalse(ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg));
        assertEquals(ProtectedReadScopePolicy.ProtectedReadScope.LOCAL_DISPLAY_ONLY,
                ProtectedReadScopePolicy.defaultScope(cfg));
    }

    @Test
    void privacy_private_off_restores_developer_default(@TempDir Path workspace) throws Exception {
        Config cfg = new Config(null);
        PrivacyCommand command = new PrivacyCommand(workspace);
        command.execute("private on", Context.builder(cfg).build());

        command.execute("private off", Context.builder(cfg).build());

        assertFalse(ProtectedReadScopePolicy.privateMode(cfg));
        assertTrue(ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg));
    }

    @Test
    void privacy_private_on_disables_retrieve_by_default(@TempDir Path workspace) throws Exception {
        Config cfg = new Config(null);
        PrivacyCommand command = new PrivacyCommand(workspace);

        command.execute("private on", Context.builder(cfg).build());

        assertFalse(ProtectedReadScopePolicy.ragEnabledInPrivateMode(cfg));
        Result.Info info = assertInstanceOf(Result.Info.class,
                command.execute("status", Context.builder(cfg).build()));
        assertTrue(info.text.contains("RAG/retrieve in private mode: disabled"), info.text);
    }

    @Test
    void privacy_status_does_not_mutate_workspace(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "public\n");
        Config cfg = new Config(null);
        PrivacyCommand command = new PrivacyCommand(workspace);

        command.execute("status", Context.builder(cfg).build());

        assertEquals("public\n", Files.readString(workspace.resolve("README.md")));
        assertEquals(1, Files.list(workspace).count());
    }

    @Test
    void private_mode_help_explains_model_context_and_artifacts(@TempDir Path workspace) throws Exception {
        PrivacyCommand command = new PrivacyCommand(workspace);

        Result.Info info = assertInstanceOf(Result.Info.class,
                command.execute("help", Context.builder(new Config(null)).build()));

        assertTrue(info.text.contains("model context"), info.text);
        assertTrue(info.text.contains("prompt-debug"), info.text);
        assertTrue(info.text.contains("session"), info.text);
        assertTrue(info.text.contains("Private document extraction"), info.text);
        assertTrue(info.text.contains("PDF/DOCX/XLS/XLSX"), info.text);
        assertTrue(info.text.contains("/privacy private on"), info.text);
    }

    @Test
    void privacy_private_on_is_session_scoped_unless_persistence_exists(@TempDir Path workspace) throws Exception {
        Config currentSession = new Config(null);
        PrivacyCommand command = new PrivacyCommand(workspace);

        command.execute("private on", Context.builder(currentSession).build());
        Config freshProcessConfig = new Config(null);

        assertTrue(ProtectedReadScopePolicy.privateMode(currentSession));
        assertFalse(ProtectedReadScopePolicy.privateMode(freshProcessConfig));
        Result.Info status = assertInstanceOf(Result.Info.class,
                command.execute("status", Context.builder(currentSession).build()));
        assertTrue(status.text.contains("current session/config state"), status.text);
        assertTrue(status.text.contains("~/.talos/config.yaml"), status.text);
    }

    @Test
    void privacy_help_mentions_persistence_semantics(@TempDir Path workspace) throws Exception {
        PrivacyCommand command = new PrivacyCommand(workspace);

        Result.Info info = assertInstanceOf(Result.Info.class,
                command.execute("help", Context.builder(new Config(null)).build()));

        assertTrue(info.text.contains("current session/config state"), info.text);
        assertTrue(info.text.contains("~/.talos/config.yaml"), info.text);
    }
}
