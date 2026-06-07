package dev.talos.core.index;

import java.util.Objects;

/** A deterministic symbol-location hit from the local workspace index. */
public record SymbolHit(
        String path,
        String symbol,
        SymbolKind kind,
        int lineStart,
        int lineEnd,
        String signature
) {
    public SymbolHit {
        path = normalizePath(path);
        symbol = Objects.requireNonNullElse(symbol, "").trim();
        kind = kind == null ? SymbolKind.FUNCTION : kind;
        lineStart = Math.max(1, lineStart);
        lineEnd = Math.max(lineStart, lineEnd);
        signature = Objects.requireNonNullElse(signature, "").strip();
    }

    private static String normalizePath(String value) {
        return Objects.requireNonNullElse(value, "").replace('\\', '/').trim();
    }
}
