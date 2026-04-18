package dev.talos.runtime;

/**
 * Thread-local carrier for the current turn's latest user request.
 *
 * <p>Set by {@link TurnProcessor#process} at the start of a turn and
 * cleared in the finally block. Read by {@link TurnProcessor#executeTool}
 * so that runtime guards (notably {@link ScopeGuard}) can compare a
 * mutating tool target against the user's actual request without having
 * to thread the request string through every call site.
 *
 * <p>Follows the same pattern as {@link TurnTraceCapture}: a narrow,
 * per-thread handoff that keeps the public {@code executeTool} signature
 * stable for callers (including the tool-call loop and tests).
 *
 * <p>All methods are null-safe. {@link #get()} returns {@code null} when
 * no turn is active on the current thread.
 */
public final class TurnUserRequestCapture {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TurnUserRequestCapture() {}

    /** Record the current turn's user request. */
    public static void set(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) {
            HOLDER.remove();
        } else {
            HOLDER.set(userRequest);
        }
    }

    /** @return the current turn's user request, or {@code null} if none is set. */
    public static String get() {
        return HOLDER.get();
    }

    /** Clear the current turn's user request (call in a finally block). */
    public static void clear() {
        HOLDER.remove();
    }
}

