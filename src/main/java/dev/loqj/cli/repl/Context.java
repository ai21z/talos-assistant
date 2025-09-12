package dev.loqj.cli.repl;

import dev.loqj.core.Audit;
import dev.loqj.core.Config;
import dev.loqj.core.net.NetPolicy;
import dev.loqj.core.security.Redactor;
import dev.loqj.core.security.Sandbox;
import dev.loqj.core.llm.LlmClient;
import dev.loqj.core.rag.RagService;

/**
 * Tiny DI container for the REPL. Immutable so it’s easy to pass around and test.
 * PR-1: Construct it in your bootstrap, but you don’t need to use it yet.
 */
public record Context(
        Config cfg,
        Audit audit,
        Redactor redactor,
        Sandbox sandbox,
        RagService rag,
        LlmClient llm,
        NetPolicy netPolicy
) {}
