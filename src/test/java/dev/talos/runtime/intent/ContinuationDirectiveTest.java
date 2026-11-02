package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuationDirectiveTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Hm... i dont know why you say failed. The page looks better this time.",
            "the page looks great now",
            "nice, that's better",
            "why did it fail?",
            "is it verified now?",
            "does it look better now?",
            "it still doesn't work",
            "no, you never added any css",
            "but there is no css",
            "I fixed it myself",
            "still bad",
            "nothing changed",
            "yes",
            "go ahead",
            "do not change anything",
            "don't fix it",
            "do nothing",
            "please delete the page",
            "remove everything",
            "do you think it looks better now?",
            "could you check if it looks better?",
            "review the remaining problems",
            "give me a list of the remaining issues",
            "do you think it still needs a fix?",
            "Look, there is no css.",
            "Do you see that there is no css?",
            "make it better?",
            ""
    })
    void commentsComplaintsQuestionsAndOtherOperationsAreNotDirectives(String message) {
        assertFalse(ContinuationDirective.present(message), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fix the remaining problems",
            "make it better and more modern",
            "nothing changed, try one more time",
            "can you fix it?",
            "please make it look better",
            "yes! it's better still not a full web page tho. Make it a full page please!",
            "it still doesn't work, please fix it",
            "Could you please try again",
            "ok, apply it",
            "I want you to finish it",
            "go ahead and apply those changes",
            "Do a final stress check and repair anything remaining",
            "Review the BMI calculator you just created and fix any obvious issue",
            "inspect and repair anything remaining",
            "could you make it look better?"
    })
    void imperativeClausesAreDirectives(String message) {
        assertTrue(ContinuationDirective.present(message), message);
    }

    @Test
    void onlyTheDirectiveClauseIsReturned() {
        assertEquals(List.of("make it a full page please"), ContinuationDirective.clauses(
                "yes! it's better still not a full web page tho. Make it a full page please!"));
        assertEquals(List.of("try one more time"),
                ContinuationDirective.clauses("nothing changed, try one more time"));
        assertEquals(List.of("fix style.css now"),
                ContinuationDirective.clauses("The colors are off. Fix style.css now."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "Yes!", "yes please", "go ahead", "do it", "yes, fix it", "OK, go ahead.", "proceed"})
    void bareAcceptancesAreAffirmations(String message) {
        assertTrue(ContinuationDirective.isAffirmation(message), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes, and also delete the css", "the page looks better", "yes it is", "no", "fix it",
            "ok", "okay", "sure", "sounds good"})
    void otherRepliesAreNotAffirmations(String message) {
        assertFalse(ContinuationDirective.isAffirmation(message), message);
    }
}
