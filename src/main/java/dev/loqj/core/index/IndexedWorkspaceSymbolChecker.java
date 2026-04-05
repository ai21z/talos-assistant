package dev.loqj.core.index;

import dev.loqj.cli.modes.WorkspaceSymbolChecker;
import dev.loqj.core.IndexPathResolver;
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
 * Lucene-backed workspace symbol checker that resolves PascalCase identifiers
 * against the indexed workspace's {@code name} field (file basenames).
 *
 * <h3>How it works</h3>
 * <p>The Lucene index stores file basenames (e.g. {@code RagService.java}) in the
 * {@link LuceneStore#F_NAME} field, analyzed by {@code StandardAnalyzer}. The analyzer
 * tokenizes and lowercases: {@code "RagService.java"} produces terms
 * {@code ["ragservice", "java"]}.
 *
 * <p>When checking a symbol like {@code "RagService"}, we lowercase it to
 * {@code "ragservice"} and issue a {@link TermQuery} against {@code F_NAME}.
 * If at least one document contains that term, the symbol is confirmed to exist
 * in the workspace.
 *
 * <h3>Caching</h3>
 * <p>Results are cached in a {@link ConcurrentHashMap} so each unique symbol
 * incurs at most one Lucene I/O per session. The cache is invalidated on
 * {@link #invalidateCache()}, which should be called after {@code :reindex}
 * to ensure subsequent lookups reflect the updated index.
 *
 * <h3>Graceful degradation</h3>
 * <p>Returns {@code false} if the index directory does not exist, is empty,
 * or cannot be read. No exceptions are propagated to the caller.
 */
public final class IndexedWorkspaceSymbolChecker implements WorkspaceSymbolChecker {

    private static final Logger LOG = LoggerFactory.getLogger(IndexedWorkspaceSymbolChecker.class);

    private final Path indexDir;
    private final ConcurrentHashMap<String, Boolean> cache = new ConcurrentHashMap<>();

    /**
     * Creates a checker for the given workspace.
     *
     * @param workspace the workspace root directory; the index location is
     *                  resolved via {@link IndexPathResolver#getIndexDirectory(Path)}
     */
    public IndexedWorkspaceSymbolChecker(Path workspace) {
        this.indexDir = IndexPathResolver.getIndexDirectory(workspace);
    }

    /**
     * Package-private constructor for testing with an explicit index directory.
     *
     * @param indexDir direct path to the Lucene index directory
     * @param forTest  ignored; disambiguates from the workspace constructor
     */
    IndexedWorkspaceSymbolChecker(Path indexDir, boolean forTest) {
        this.indexDir = indexDir;
    }

    @Override
    public boolean existsInWorkspace(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String key = symbol.toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(key, this::lookupInIndex);
    }

    /**
     * Clears the lookup cache so that subsequent calls to
     * {@link #existsInWorkspace(String)} re-query the Lucene index.
     *
     * <p>Should be called after {@code :reindex} completes. Safe to call
     * concurrently — ongoing lookups will simply re-populate the cache.
     */
    @Override
    public void invalidateCache() {
        int before = cache.size();
        cache.clear();
        LOG.debug("Symbol checker cache invalidated ({} → 0 entries)", before);
    }

    /**
     * Performs the actual Lucene lookup. Opens a read-only {@link DirectoryReader},
     * executes a {@link PrefixQuery}, and closes the reader immediately.
     *
     * <p>Uses {@code PrefixQuery} rather than {@code TermQuery} because the
     * {@code StandardAnalyzer} may or may not split file basenames at the dot
     * (e.g. "RagService.java" might be one token "ragservice.java" or two tokens
     * "ragservice" + "java" depending on UAX#29 interpretation). A prefix query
     * for "ragservice" matches either case correctly.
     *
     * @return {@code false} on any error
     */
    private boolean lookupInIndex(String lowercasedSymbol) {
        if (!Files.isDirectory(indexDir)) return false;
        try (var dir = FSDirectory.open(indexDir);
             var reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            PrefixQuery query = new PrefixQuery(new Term(LuceneStore.F_NAME, lowercasedSymbol));
            TopDocs results = searcher.search(query, 1);
            return results.scoreDocs.length > 0;
        } catch (Exception e) {
            LOG.debug("Symbol lookup failed for '{}': {}", lowercasedSymbol, e.getMessage());
            return false;
        }
    }

    /** Returns the resolved index directory (visible for testing). */
    Path indexDir() {
        return indexDir;
    }
}

