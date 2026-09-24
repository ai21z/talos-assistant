package dev.talos.safety;

import java.util.Objects;

/** The synchronous turn owner carries restrictions to later derived messages and log arguments. */
public final class ContentRestrictionScope {
    private static final ThreadLocal<java.util.concurrent.atomic.AtomicReference<ContentRestrictions>> CURRENT = new ThreadLocal<>();
    private ContentRestrictionScope() {}

    public static void begin() { CURRENT.set(new java.util.concurrent.atomic.AtomicReference<>(ContentRestrictions.UNRESTRICTED)); }
    public static ContentRestrictions current() {
        var scope = CURRENT.get();
        return scope == null ? ContentRestrictions.UNRESTRICTED : scope.get();
    }
    public static void restrict(ContentRestrictions restrictions) {
        if (CURRENT.get() == null) throw new IllegalStateException("An active content scope is required.");
        Objects.requireNonNull(restrictions);
        CURRENT.get().updateAndGet(current -> current.intersect(restrictions));
    }
    public static void restrictIfActive(ContentRestrictions restrictions) {
        if (CURRENT.get() != null) restrict(restrictions);
    }
    public static Runnable propagate(Runnable work) {
        Objects.requireNonNull(work);
        var captured = CURRENT.get();
        return () -> {
            var prior = CURRENT.get();
            if (captured == null) CURRENT.remove(); else CURRENT.set(captured);
            try { work.run(); }
            finally { if (prior == null) CURRENT.remove(); else CURRENT.set(prior); }
        };
    }
    public static void end() { CURRENT.remove(); }
}
