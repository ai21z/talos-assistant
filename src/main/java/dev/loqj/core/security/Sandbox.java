package dev.loqj.core.security;

import dev.loqj.core.CfgUtil;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Minimal, local-only sandbox focused on "workspace-only" path policy.
 * - Symlink-aware: resolves real targets and checks they live under workspace.
 * - Non-throwing: returns boolean + explain(reason) for user-friendly messages.
 * - Config hooks (optional; safe defaults):
 *     policy.enabled = true
 *     policy.allow   = []  (relative prefixes; if present, path must start with one)
 *     policy.deny    = []  (relative prefixes to block even inside workspace)
 */
public final class Sandbox {

    private final Path workspaceReal;
    private final boolean enabled;
    private final List<String> allow;
    private final List<String> deny;

    @SuppressWarnings("unchecked")
    public Sandbox(Path workspace, Map<String,Object> cfg) {
        Path resolvedWorkspace;
        try {
            resolvedWorkspace = (workspace == null ? Path.of(".") : workspace)
                    .toAbsolutePath().normalize().toRealPath();
        } catch (Exception e) {
            resolvedWorkspace = (workspace == null ? Path.of(".") : workspace)
                    .toAbsolutePath().normalize();
        }
        this.workspaceReal = resolvedWorkspace;

        Map<String,Object> policy = CfgUtil.map(cfg == null ? null : cfg.get("policy"));
        this.enabled = policy == null || !policy.containsKey("enabled") || Boolean.TRUE.equals(policy.get("enabled"));

        List<String> a = List.of();
        List<String> d = List.of();
        if (policy != null) {
            Object ao = policy.get("allow");
            if (ao instanceof List<?> xs) a = xs.stream().map(String::valueOf).map(Sandbox::cleanRel).toList();
            Object do_ = policy.get("deny");
            if (do_ instanceof List<?> xs) d = xs.stream().map(String::valueOf).map(Sandbox::cleanRel).toList();
        }
        this.allow = a;
        this.deny  = d;
    }

    /** Quick on/off for callers. */
    public boolean isEnabled() { return enabled; }

    /** Main predicate: is a candidate path allowed under the workspace policy? */
    public boolean allowedPath(Path candidate) {
        return allowedPathInternal(candidate).allowed;
    }

    /** If blocked, returns a short human-readable reason. */
    public String explain(Path candidate) {
        Decision d = allowedPathInternal(candidate);
        return d.allowed ? "allowed" : d.reason;
    }

    /** Reserved for future command gating; permissive for now. */
    public boolean allowedCommand(String verb) {
        return true;
    }

    /* ---------- internals ---------- */

    private Decision allowedPathInternal(Path p) {
        if (!enabled) return Decision.allow();
        if (p == null) return Decision.deny("null path");

        // Resolve target; if it doesn't exist yet, resolve parent + filename.
        Path real;
        try {
            if (Files.exists(p)) {
                // first, avoid link trickery; then resolve fully
                real = p.toRealPath(LinkOption.NOFOLLOW_LINKS);
                real = p.toRealPath();
            } else {
                Path parent = p.toAbsolutePath().normalize().getParent();
                if (parent == null) parent = workspaceReal;
                Path parentReal = existsOrSelf(parent);
                real = parentReal.resolve(p.getFileName() == null ? Path.of("") : p.getFileName()).normalize();
                if (Files.exists(parentReal)) {
                    try { real = parentReal.resolve(p.getFileName()).toRealPath(); } catch (Exception ignore) {}
                }
            }
        } catch (Exception e) {
            real = p.toAbsolutePath().normalize();
        }

        // 1) must live under workspace
        if (!real.startsWith(workspaceReal)) {
            return Decision.deny("path escapes workspace");
        }

        // Convert to workspace-relative normalized string for allow/deny checks
        String rel = safeRel(real);

        // 2) denied prefixes
        for (String bad : deny) {
            if (!bad.isEmpty() && rel.startsWith(bad)) {
                return Decision.deny("path denied by policy: " + bad);
            }
        }

        // 3) allow-list (if present, must match at least one)
        if (!allow.isEmpty()) {
            boolean any = false;
            for (String ok : allow) if (!ok.isEmpty() && rel.startsWith(ok)) { any = true; break; }
            if (!any) return Decision.deny("path not in allow-list");
        }

        return Decision.allow();
    }

    private String safeRel(Path real) {
        try {
            Path rel = workspaceReal.relativize(real);
            String s = rel.toString().replace('\\','/');
            if (s.startsWith("./")) s = s.substring(2);
            return s;
        } catch (Exception e) {
            return real.getFileName() == null ? "" : real.getFileName().toString();
        }
    }

    private static Path existsOrSelf(Path p) {
        try { return Files.exists(p) ? p.toRealPath() : p.toAbsolutePath().normalize(); }
        catch (Exception e) { return p.toAbsolutePath().normalize(); }
    }

    private static String cleanRel(String s) {
        if (s == null) return "";
        String t = s.trim().replace('\\','/');
        while (t.startsWith("./")) t = t.substring(2);
        if (t.startsWith("/")) t = t.substring(1);
        return t;
    }

    private record Decision(boolean allowed, String reason) {
        static Decision allow() { return new Decision(true, ""); }
        static Decision deny(String why) { return new Decision(false, why == null ? "denied" : why); }
    }
}
