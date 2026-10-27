package dev.talos.engine.llamacpp;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** Parses and sanitizes llama.cpp context-size command-line overrides. */
public final class LlamaCppContextArgs {
    private LlamaCppContextArgs() {
    }

    public static OptionalInt contextOverride(List<String> args) {
        if (args == null || args.isEmpty()) return OptionalInt.empty();
        OptionalInt override = OptionalInt.empty();
        for (int i = 0; i < args.size(); i++) {
            String arg = clean(args.get(i));
            if (arg.isBlank()) continue;
            if (isContextEqualsFlag(arg)) {
                OptionalInt equalsValue = parseContextValue(arg.substring(arg.indexOf('=') + 1));
                if (equalsValue.isPresent()) {
                    override = equalsValue;
                }
                continue;
            }
            if (isContextFlag(arg) && i + 1 < args.size()) {
                OptionalInt parsed = parseContextValue(args.get(++i));
                if (parsed.isPresent()) {
                    override = parsed;
                }
            }
        }
        return override;
    }

    public static List<String> sanitize(List<String> args) {
        if (args == null || args.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            String raw = args.get(i);
            String arg = clean(raw);
            if (isContextEqualsFlag(arg)) {
                int equals = arg.indexOf('=');
                OptionalInt equalsValue = parseContextValue(arg.substring(equals + 1));
                equalsValue.ifPresent(value -> out.add(arg.substring(0, equals + 1) + value));
                continue;
            }
            if (isContextFlag(arg)) {
                if (i + 1 < args.size()) {
                    OptionalInt parsed = parseContextValue(args.get(++i));
                    if (parsed.isPresent()) {
                        out.add(raw);
                        out.add(String.valueOf(parsed.orElseThrow()));
                    }
                }
                continue;
            }
            out.add(raw);
        }
        return List.copyOf(out);
    }

    public static boolean isContextFlag(String arg) {
        return "-c".equals(arg)
                || "--ctx-size".equals(arg)
                || "--ctx_size".equals(arg);
    }

    private static boolean isContextEqualsFlag(String arg) {
        int equals = arg.indexOf('=');
        if (equals <= 0) return false;
        String flag = arg.substring(0, equals).trim();
        return isContextFlag(flag);
    }

    private static OptionalInt parseContextValue(String raw) {
        String cleaned = clean(raw);
        if (!cleaned.matches("\\+?\\d+")) {
            return OptionalInt.empty();
        }
        if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1);
        }
        try {
            BigInteger parsed = new BigInteger(cleaned);
            BigInteger max = BigInteger.valueOf(LlamaCppContextLimits.MAX_CONTEXT);
            return OptionalInt.of(parsed.compareTo(max) > 0
                    ? LlamaCppContextLimits.MAX_CONTEXT
                    : parsed.intValue());
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
