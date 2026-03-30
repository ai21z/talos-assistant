package dev.loqj.core.index;

import dev.loqj.core.ingest.ChunkMetadata;
import dev.loqj.core.spi.CorpusStore;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lucene 10.x store with BM25 + KNN and SearcherManager for NRT. */
public class LuceneStore implements AutoCloseable, CorpusStore {
    private static final Logger LOG = LoggerFactory.getLogger(LuceneStore.class);

    public static final String F_TEXT     = "text";
    public static final String F_PATH     = "path";       // unique key: relativeFile#chunkId
    public static final String F_VEC      = "vec";
    public static final String F_FILEHASH = "fileHash";   // metadata
    public static final String F_CHUNKID  = "chunkId";    // metadata
    public static final String F_NAME     = "name";       // basename (analyzed)
    public static final String F_PATHTOK  = "pathtok";    // path tokens (analyzed)
    public static final String F_LANG     = "lang";       // programming/markup language (StringField, filterable)
    public static final String F_LINE_START = "lineStart"; // 1-based start line (StoredField + IntPoint)
    public static final String F_LINE_END   = "lineEnd";   // 1-based end line, inclusive (StoredField + IntPoint)
    /**
     * Last Markdown heading in effect for this chunk (StoredField only).
     * <p>
     * Current purpose: provenance — lets consumers display section context alongside
     * a retrieved snippet (e.g. "src/Foo.java § Architecture, lines 10–25").
     * <p>
     * Future purpose: if heading-filtered retrieval is needed, add a parallel
     * {@code StringField} or {@code TextField} to make this field searchable.
     * Kept as StoredField-only for now to avoid index bloat until a consumer exists.
     */
    public static final String F_HEADING    = "heading";

    /** Legacy hit type kept for test compatibility. */
    public static class Hit {
        public final String path;
        public final float score;
        public Hit(String path, float score) { this.path = path; this.score = score; }
    }

    private final Analyzer analyzer = new StandardAnalyzer();
    private final FSDirectory dir;
    private final IndexWriter writer;
    private final SearcherManager sm;
    private final int vectorDim;

    public LuceneStore(Path indexDir, int vectorDim) {
        try {
            this.dir = FSDirectory.open(indexDir);
            var iwc = new IndexWriterConfig(analyzer);
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            this.writer = new IndexWriter(dir, iwc);
            this.sm = new SearcherManager(writer, true, true, null);
            this.vectorDim = vectorDim;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /* ------------------- CorpusStore (SPI) ------------------- */

    /** Package-private accessor for test use. */
    SearcherManager getSearcherManager() { return sm; }

    @Override
    public void add(String path, String text, float[] vec) {
        add(path, text, vec, null, null);
    }

    @Override
    public void add(String path, String text, float[] vec, String fileHash, Integer chunkId) {
        add(path, text, vec, fileHash, chunkId, null);
    }

    @Override
    public void add(String path, String text, float[] vec, String fileHash, Integer chunkId, ChunkMetadata metadata) {
        try {
            var doc = new Document();
            doc.add(new StringField(F_PATH, path, Field.Store.YES));
            if (fileHash != null) doc.add(new StringField(F_FILEHASH, fileHash, Field.Store.YES));
            if (chunkId  != null) doc.add(new StoredField(F_CHUNKID, chunkId));
            doc.add(new TextField(F_TEXT, text, Field.Store.YES));

            // Normalize id → real file path (drop "#chunkId")
            String rel = path;
            int hash = rel.indexOf('#');
            if (hash >= 0) rel = rel.substring(0, hash);

            // Basename and path tokens from normalized rel
            String base = rel;
            int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
            if (slash >= 0) base = base.substring(slash + 1);

            String pathtoks = rel.replace('\\','/')
                    .replaceAll("[^A-Za-z0-9/_.-]", " ")
                    .replace('/', ' ');

            doc.add(new TextField(F_NAME, base, Field.Store.NO));
            doc.add(new TextField(F_PATHTOK, pathtoks, Field.Store.NO));

            if (vec != null) {
                if (vectorDim > 0 && vec.length == vectorDim) {
                    doc.add(new KnnFloatVectorField(F_VEC, vec));
                } else {
                    LOG.debug("Skip vector for {} (have={}, expected={})", path,
                            (vec == null ? -1 : vec.length), vectorDim);
                }
            }

            // Structured chunk metadata
            if (metadata != null) {
                if (metadata.language() != null) {
                    doc.add(new StringField(F_LANG, metadata.language(), Field.Store.YES));
                }
                if (metadata.lineStart() > 0) {
                    doc.add(new StoredField(F_LINE_START, metadata.lineStart()));
                    doc.add(new IntPoint("lineStartPt", metadata.lineStart()));
                }
                if (metadata.lineEnd() > 0) {
                    doc.add(new StoredField(F_LINE_END, metadata.lineEnd()));
                    doc.add(new IntPoint("lineEndPt", metadata.lineEnd()));
                }
                if (metadata.headingContext() != null) {
                    doc.add(new StoredField(F_HEADING, metadata.headingContext()));
                }
            }

            writer.updateDocument(new Term(F_PATH, path), doc);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void commit() {
        try {
            writer.commit();
            sm.maybeRefresh();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<CorpusStore.Hit> bm25(String queryText, int k) {
        IndexSearcher s = null;
        try {
            s = sm.acquire();

            // Multi-field BM25 with boosts: name > path tokens > text
            var boosts = new java.util.HashMap<String,Float>();
            boosts.put(F_TEXT,    1.0f);
            boosts.put(F_PATHTOK, 1.8f);
            boosts.put(F_NAME,    3.0f);

            Query base = new org.apache.lucene.queryparser.classic.MultiFieldQueryParser(
                    new String[]{F_TEXT, F_NAME, F_PATHTOK},
                    analyzer,
                    boosts
            ).parse(org.apache.lucene.queryparser.classic.QueryParser.escape(queryText));

            // Extra nudges: exact basename hits & CamelCase/file-like tokens
            var nudges = new org.apache.lucene.search.BooleanQuery.Builder();
            org.apache.lucene.queryparser.classic.QueryParser nameParser =
                    new org.apache.lucene.queryparser.classic.QueryParser(F_NAME, analyzer);
            org.apache.lucene.queryparser.classic.QueryParser tokParser =
                    new org.apache.lucene.queryparser.classic.QueryParser(F_PATHTOK, analyzer);

            String[] tokens = queryText.split("[^A-Za-z0-9_./-]+");
            for (String t : tokens) {
                if (t.isBlank()) continue;

                boolean looksLikeFile = t.endsWith(".java") || t.endsWith(".md") || t.contains(".");
                boolean looksCamel    = t.matches("[A-Z][A-Za-z0-9_]{3,}");

                if (looksLikeFile || looksCamel) {
                    try {
                        var qNameExact = nameParser.parse(org.apache.lucene.queryparser.classic.QueryParser.escape(t));
                        nudges.add(new org.apache.lucene.search.BoostQuery(qNameExact, 6.0f),
                                org.apache.lucene.search.BooleanClause.Occur.SHOULD);

                        var qTok = tokParser.parse(org.apache.lucene.queryparser.classic.QueryParser.escape(t));
                        nudges.add(new org.apache.lucene.search.BoostQuery(qTok, 3.5f),
                                org.apache.lucene.search.BooleanClause.Occur.SHOULD);
                    } catch (org.apache.lucene.queryparser.classic.ParseException ignore) {
                        // ignore malformed tokens
                    }
                }
            }

            Query finalQ = new org.apache.lucene.search.BooleanQuery.Builder()
                    .add(base,  org.apache.lucene.search.BooleanClause.Occur.SHOULD)
                    .add(nudges.build(), org.apache.lucene.search.BooleanClause.Occur.SHOULD)
                    .build();

            TopDocs td = s.search(finalQ, k);

            StoredFields stored = s.storedFields();
            var hits = new ArrayList<CorpusStore.Hit>(td.scoreDocs.length);
            for (ScoreDoc sd : td.scoreDocs) {
                var d = stored.document(sd.doc);
                hits.add(new CorpusStore.Hit(d.get(F_PATH), sd.score));
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (s != null) try { sm.release(s); } catch (IOException ignore) {}
        }
    }

    @Override
    public List<CorpusStore.Hit> knn(float[] qvec, int k) {
        if (qvec == null) return List.of();
        IndexSearcher s = null;
        try {
            s = sm.acquire();
            var q = new KnnFloatVectorQuery(F_VEC, qvec, k);
            TopDocs td = s.search(q, k);

            StoredFields stored = s.storedFields();
            var hits = new ArrayList<CorpusStore.Hit>(td.scoreDocs.length);
            for (ScoreDoc sd : td.scoreDocs) {
                var d = stored.document(sd.doc);
                hits.add(new CorpusStore.Hit(d.get(F_PATH), sd.score));
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (s != null) try { sm.release(s); } catch (IOException ignore) {}
        }
    }

    @Override
    public String getTextByPath(String path) {
        IndexSearcher s = null;
        try {
            s = sm.acquire();
            var tq = new TermQuery(new Term(F_PATH, path));
            TopDocs td = s.search(tq, 1);
            if (td.scoreDocs.length == 0) return null;
            var d = s.storedFields().document(td.scoreDocs[0].doc);
            return d.get(F_TEXT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (s != null) try { sm.release(s); } catch (IOException ignore) {}
        }
    }

    /* -------- Legacy methods retained for tests/compat -------- */

    public List<Hit> searchBM25(String queryText, int k) {
        var spi = bm25(queryText, k);
        var out = new ArrayList<Hit>(spi.size());
        for (var h : spi) out.add(new Hit(h.path(), h.score()));
        return out;
    }

    public List<Hit> searchKNN(float[] qvec, int k) {
        var spi = knn(qvec, k);
        var out = new ArrayList<Hit>(spi.size());
        for (var h : spi) out.add(new Hit(h.path(), h.score()));
        return out;
    }

    /**
     * Match-all listing, ordered by path for stable grouping.
     * Use this instead of bm25("*") which doesn't work as expected.
     */
    public List<CorpusStore.Hit> matchAll(int k) {
        IndexSearcher s = null;
        try {
            s = sm.acquire();
            var query = new MatchAllDocsQuery();
            TopDocs td = s.search(query, k);

            StoredFields stored = s.storedFields();
            var hits = new ArrayList<CorpusStore.Hit>(td.scoreDocs.length);
            for (ScoreDoc sd : td.scoreDocs) {
                var d = stored.document(sd.doc);
                String path = d.get(F_PATH);
                if (path != null) {
                    hits.add(new CorpusStore.Hit(path, sd.score));
                }
            }

            // Sort by path for deterministic output
            hits.sort(java.util.Comparator.comparing(CorpusStore.Hit::path, String.CASE_INSENSITIVE_ORDER));
            return hits;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (s != null) try { sm.release(s); } catch (IOException ignore) {}
        }
    }

    /**
     * Number of live docs in the index for diagnostics.
     */
    public int numDocs() {
        IndexSearcher s = null;
        try {
            s = sm.acquire();
            return s.getIndexReader().numDocs();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (s != null) try { sm.release(s); } catch (IOException ignore) {}
        }
    }

    /**
     * Check if a file with given path and hash is already up-to-date in the index.
     * Used to skip re-embedding unchanged chunks during incremental indexing.
     */
    public boolean isUpToDate(String filePath, String fileHash) {
        if (fileHash == null) return false;

        IndexSearcher s = null;
        try {
            s = sm.acquire();

            // Query for any chunk from this file with matching hash
            Query pathPrefix = new PrefixQuery(new Term(F_PATH, filePath + "#"));
            Query hashMatch = new TermQuery(new Term(F_FILEHASH, fileHash));
            Query combined = new BooleanQuery.Builder()
                .add(pathPrefix, BooleanClause.Occur.MUST)
                .add(hashMatch, BooleanClause.Occur.MUST)
                .build();

            TopDocs hits = s.search(combined, 1);
            return hits.scoreDocs.length > 0;
        } catch (Exception e) {
            LOG.debug("Error checking file freshness for {}: {}", filePath, e.getMessage());
            return false;
        } finally {
            if (s != null) {
                try { sm.release(s); } catch (IOException ignore) {}
            }
        }
    }

    /**
     * Remove all chunks for a given file path (used when file content changes).
     */
    public void removeFileChunks(String filePath) {
        try {
            Query pathPrefix = new PrefixQuery(new Term(F_PATH, filePath + "#"));
            writer.deleteDocuments(pathPrefix);
        } catch (IOException e) {
            LOG.warn("Failed to remove chunks for {}: {}", filePath, e.getMessage());
        }
    }

    @Override public void close() {
        try {
            sm.close();
            writer.close();
            dir.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
