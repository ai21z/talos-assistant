package dev.talos.core.context;

import dev.talos.core.privacy.DocumentContentDecision;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChunkMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextPackerPrivacyTest {
    private static final DocumentContentDecision PRIVATE =
            new DocumentContentDecision(true, true, false, true, "private fixture");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void truncationAndPinnedReservationRetainAllDestinationRestrictions(boolean reserve) {
        var privateSnippet = new ContextResult.Snippet("notes.docx#0", "x".repeat(2_000),
                ChunkMetadata.empty(), PRIVATE);
        var packed = new ContextPacker(new TokenBudget(500, 0.3, 100)).pack("", "", 0,
                List.of(privateSnippet, new ContextResult.Snippet("other.md#0", "Public.")),
                List.of(), reserve);
        assertTrue(packed.wasTrimmed());
        assertFalse(packed.isEmpty());
        assertTrue(packed.snippets().getFirst().text().length() < 2_000);
        assertEquals(PRIVATE, packed.snippets().getFirst().contentDecision());
        assertEquals(new ContentRestrictions(true, false, false, true), packed.contentRestrictions());
    }

    @Test
    void deduplicationRetainsSelectedSnippetPolicyAndDenialDoesNotRestrictPublicContent() {
        var privateSnippet = new ContextResult.Snippet("notes.docx#0", "Private.", ChunkMetadata.empty(), PRIVATE);
        var denied = new ContextResult.Snippet("denied.docx#0", "Denied.", ChunkMetadata.empty(),
                new DocumentContentDecision(true, false, false, true, "denied fixture"));
        var packed = new ContextPacker(new TokenBudget(10_000)).pack("", "",
                List.of(privateSnippet), List.of(privateSnippet, denied, new ContextResult.Snippet("public.md#0", "Public.")));
        assertEquals(2, packed.finalCount());
        assertEquals(PRIVATE, packed.snippets().getFirst().contentDecision());
        assertFalse(packed.snippets().stream().anyMatch(s -> s.text().contains("Denied.")));
        var publicOnly = new ContextPacker(new TokenBudget(10_000)).pack("", "", List.of(),
                List.of(denied, new ContextResult.Snippet("public.md#0", "Public.")));
        assertEquals(1, publicOnly.finalCount());
        assertEquals(ContentRestrictions.UNRESTRICTED, publicOnly.contentRestrictions());
    }
}
