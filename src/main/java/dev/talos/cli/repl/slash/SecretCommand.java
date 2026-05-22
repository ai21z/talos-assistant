package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.Audit;
import dev.talos.core.Config;
import dev.talos.core.secret.FileSecretStore;
import dev.talos.core.secret.SecretStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * :secret set|get|del <key>
 * - Stores secrets locally (encrypted-at-rest)
 * - Never prints values when audit is enabled
 * - By default, "get" shows redacted echo (length only), not the raw value
 */
public final class SecretCommand implements Command {

    private final SecretStore store;
    private final Audit audit;

    public SecretCommand(Config cfg, Audit audit) {
        this.store = new FileSecretStore(cfg); // swap later for CredMan on Windows if desired
        this.audit = audit == null ? new Audit() : audit;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("secret", List.of(), "/secret set|get|del <key>",
                "Manage local secrets.",
                CommandGroup.SECURITY);
    }

    @Override
    public Result execute(String args, Context ctx) throws Exception {
        String a = (args == null ? "" : args.trim());
        if (a.isEmpty()) return usage();

        String[] toks = a.split("\\s+");
        if (toks.length < 2) return usage();

        String op  = toks[0].toLowerCase();
        String key = toks[1];

        switch (op) {
            case "set" -> {
                char[] value = promptSecret("Enter value: ");
                if (value.length == 0) return new Result.Error("Aborted (no value).", 200);
                try {
                    char[] confirm = promptSecret("Confirm value: ");
                    if (!equals(value, confirm)) {
                        wipe(confirm);
                        wipe(value);
                        return new Result.Error("Values did not match. Secret NOT stored.", 200);
                    }
                    wipe(confirm);
                    store.put("default", key, value);
                    auditSafe("secret.set", key);
                    return new Result.Info("Secret stored for key '" + key + "'.");
                } finally {
                    wipe(value);
                }
            }
            case "get" -> {
                Optional<char[]> v = store.get("default", key);
                if (v.isEmpty()) {
                    auditSafe("secret.get.miss", key);
                    return new Result.Error("No secret for key '" + key + "'.", 200);
                }
                try {
                    if (audit.isEnabled()) {
                        auditSafe("secret.get.hidden_due_to_audit", key);
                        return new Result.Info("audit.enabled=true — refusing to print secret. Use ':audit off' to temporarily view, or use the key programmatically.");
                    }
                    // Show redacted echo only (length), not the raw content
                    int len = v.get().length;
                    auditSafe("secret.get.redacted", key);
                    return new Result.Info("value: [secret] (length " + len + ")");
                } finally {
                    v.ifPresent(this::wipe);
                }
            }
            case "del", "delete", "rm" -> {
                boolean ok = store.delete("default", key);
                auditSafe(ok ? "secret.del.ok" : "secret.del.miss", key);
                return new Result.Info(ok ? "Deleted '" + key + "'." : "No secret for key '" + key + "'.");
            }
            default -> { return usage(); }
        }
    }

    private Result usage() {
        return new Result.Error("Usage: /secret set|get|del <key>", 201);
    }

    /* ---------- io helpers ---------- */

    private static char[] promptSecret(String prompt) throws Exception {
        var con = System.console();
        if (con != null) {
            char[] v = con.readPassword("%s", prompt);
            return (v == null) ? new char[0] : v;
        }
        // No console (IDE): fallback with visible input (warn the user)
        System.out.print(prompt);
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String s = br.readLine();
        if (s == null) return new char[0];
        return s.toCharArray();
    }

    /* ---------- safety ---------- */

    private static boolean equals(char[] a, char[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    private void wipe(char[] a) {
        if (a != null) java.util.Arrays.fill(a, '\0');
    }

    private void auditSafe(String event, String key) {
        try {
            audit.log(event, Map.of("key", key));
        } catch (Exception ignored) {}
    }
}
