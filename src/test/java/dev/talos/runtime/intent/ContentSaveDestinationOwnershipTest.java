package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContentSaveDestinationOwnershipTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "I want to write and save a txt file with a short story about a woman named Mara in medieval times who has one short thumb. Do not create notes.txt.",
            "Save a text file with a story about a woman named Mara.txt. Do not create notes.txt.",
            "Write a file containing a story about a character called Rowan.txt. Do not create notes.txt.",
            "Create a text file with a character described as Linden.txt. Do not create notes.txt.",
            "Put a story about a character called `River.txt` in a text file. Do not create notes.txt.",
            "Save a text file with a story about a note in a file named Mara.txt. Do not create notes.txt.",
            "Save a story about a character known as Mara.txt. Do not create notes.txt."
    })
    void contentNamesCannotSupplyMissingDestinationOrMutationTarget(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var save = resolution.intent().contentSaveRequest();
        assertAll(
                () -> assertTrue(save.supportsFilenameContinuation(), save.toString()),
                () -> assertTrue(save.clarificationRequired(), save.toString()),
                () -> assertTrue(resolution.contract().mutationRequested(), resolution.toString()),
                () -> assertFalse(resolution.contract().mutationAllowed(), resolution.toString()),
                () -> assertTrue(resolution.contract().expectedTargets().isEmpty(), resolution.toString()),
                () -> assertEquals(Set.of("notes.txt"), resolution.contract().forbiddenTargets()),
                () -> assertEquals(request, save.originalRequest())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Save a file named README containing a character named Mara.txt.",
            "Write a text file called README with a character called Rowan.txt.",
            "Save a story about a woman named Mara.txt in a file called README.",
            "Put a story about a character called River.txt into a new file named README.",
            "Save a story as `README`."
    })
    void explicitFileObjectOwnsOnlyItsName(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertFalse(resolution.intent().contentSaveRequest().clarificationRequired(), resolution.toString());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("README"), resolution.contract().expectedTargets(), resolution.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Save a story about a woman named Mara as story.txt.",
            "Save a story about a woman named Mara.txt as story.txt.",
            "Write story.txt about a band called Retrocats.txt.",
            "Create story.txt containing an example named Demo.txt.",
            "Save \"a woman named Mara as report.txt\" as story.txt.",
            "Save \"hello\" to story.txt.",
            "Save a short story to story.txt.",
            "Save a file as story.txt with a short story."
    })
    void outputSlotIsNotDisplacedByPayloadNames(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertFalse(resolution.intent().contentSaveRequest().clarificationRequired(), resolution.toString());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("story.txt"), resolution.contract().expectedTargets(), resolution.toString());
    }

    @Test
    void independentSourceReadAndExclusionRemainOwned() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Read source.md. I want to write and save a file named story.txt with a character named Mara.txt based on source.md. "
                        + "Do not create notes.txt.");
        assertEquals(Set.of("story.txt"), resolution.contract().expectedTargets(), resolution.toString());
        assertEquals(Set.of("source.md"), resolution.contract().sourceEvidenceTargets(), resolution.toString());
        assertEquals(Set.of("notes.txt"), resolution.contract().forbiddenTargets(), resolution.toString());
    }

    @Test
    void samePathInAnIndependentMutationIsNotRemovedByValue() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Save a file named story.txt with a character named Mara.txt. Update Mara.txt.");
        assertEquals(Set.of("story.txt", "Mara.txt"), resolution.contract().expectedTargets(), resolution.toString());
    }

    @Test
    void outputAlternativesRemainAmbiguous() {
        var save = ContentSaveRequest.parse("Save a story about a woman named Mara as first.txt or second.txt.");
        assertEquals(ContentSaveRequest.Binding.AMBIGUOUS, save.operations().getFirst().destination().binding());
        assertFalse(save.supportsFilenameContinuation());
    }

    @Test
    void twoIndependentOperationsCannotBeBoundByOneFilename() {
        var save = ContentSaveRequest.parse("Save a text file with a character named Mara. Save a poem as poem.txt.");
        assertEquals(2, save.operations().size());
        assertEquals(Set.of("poem.txt"), save.resolvedTargets());
        assertFalse(save.supportsFilenameContinuation());
    }
}
