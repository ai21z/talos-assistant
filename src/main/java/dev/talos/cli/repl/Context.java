package dev.talos.cli.repl;

import dev.talos.core.Audit;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.net.NetPolicy;
import dev.talos.core.rag.RagService;
import dev.talos.core.security.Redactor;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** Runtime dependencies available to modes and commands. */
public record Context(
        Config cfg,
        Limits limits,
        SessionState session,
        Audit audit,
        Redactor redactor,
        Sandbox sandbox,
        RagService rag,
        LlmClient llm,
        NetPolicy netPolicy,
        SessionMemory memory,
        ApprovalGate approvalGate,
        ToolRegistry toolRegistry,
        ConversationManager conversationManager,
        ToolCallLoop toolCallLoop,
        Consumer<String> streamSink
) {
    /** Backward-compatible constructor without streamSink. */
    public Context(Config cfg, Limits limits, SessionState session, Audit audit,
                   Redactor redactor, Sandbox sandbox, RagService rag, LlmClient llm,
                   NetPolicy netPolicy, SessionMemory memory, ApprovalGate approvalGate,
                   ToolRegistry toolRegistry, ConversationManager conversationManager,
                   ToolCallLoop toolCallLoop) {
        this(cfg, limits, session, audit, redactor, sandbox, rag, llm, netPolicy,
             memory, approvalGate, toolRegistry, conversationManager, toolCallLoop, null);
    }

    /** Backward-compatible constructor without toolCallLoop or streamSink. */
    public Context(Config cfg, Limits limits, SessionState session, Audit audit,
                   Redactor redactor, Sandbox sandbox, RagService rag, LlmClient llm,
                   NetPolicy netPolicy, SessionMemory memory, ApprovalGate approvalGate,
                   ToolRegistry toolRegistry, ConversationManager conversationManager) {
        this(cfg, limits, session, audit, redactor, sandbox, rag, llm, netPolicy,
             memory, approvalGate, toolRegistry, conversationManager, null, null);
    }

    /** Backward-compatible constructor without conversationManager or toolCallLoop. */
    public Context(Config cfg, Limits limits, SessionState session, Audit audit,
                   Redactor redactor, Sandbox sandbox, RagService rag, LlmClient llm,
                   NetPolicy netPolicy, SessionMemory memory, ApprovalGate approvalGate,
                   ToolRegistry toolRegistry) {
        this(cfg, limits, session, audit, redactor, sandbox, rag, llm, netPolicy,
             memory, approvalGate, toolRegistry,
             new ConversationManager(memory != null ? memory : new SessionMemory(), TokenBudget.fromConfig(cfg)));
    }

    /** Backward-compatible constructor without toolRegistry, conversationManager, or toolCallLoop. */
    public Context(Config cfg, Limits limits, SessionState session, Audit audit,
                   Redactor redactor, Sandbox sandbox, RagService rag, LlmClient llm,
                   NetPolicy netPolicy, SessionMemory memory, ApprovalGate approvalGate) {
        this(cfg, limits, session, audit, redactor, sandbox, rag, llm, netPolicy,
             memory, approvalGate, new ToolRegistry());
    }

    /** Fluent builder for tests and advanced wiring. Prefer explicit setter calls over withDefaults in prod. */
    public static Builder builder(Config cfg) { return new Builder(cfg); }

    public static final class Builder {
        private final Config cfg;
        private Limits limits;
        private SessionState session;
        private Audit audit;
        private Redactor redactor;
        private Sandbox sandbox;
        private RagService rag;
        private LlmClient llm;
        private NetPolicy net;
        private SessionMemory memory;
        private ApprovalGate approvalGate;
        private ToolRegistry toolRegistry;
        private ConversationManager conversationManager;
        private ToolCallLoop toolCallLoop;
        private Consumer<String> streamSink;

        public Builder(Config cfg) { this.cfg = (cfg == null ? new Config() : cfg); }

        public Builder limits(Limits l)              { this.limits = l; return this; }
        public Builder session(SessionState s)       { this.session = s; return this; }
        public Builder audit(Audit a)                { this.audit = a; return this; }
        public Builder redactor(Redactor r)          { this.redactor = r; return this; }
        public Builder sandbox(Sandbox s)            { this.sandbox = s; return this; }
        public Builder rag(RagService r)             { this.rag = r; return this; }
        public Builder llm(LlmClient l)              { this.llm = l; return this; }
        public Builder netPolicy(NetPolicy n)        { this.net = n; return this; }
        public Builder memory(SessionMemory m)       { this.memory = m; return this; }
        public Builder approvalGate(ApprovalGate g)  { this.approvalGate = g; return this; }
        public Builder toolRegistry(ToolRegistry t)  { this.toolRegistry = t; return this; }
        public Builder conversationManager(ConversationManager cm) { this.conversationManager = cm; return this; }
        public Builder toolCallLoop(ToolCallLoop l)  { this.toolCallLoop = l; return this; }
        public Builder streamSink(Consumer<String> s) { this.streamSink = s; return this; }

        /** Convenience for ad-hoc usage; tests should prefer explicit setters for control. */
        public Builder withDefaults(Path workspace, SessionState session) {
            if (this.limits == null)   this.limits   = Limits.fromConfig(cfg);
            if (this.session == null)  this.session  = session;

            Redactor red = (this.redactor != null ? this.redactor : new Redactor());
            Sandbox sbx = (this.sandbox != null ? this.sandbox : new Sandbox(
                    (workspace == null ? Path.of(".") : workspace), Map.of()
            ));
            if (this.redactor == null) this.redactor = red;
            if (this.sandbox == null)  this.sandbox  = sbx;
            if (this.audit == null)    this.audit    = new Audit();
            if (this.rag == null)      this.rag      = new RagService(cfg);
            if (this.llm == null)      this.llm      = new LlmClient(cfg);
            if (this.net == null)      this.net      = new NetPolicy(cfg);
            if (this.memory == null)   this.memory   = new SessionMemory();
            if (this.approvalGate == null) this.approvalGate = new NoOpApprovalGate();
            if (this.toolRegistry == null) this.toolRegistry = new ToolRegistry();
            if (this.conversationManager == null) this.conversationManager =
                    new ConversationManager(this.memory, TokenBudget.fromConfig(cfg));
            return this;
        }

        public Context build() {
            if (limits == null)   limits   = Limits.fromConfig(cfg);
            if (session == null)  session  = new SessionState() {
                private int k = 8; private boolean dbg;
                public int getK() { return k; } public void setK(int v){k=v;}
                public boolean isDebug(){return dbg;} public void setDebug(boolean on){dbg=on;}
            };
            if (audit == null)    audit    = new Audit();
            if (redactor == null) redactor = new Redactor();
            if (sandbox == null)  sandbox  = new Sandbox(Path.of("."), Map.of());
            if (rag == null)      rag      = new RagService(cfg);
            if (llm == null)      llm      = new LlmClient(cfg);
            if (net == null)      net      = new NetPolicy(cfg);
            if (memory == null)   memory   = new SessionMemory();
            if (approvalGate == null) approvalGate = new NoOpApprovalGate();
            if (toolRegistry == null) toolRegistry = new ToolRegistry();
            if (conversationManager == null) conversationManager =
                    new ConversationManager(memory, TokenBudget.fromConfig(cfg));

            return new Context(cfg, limits, session, audit, redactor, sandbox, rag, llm, net,
                    memory, approvalGate, toolRegistry, conversationManager, toolCallLoop, streamSink);
        }
    }
}
