package dev.talos.core.index;

import dev.talos.cli.modes.WorkspaceSymbolChecker;
import dev.talos.core.IndexPathResolver;
import dev.talos.runtime.policy.SafeLogFormatter;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lucene-backed symbol checker that resolves PascalCase identifiers against
 * indexed file basenames. Results are cached per session; call
 * {@link #invalidateCache()} after reindex. Returns {@code false} gracefully
 * if the index is missing or unreadable.
 */
public final class IndexedWorkspaceSymbolChecker implements WorkspaceSymbolChecker {

    private static final Logger LOG = LoggerFactory.getLogger(IndexedWorkspaceSymbolChecker.class);

    private final Path indexDir;
    private final ConcurrentHashMap<String, Boolean> cache = new ConcurrentHashMap<>();

    /** Creates a checker for the given workspace root. */
    public IndexedWorkspaceSymbolChecker(Path workspace) {
        this.indexDir = IndexPathResolver.getIndexDirectory(workspace);
    }

    /** Package-private constructor for testing with an explicit index directory. */
    IndexedWorkspaceSymbolChecker(Path indexDir, boolean forTest) {
        this.indexDir = indexDir;
    }

    @Override
    public boolean existsInWorkspace(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String key = symbol.toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(key, this::lookupInIndex);
    }

    @Override
    public void invalidateCache() {
        int before = cache.size();
        cache.clear();
        LOG.debug("Symbol checker cache invalidated ({} → 0 entries)", before);
    }

    /** Lucene lookup via PrefixQuery (handles StandardAnalyzer's variable dot-splitting). */
    private boolean lookupInIndex(String lowercasedSymbol) {
        if (!Files.isDirectory(indexDir)) return false;
        try (var dir = FSDirectory.open(indexDir);
             var reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            PrefixQuery query = new PrefixQuery(new Term(LuceneStore.F_NAME, lowercasedSymbol));
            TopDocs results = searcher.search(query, 1);
            return results.scoreDocs.length > 0;
        } catch (Exception e) {
            LOG.debug("Symbol lookup failed for '{}': {}",
                    SafeLogFormatter.value(lowercasedSymbol), SafeLogFormatter.throwableMessage(e));
            return false;
        }
    }

    /** Returns the resolved index directory (visible for testing). */
    Path indexDir() {
        return indexDir;
    }
}

