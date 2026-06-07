package dev.talos.core.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.safety.SafeLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** JSON sidecar for deterministic workspace symbol evidence. */
public final class SymbolIndexStore {

    private static final Logger LOG = LoggerFactory.getLogger(SymbolIndexStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE_NAME = "talos-symbols.json";
    private static final Pattern QUERY_TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private SymbolIndexStore() {}

    public enum LoadStatus {
        MISSING,
        LOADED,
        CORRUPT
    }

    public record LoadResult(LoadStatus status, List<SymbolHit> hits, String reason) {
        public LoadResult {
            status = status == null ? LoadStatus.MISSING : status;
            hits = stableSort(hits);
            reason = reason == null ? "" : reason.strip();
        }
    }

    public record QueryResult(List<SymbolHit> hits, LoadStatus sidecarStatus, String sidecarReason) {
        public QueryResult {
            hits = stableSort(hits);
            sidecarStatus = sidecarStatus == null ? LoadStatus.MISSING : sidecarStatus;
            sidecarReason = sidecarReason == null ? "" : sidecarReason.strip();
        }
    }

    public static Path symbolsFile(Path indexDir) {
        return indexDir.resolve(FILE_NAME);
    }

    public static boolean exists(Path indexDir) {
        return Files.isRegularFile(symbolsFile(indexDir));
    }

    public static void writeAll(Path indexDir, List<SymbolHit> hits) throws IOException {
        Files.createDirectories(indexDir);
        List<SymbolHit> sorted = stableSort(hits);
        JSON.writerWithDefaultPrettyPrinter().writeValue(symbolsFile(indexDir).toFile(), sorted);
    }

    public static LoadResult loadDetailed(Path indexDir) {
        Path file = symbolsFile(indexDir);
        if (!Files.isRegularFile(file)) return new LoadResult(LoadStatus.MISSING, List.of(), "missing sidecar");
        try {
            List<SymbolHit> hits = JSON.readValue(file.toFile(), new TypeReference<List<SymbolHit>>() {});
            return new LoadResult(LoadStatus.LOADED, hits, "");
        } catch (Exception e) {
            String reason = SafeLogFormatter.throwableMessage(e);
            LOG.debug("Failed to load symbol index sidecar {}: {}",
                    SafeLogFormatter.value(file), reason);
            return new LoadResult(LoadStatus.CORRUPT, List.of(), reason);
        }
    }

    public static List<SymbolHit> load(Path indexDir) {
        return loadDetailed(indexDir).hits();
    }

    public static QueryResult queryDetailed(Path indexDir, String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return new QueryResult(List.of(), LoadStatus.MISSING, "invalid query");
        }
        Set<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return new QueryResult(List.of(), LoadStatus.MISSING, "no symbol terms");
        }
        LoadResult loaded = loadDetailed(indexDir);
        if (loaded.status() != LoadStatus.LOADED || loaded.hits().isEmpty()) {
            return new QueryResult(List.of(), loaded.status(), loaded.reason());
        }

        List<SymbolHit> out = new ArrayList<>();
        for (SymbolHit hit : loaded.hits()) {
            if (terms.contains(hit.symbol().toLowerCase(Locale.ROOT))) {
                out.add(hit);
            }
        }
        return new QueryResult(stableSort(out).stream().limit(limit).toList(), loaded.status(), loaded.reason());
    }

    public static List<SymbolHit> query(Path indexDir, String query, int limit) {
        return queryDetailed(indexDir, query, limit).hits();
    }

    static Set<String> queryTerms(String query) {
        var matcher = QUERY_TOKEN.matcher(query);
        Set<String> terms = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() < 3) continue;
            terms.add(token.toLowerCase(Locale.ROOT));
        }
        return terms;
    }

    private static List<SymbolHit> stableSort(List<SymbolHit> hits) {
        if (hits == null || hits.isEmpty()) return List.of();
        return hits.stream()
                .filter(hit -> hit != null && !hit.path().isBlank() && !hit.symbol().isBlank())
                .sorted(Comparator
                        .comparing(SymbolHit::path, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(SymbolHit::lineStart)
                        .thenComparing(SymbolHit::symbol, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(hit -> hit.kind().name()))
                .toList();
    }
}
