package dev.talos.core.context;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TokenBudget#fromConfig(Config)} — ensures all paths
 * that construct a budget use the same config key and default.
 */
class TokenBudgetFromConfigTest {

    @Test
    void fromConfig_readsLimitsContextMaxTokens() {
        Config cfg = new Config();
        cfg.data.put("limits", Map.of("llm_context_max_tokens", 4096));

        TokenBudget budget = TokenBudget.fromConfig(cfg);

        assertEquals(4096, budget.contextMaxTokens());
    }

    @Test
    void fromConfig_fallsBackToDefault_whenLimitsMissing() {
        Config cfg = new Config();
        // no "limits" key at all

        TokenBudget budget = TokenBudget.fromConfig(cfg);

        assertEquals(TokenBudget.DEFAULT_CONTEXT_MAX_TOKENS, budget.contextMaxTokens());
    }

    @Test
    void fromConfig_fallsBackToDefault_whenKeyMissing() {
        Config cfg = new Config();
        cfg.data.put("limits", Map.of("some_other_key", 999));

        TokenBudget budget = TokenBudget.fromConfig(cfg);

        assertEquals(TokenBudget.DEFAULT_CONTEXT_MAX_TOKENS, budget.contextMaxTokens());
    }

    @Test
    void fromConfig_usesDefaultReserveAndOverhead() {
        Config cfg = new Config();
        cfg.data.put("limits", Map.of("llm_context_max_tokens", 16384));

        TokenBudget budget = TokenBudget.fromConfig(cfg);

        assertEquals(TokenBudget.DEFAULT_RESPONSE_RESERVE, budget.responseReserveFraction());
        assertEquals(TokenBudget.DEFAULT_OVERHEAD_TOKENS, budget.overheadTokens());
    }
}

