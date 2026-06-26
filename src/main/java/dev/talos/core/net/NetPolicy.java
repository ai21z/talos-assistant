package dev.talos.core.net;

import dev.talos.core.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Net policy snapshot built from Config.
 * No network I/O is performed in Phase-1; this is for inspection/printing only.
 */
public final class NetPolicy {
    public final boolean enabled;
    public final boolean readOnly;          // for NetCmd printing
    public final List<String> allowDomains;
    public final List<String> contentTypes;
    public final int maxBytes;

    /** Primary constructor (handy for tests). */
    public NetPolicy(boolean enabled, List<String> allowDomains, List<String> contentTypes, int maxBytes) {
        this.enabled = enabled;
        this.readOnly = !enabled;
        this.allowDomains = (allowDomains == null) ? List.of() : List.copyOf(allowDomains);
        this.contentTypes = (contentTypes == null) ? List.of() : List.copyOf(contentTypes);
        this.maxBytes = maxBytes;
    }

    /** Constructor expected by NetCmd: build directly from Config. */
    @SuppressWarnings("unchecked")
    public NetPolicy(Config cfg) {
        Map<String,Object> root = cfg.data;
        Map<String,Object> m = asMap(root.get("net"));

        this.enabled = asBool(m.get("enabled"), false);
        // If config has explicit read_only, use it; otherwise infer from enabled
        this.readOnly = asBool(m.get("read_only"), !this.enabled);

        this.allowDomains = asStrList(m.get("allow_domains"));
        this.contentTypes = asStrList(m.get("content_types"));
        this.maxBytes = asInt(m.get("max_bytes"), 1_048_576);
    }

    public static NetPolicy from(Config cfg) { return new NetPolicy(cfg); }

    /** Best-effort domain rule: exact match or suffix starting with '.' */
    public boolean allowedDomain(String host) {
        if (allowDomains.isEmpty() || host == null || host.isBlank()) return false;
        String h = host.toLowerCase();
        for (String rule : allowDomains) {
            if (rule == null || rule.isBlank()) continue;
            String r = rule.toLowerCase().trim();
            if (h.equals(r)) return true;
            if (r.startsWith(".") && h.endsWith(r)) return true;
        }
        return false;
    }

    public String summary() {
        return "net.enabled=" + enabled +
                ", read_only=" + readOnly +
                ", allow_domains=" + allowDomains +
                ", content_types=" + contentTypes +
                ", max_bytes=" + maxBytes;
    }

    /* -------------------- tiny parsing helpers (no CfgUtil dependency) -------------------- */

    @SuppressWarnings("unchecked")
    private static Map<String,Object> asMap(Object o) {
        return (o instanceof Map<?,?> m) ? (Map<String,Object>) m : Map.of();
    }

    private static boolean asBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return def;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    private static int asInt(Object o, int def) {
        if (o == null) return def;
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return def; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStrList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object it : list) if (it != null) out.add(String.valueOf(it));
            return List.copyOf(out);
        }
        // single string -> singleton
        return List.of(String.valueOf(o));
    }
}
