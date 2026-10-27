package dev.talos.safety;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ContentRestrictionScopeCallTest {
    private static final ContentRestrictions PRIVATE = new ContentRestrictions(true, false, false, true);

    @AfterEach void clear() { ContentRestrictionScope.end(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulCallRetainsMessagePolicyAndOnlyRemovesStandaloneScope(boolean activeTurn) {
        if (activeTurn) ContentRestrictionScope.begin();
        ChatMessage message = ContentRestrictionScope.callWithRestrictions(PRIVATE, () -> {
            assertEquals(PRIVATE, ContentRestrictionScope.current());
            return ChatMessage.assistant("Harmless derived answer.");
        });
        assertEquals(PRIVATE, message.contentRestrictions());
        assertEquals(activeTurn ? PRIVATE : ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        ContentRestrictionScope.end();
        assertEquals(ContentRestrictions.WITHHELD, message.contentRestrictions().forHistory(message.content()));
        assertEquals(ContentRestrictions.UNRESTRICTED, ChatMessage.user("Next ordinary turn.").contentRestrictions());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCallStillCleansStandaloneScopeAndNeverWeakensOwningTurn(boolean activeTurn) {
        if (activeTurn) ContentRestrictionScope.begin();
        assertThrows(IllegalStateException.class, () -> ContentRestrictionScope.callWithRestrictions(PRIVATE, () -> {
            assertEquals(PRIVATE, ContentRestrictionScope.current());
            throw new IllegalStateException("fixture failure");
        }));
        assertEquals(activeTurn ? PRIVATE : ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
    }
}
