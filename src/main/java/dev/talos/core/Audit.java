package dev.talos.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.talos.core.security.Redactor;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal, safe, redacted JSONL audit logger.
 * - Session toggle via setEnabled()/isEnabled()
 * - Config defaults: audit.enabled (false), audit.redact (true)
 * - Writes to ~/.talos/logs/audit.jsonl
 * - Never throws to callers (swallows I/O errors)
 */
public class Audit {

    private final Path logPath =
            Paths.get(System.getProperty("user.home"), ".talos", "logs", "audit.jsonl");

    private final ObjectMapper mapper =
            new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private volatile boolean enabled = false;
    private final boolean redactOn;
    private final Redactor redactor;

    public Audit() {
        // Best-effort directory creation
        try { Files.createDirectories(logPath.getParent()); } catch (Exception ignored) {}

        // Defaults
        boolean cfgEnabled = false;
        boolean cfgRedact = true;

        // Optional: read defaults from config if available
        try {
            Config cfg = new Config();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) cfg.data;
            Object auditObj = (data == null) ? null : data.get("audit");
            @SuppressWarnings("unchecked")
            Map<String, Object> audit = (auditObj instanceof Map<?,?>) ? (Map<String, Object>) auditObj : Map.of();
            cfgEnabled = asBool(audit.get("enabled"), false);
            cfgRedact = asBool(audit.get("redact"), true);
        } catch (Throwable ignored) {
            // If config fails for any reason, we keep safe defaults (disabled + redact true once enabled).
        }

        this.enabled = cfgEnabled;
        this.redactOn = cfgRedact;
        this.redactor = new Redactor();
    }

    /** Toggle audit for the current process/session. */
    public void setEnabled(boolean on) { this.enabled = on; }

    /** Current session setting. */
    public boolean isEnabled() { return enabled; }

    /**
     * Structured event form used by commands/modes.
     * Values are minimally redacted (strings only) before persisting.
     */
    public void log(String event, String mode, String cmd, String result, Map<String, Object> extra) {
        if (!enabled) return;
        try {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("ts", Instant.now().toString());
            rec.put("event", safe(event));
            rec.put("mode", safe(mode));
            rec.put("cmd", scrub(safe(cmd)));
            rec.put("result", scrub(safe(result)));
            if (extra != null) {
                for (var e : extra.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String s) v = scrub(s);
                    rec.put(e.getKey(), v);
                }
            }
            writeLine(rec);
        } catch (Exception ignored) {
            // Never bubble up audit issues to the CLI
        }
    }

    /**
     * Back-compat payload form.
     * NOTE: Strings inside payload are redacted when redact is enabled.
     */
    public void log(String event, Map<String, Object> payload) {
        if (!enabled) return;
        try {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("ts", Instant.now().toString());
            rec.put("event", safe(event));
            rec.put("payload", redactPayload(payload));
            writeLine(rec);
        } catch (Exception ignored) {
            // Swallow to avoid impacting user flow
        }
    }

    /* ===================== helpers ===================== */

    private void writeLine(Map<String, Object> rec) throws IOException {
        String line = mapper.writeValueAsString(rec) + System.lineSeparator();
        // synchronized to avoid interleaving lines across threads
        synchronized (this) {
            Files.writeString(logPath, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private Map<String, Object> redactPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>(payload.size());
        for (var e : payload.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) v = scrub(s);
            out.put(e.getKey(), v);
        }
        return out;
    }

    private String scrub(String s) {
        if (!redactOn) return s;
        return redactor.redactLine(s);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static boolean asBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return def;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }
}
