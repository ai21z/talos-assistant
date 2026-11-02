package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An indirect change request is a stated wish for a different result, never a directive (MICA decision D6, P3b). */
class IndirectChangeRequestTest {

    @Test
    void statedWishesForADifferentResultAreRecognized() {
        for (String message : List.of(
                "I dont like it ... a modern dark synthwave landing page is more appropriate than this. Can you help me?",
                "Can you help me make it a synthwave landing page?",
                "I want it to be a synthwave landing page",
                "I'd like the page to feel calmer.",
                "It should look like a retro arcade.",
                "I would prefer a light theme.",
                "This is not what I wanted, can you help?",
                "It would look better with a sidebar.")) {
            assertTrue(IndirectChangeRequest.recognized(message), message);
        }
    }

    @Test
    void questionsComplaintsAndDirectivesAreNot() {
        for (String message : List.of(
                "what is index.html for?",
                "can you help me understand the css?",
                "help me decide between two layouts",
                "I don't like the css, can you help me understand it?",
                "I don't like it.",
                "is a dark theme more appropriate?",
                "should it be darker?",
                "make it a synthwave landing page",
                "Could you turn it into a synthwave landing page?",
                "delete it, I prefer a clean workspace",
                "Thanks. Which one is more appropriate, grid or flex?",
                "Ok, so what would be more appropriate here?",
                "I'd prefer you not change anything yet, just explain the layout.",
                "I would prefer a light theme, but do not change any files.",
                "")) {
            assertFalse(IndirectChangeRequest.recognized(message), message);
        }
    }
}
