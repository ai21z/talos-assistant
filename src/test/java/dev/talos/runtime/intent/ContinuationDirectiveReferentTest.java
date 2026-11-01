package dev.talos.runtime.intent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A directive that names nothing of its own to act on (MICA invariant I1). */
class ContinuationDirectiveReferentTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "apply it",
            "Apply it.",
            "do it now",
            "do it",
            "ok do it",
            "yes, apply it",
            "make the changes",
            "make those changes",
            "apply those changes",
            "Apply that proposal now.",
            "Please implement those changes now.",
            "go ahead and apply those changes",
            "edit it",
            "fix it",
            "Check it and edit it",
            "can you do it?",
            "just do it please",
            "do it again",
            "make it so",
            "finish the rest",
            "do everything for me",
            "review it and fix it",
            "check it and then edit it",
            "fix it and then test it",
            "check it then edit it",
            "check it, and then edit it",
            "ok, apply it then",
            "fix it pls",
            "apply it already",
            "fix it as well",
            "apply it right away",
            "apply it :)",
            "apply all",
            "apply them all",
            "apply it all",
            "apply all of them",
            "apply both",
            "apply all the changes",
            "apply it?",
            "fix it?",
            "do it now?",
            "do those changes",
            "apply it, thank you",
            "fix it thank you",
            "hey, apply it",
            "hey apply it",
            "apply it thx"
    })
    void aDirectiveWithOnlyDeicticObjectsIsDeictic(String message) {
        assertEquals(ContinuationDirective.ReferentForm.DEICTIC, ContinuationDirective.referentForm(message), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply", "fix", "continue", "proceed", "please proceed", "try again", "go ahead and apply",
            "please do"})
    void aDirectiveWithNoObjectIsObjectless(String message) {
        assertEquals(ContinuationDirective.ReferentForm.OBJECTLESS, ContinuationDirective.referentForm(message),
                message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "yes",
            "yes please",
            "go ahead",
            "ok",
            "thanks",
            "make it blue",
            "make it nice",
            "make it good",
            "make them nice",
            "make everything nice",
            "make it 🎃",
            "apply it ok",
            "fix the page",
            "fix index.html",
            "edit it to say hello",
            "apply it to style.css",
            "did you do it?",
            "delete it",
            "rename it",
            "move it",
            "do not do it",
            "don't fix it",
            "nothing changed, try one more time",
            "check it",
            "review it",
            "fix it and add a footer",
            "apply it and",
            "check it and then",
            "apply it then add a footer",
            "apply it but keep the footer",
            "apply all files",
            "apply all of index.html",
            "apply all of",
            "make the changes to the header",
            "do you think it is done",
            ""
    })
    void aMessageThatNamesContentOrIsNotADirectiveIsNotReferentless(String message) {
        assertEquals(ContinuationDirective.ReferentForm.NOT_REFERENTLESS, ContinuationDirective.referentForm(message),
                message);
    }
}
