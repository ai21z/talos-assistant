package dev.talos.core.privacy;

import dev.talos.core.Config;
import dev.talos.core.context.ContextResult;
import dev.talos.spi.types.ChunkMetadata;
import dev.talos.spi.types.SourceIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextSourcePolicyTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @CsvSource({
            "notes#draft.docx#0,notes#draft.docx",
            "folder#draft/notes.docx#2147483647,folder#draft/notes.docx",
            "notes#draft.docx,notes#draft.docx",
            "notes.md#draft,notes.md#draft",
            "notes.md#-1,notes.md#-1",
            "notes.md#2147483648,notes.md#2147483648",
            "notes.md#01,notes.md#01",
            "notes.md#0/other.txt,notes.md#0/other.txt"
    })
    void legacyDecoderRemovesOnlyOneCanonicalBoundedTerminalChunkId(String chunk, String source) {
        assertEquals(source, ContextSourcePolicy.basePath(chunk));
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes#draft.docx#0", "folder#draft/notes.docx#2", "notes#draft.docx"})
    void embeddedDelimiterCannotDowngradeLegacyDocumentPrivacy(String path) {
        var admitted = ContextSourcePolicy.admit(privateConfig(), workspace, new ContextResult.Snippet(path, "Private prose."));
        assertFalse(admitted.contentRestrictions().modelAllowed());
        assertFalse(admitted.contentRestrictions().artifactAllowed());
        assertFalse(admitted.contentRestrictions().historyAllowed());
        assertTrue(admitted.contentRestrictions().indexAllowed());
    }

    @ParameterizedTest
    @CsvSource({
            "public.md#0,notes.docx",
            "notes#draft.docx#0,notes",
            "public.md#0,secrets/private.md",
            "public.md#0,../outside.md",
            "public.md#-1,public.md",
            "public.md#2147483648,public.md",
            "public.md#0/other.txt,public.md",
            "public.md#0,''"
    })
    void inconsistentSourceIdentityFailsClosed(String path, String source) {
        var metadata = new ChunkMetadata(null, -1, -1, null, SourceIdentity.unclassified(source));
        var admitted = ContextSourcePolicy.admit(privateConfig(), workspace,
                new ContextResult.Snippet(path, "Untrusted identity prose.", metadata));
        assertFalse(admitted.contentRestrictions().modelAllowed());
        assertFalse(admitted.contentRestrictions().artifactAllowed());
        assertFalse(admitted.contentRestrictions().historyAllowed());
        assertFalse(admitted.contentRestrictions().indexAllowed());
    }

    @ParameterizedTest
    @CsvSource({
            "notes#draft.docx#0,notes#draft.docx,false",
            "notes#draft.docx,notes#draft.docx,false",
            "notes#draft.md#0,notes#draft.md,true",
            "notes.md#0,notes.md#0,true"
    })
    void consistentExplicitIdentityPreservesLiteralSourcePath(String path, String source, boolean modelAllowed) {
        var metadata = new ChunkMetadata(null, -1, -1, null, SourceIdentity.unclassified(source));
        var admitted = ContextSourcePolicy.admit(privateConfig(), workspace,
                new ContextResult.Snippet(path, "Retained prose.", metadata));
        assertEquals(modelAllowed, admitted.contentRestrictions().modelAllowed());
        assertEquals(modelAllowed, admitted.contentRestrictions().historyAllowed());
    }

    private static Config privateConfig() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", Map.of("mode", "private", "rag", Map.of("enabled_in_private_mode", true),
                "document_extraction", Map.of(
                "allow_send_to_model", false, "allow_rag_indexing", true, "persist_raw_artifacts", false)));
        cfg.data.put("document_extraction", Map.of("enabled", true, "word", Map.of("enabled", true)));
        return cfg;
    }

    @Test
    void knownDocumentOriginRemainsDeniedWhenExtractionIsDisabled() {
        Config cfg = new Config(null);
        cfg.data.put("document_extraction", Map.of("enabled", false));
        var snippet = ContextSourcePolicy.admit(cfg, workspace, new ContextResult.Snippet("notes.docx#2", "Harmless prose."));
        assertNotNull(snippet.contentDecision());
        assertFalse(snippet.contentRestrictions().modelAllowed());
        assertFalse(snippet.contentRestrictions().artifactAllowed());
    }

    @Test
    void retainedProvenanceCannotBeRelaxedByOrdinaryPathOrDeveloperMode() {
        var retained = new DocumentContentDecision(true, false, false, true, "retained private document");
        var snippet = ContextSourcePolicy.admit(new Config(null), workspace,
                new ContextResult.Snippet("ordinary.md#0", "Harmless prose.", ChunkMetadata.empty(), retained));
        assertEquals(retained, snippet.contentDecision());
        assertFalse(snippet.contentRestrictions().modelAllowed());
        assertFalse(snippet.contentRestrictions().historyAllowed());
    }

    @Test
    void protectedAndOutsideSourcesCannotBeImplicitlyPreloaded() {
        assertFalse(ContextSourcePolicy.mayPreload(workspace, workspace.resolve("secrets/notes.md")));
        assertFalse(ContextSourcePolicy.mayPreload(workspace, workspace.resolve("../notes.md")));
        assertTrue(ContextSourcePolicy.mayPreload(workspace, workspace.resolve("notes.md")));
    }
}
