package dev.loqj.cli.modes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ModeController}: alias registration,
 * mode switching, and chat alias behavior.
 */
class ModeControllerTest {

    // ── defaultController setup ──────────────────────────────────────────

    @Test
    void defaultController_has_auto_as_default_mode() {
        ModeController mc = ModeController.defaultController();
        assertEquals("auto", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_chat_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("chat"), "Should accept 'chat' as a valid mode");
        assertEquals("chat", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_ask_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("ask"), "Should accept 'ask' as a valid mode");
        assertEquals("ask", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_rag_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("rag"));
        assertEquals("rag", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_dev_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("dev"));
        assertEquals("dev", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_auto_mode() {
        ModeController mc = ModeController.defaultController();
        mc.setActive("rag"); // change first
        assertTrue(mc.setActive("auto"));
        assertEquals("auto", mc.getActiveName());
    }

    @Test
    void defaultController_rejects_unknown_mode() {
        ModeController mc = ModeController.defaultController();
        assertFalse(mc.setActive("nonexistent"));
        assertEquals("auto", mc.getActiveName(), "Should remain auto after rejection");
    }

    // ── Alias behavior ──────────────────────────────────────────────────

    @Test
    void chat_and_ask_resolve_to_same_mode_instance() {
        ModeController mc = ModeController.defaultController();

        mc.setActive("ask");
        var askMode = mc.getActive().orElse(null);

        mc.setActive("chat");
        var chatMode = mc.getActive().orElse(null);

        assertNotNull(askMode);
        assertNotNull(chatMode);
        assertSame(askMode, chatMode, "chat and ask should resolve to the same Mode instance");
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    void setActive_rejects_null() {
        ModeController mc = ModeController.defaultController();
        assertFalse(mc.setActive(null));
    }

    @Test
    void setActive_rejects_blank() {
        ModeController mc = ModeController.defaultController();
        assertFalse(mc.setActive(""));
        assertFalse(mc.setActive("   "));
    }

    @Test
    void setActive_is_case_insensitive() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("CHAT"));
        assertEquals("chat", mc.getActiveName());

        assertTrue(mc.setActive("Rag"));
        assertEquals("rag", mc.getActiveName());

        assertTrue(mc.setActive("AUTO"));
        assertEquals("auto", mc.getActiveName());
    }

    // ── Prompt refresh callback ──────────────────────────────────────────

    @Test
    void promptRefreshCallback_fires_on_mode_change() {
        ModeController mc = ModeController.defaultController();
        int[] callCount = {0};
        mc.setPromptRefreshCallback(() -> callCount[0]++);

        mc.setActive("rag");
        assertEquals(1, callCount[0]);

        mc.setActive("chat");
        assertEquals(2, callCount[0]);
    }

    @Test
    void promptRefreshCallback_does_not_fire_on_rejection() {
        ModeController mc = ModeController.defaultController();
        int[] callCount = {0};
        mc.setPromptRefreshCallback(() -> callCount[0]++);

        mc.setActive("nonexistent");
        assertEquals(0, callCount[0]);
    }
}

