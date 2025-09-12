package dev.loqj.cli.repl;

import dev.loqj.core.Audit;

/**
 * Central place to wrap execution with cross-cutting concerns.
 * PR-1 keeps it minimal (no behavior change); later PRs can add:
 *  - audit start/end
 *  - rate limiting
 *  - timeouts
 *  - sandbox checks (when applicable)
 */
public final class ExecutionPipeline {

    public Result run(CommandInvoker invoker, Context ctx, String eventName) {
        // Future: ctx.audit().log("start", ...);
        try {
            Result r = invoker.invoke();
            // Future: redact here only for audit payloads; user-output redaction is in RenderEngine.
            // Future: ctx.audit().log("end", ...);
            return r;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Result.Error("Interrupted.", 399);
        } catch (Exception e) {
            // Do NOT expose stack traces to user here; RenderEngine will present a clean message.
            Audit audit = ctx.audit();
            if (audit != null) {
                audit.log("error", java.util.Map.of(
                        "event", eventName == null ? "" : eventName,
                        "type", e.getClass().getSimpleName(),
                        "msg", e.getMessage() == null ? "" : e.getMessage()
                ));
            }
            return new Result.Error("Unexpected error; try again with :debug if needed.", 599);
        }
    }
}
