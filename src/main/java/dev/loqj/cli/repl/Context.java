package dev.loqj.cli.repl;

import dev.loqj.core.Audit;
import dev.loqj.core.Config;
import dev.loqj.core.llm.LlmClient;
import dev.loqj.core.net.NetPolicy;
import dev.loqj.core.rag.RagService;
import dev.loqj.core.security.Redactor;
import dev.loqj.core.security.Sandbox;

import java.nio.file.Path;
import java.util.Map;

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
        NetPolicy netPolicy
) {
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

        public Builder(Config cfg) { this.cfg = (cfg == null ? new Config() : cfg); }

        public Builder limits(Limits l)              { this.limits = l; return this; }
        public Builder session(SessionState s)       { this.session = s; return this; }
        public Builder audit(Audit a)                { this.audit = a; return this; }
        public Builder redactor(Redactor r)          { this.redactor = r; return this; }
        public Builder sandbox(Sandbox s)            { this.sandbox = s; return this; }
        public Builder rag(RagService r)             { this.rag = r; return this; }
        public Builder llm(LlmClient l)              { this.llm = l; return this; }
        public Builder netPolicy(NetPolicy n)        { this.net = n; return this; }

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

            return new Context(cfg, limits, session, audit, redactor, sandbox, rag, llm, net);
        }
    }
}
