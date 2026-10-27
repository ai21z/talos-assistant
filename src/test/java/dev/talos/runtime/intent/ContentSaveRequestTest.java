package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContentSaveRequestTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "I want to write and save a txt file with a story of a woman in medieval times with one short thumb",
            "A text file containing a short medieval story is wanted, please save it in this workspace.",
            "We would like to write and save a file with an account of the expedition.",
            "Please save a text file containing a description of the orchard."
    })
    void requestedSaveSurvivesMissingDestinationThroughActualResolver(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var save = resolution.intent().contentSaveRequest();
        assertAll(
                () -> assertTrue(save.mutationRequested(), save.toString()),
                () -> assertTrue(save.clarificationRequired(), save.toString()),
                () -> assertTrue(resolution.contract().mutationRequested(), resolution.toString()),
                () -> assertFalse(resolution.contract().mutationAllowed(), resolution.toString()),
                () -> assertTrue(save.operations().getFirst().content().resolved(), save.toString()),
                () -> assertEquals(ContentSaveRequest.Binding.MISSING, save.operations().getFirst().destination().binding())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I want to write and save a txt file named story.txt with a short medieval story.",
            "A text file containing a short medieval story is wanted, please save it as story.txt.",
            "We need to write and save a file called story.txt containing an account of the expedition.",
            "Please save a description of the orchard as story.txt."
    })
    void resolvedDestinationReachesExistingTypedAuthority(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var save = resolution.intent().contentSaveRequest();
        assertTrue(save.mutationRequested(), save.toString());
        assertFalse(save.clarificationRequired(), save.toString());
        assertTrue(resolution.contract().mutationRequested(), resolution.toString());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("story.txt"), resolution.contract().expectedTargets());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I want to learn how to save a file named story.txt.",
            "Teach me how to write and save a file named story.txt.",
            "Can you save files in this workspace?",
            "Could you write and save files?",
            "The guide says I want to write and save a file named story.txt.",
            "The operator wants to write and save a file named story.txt.",
            "If a story is needed, please save it as story.txt.",
            "Save a short story as story.txt if needed.",
            "Do not write and save a file named story.txt.",
            "I do not want to write and save a file named story.txt.",
            "Explain the instruction \"write and save a file named story.txt\".",
            "\"Please save a story as story.txt.\"",
            "The report states: please save a story as story.txt.",
            "If a file containing a story is wanted, please save it as story.txt."
    })
    void scopedAndInstructionalLanguageDoesNotAcquireWriteAuthority(String request) {
        var save = ContentSaveRequest.parse(request);
        assertFalse(save.mutationRequested(), save.toString());
        assertFalse(TaskContractResolver.fromUserRequest(request).mutationAllowed(), request);
    }

    @Test void unboundReferentsKeepBothMissingOperands() {
        var save = ContentSaveRequest.parse("Save it there.");
        assertTrue(save.mutationRequested());
        assertTrue(save.clarificationRequired());
        assertEquals(ContentSaveRequest.Binding.MISSING, save.operations().getFirst().destination().binding());
        assertEquals(ContentSaveRequest.Binding.MISSING, save.operations().getFirst().content().binding());
        assertFalse(TaskContractResolver.fromUserRequest("Save it there.").mutationAllowed());
    }

    @Test void destinationAlternativesAreNotSilentlySelected() {
        var save = ContentSaveRequest.parse("Save a story as first.txt or second.txt.");
        assertTrue(save.mutationRequested());
        assertEquals(ContentSaveRequest.Binding.AMBIGUOUS, save.operations().getFirst().destination().binding());
        assertFalse(TaskContractResolver.fromUserRequest(save.originalRequest()).mutationAllowed());
    }

    @Test void filenameAloneCannotSupplyContent() {
        var save = ContentSaveRequest.parse("Save story.txt.");
        assertTrue(save.mutationRequested());
        assertTrue(save.operations().getFirst().destination().resolved());
        assertEquals(ContentSaveRequest.Binding.MISSING, save.operations().getFirst().content().binding());
        assertTrue(save.clarificationRequired());
        assertFalse(TaskContractResolver.fromUserRequest(save.originalRequest()).mutationAllowed());
    }

    @Test void formatDescriptionDoesNotInventAFilename() {
        var save = ContentSaveRequest.parse("Save a story as plain text.");
        assertTrue(save.mutationRequested());
        assertEquals(ContentSaveRequest.Binding.MISSING, save.operations().getFirst().destination().binding());
        assertTrue(save.clarificationRequired());
    }

    @Test void independentRequiredMutationSurvivesOptionalSave() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Save a poem as verse.txt if needed. Create report.txt with a short account of the voyage.");
        assertTrue(resolution.contract().mutationRequested(), resolution.toString());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertTrue(resolution.contract().expectedTargets().contains("report.txt"), resolution.toString());
        assertFalse(resolution.contract().expectedTargets().contains("verse.txt"), resolution.toString());
    }

    @Test void globalDraftOnlyRestrictionRemainsAnAuthorityCeiling() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "I want to write and save a file named story.txt with a story, but do not change any files.");
        assertFalse(resolution.contract().mutationAllowed(), resolution.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Create a new file named story.txt containing a short story.",
            "I want to write a file named story.txt with a short story.",
            "Please put a short story in a new file called story.txt."
    })
    void explicitFileConstructionUsesTheSameOperationReadiness(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertTrue(resolution.intent().contentSaveRequest().mutationRequested(), resolution.toString());
        assertFalse(resolution.intent().contentSaveRequest().clarificationRequired(), resolution.toString());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("story.txt"), resolution.contract().expectedTargets());
    }

    @Test void originalOffsetsAndQuotedContentRemainUnchanged() {
        String request = "Please save \"Do not write forbidden.txt.\" as `My Story.txt`.";
        var save = ContentSaveRequest.parse(request);
        var operation = save.operations().getFirst();
        assertEquals(request, save.originalRequest());
        assertEquals("save", operation.action().text(request));
        assertEquals("My Story.txt", operation.destination().source().text(request));
        assertEquals("Do not write forbidden.txt.", operation.content().source().text(request));
        assertEquals(Set.of("My Story.txt"), save.resolvedTargets());
    }

    @Test void leadingQuotedProseIsContentAndCannotBecomeAnImplicitFilename() {
        String request = "Save \"Calculate 2 + 2 and tell me which workspace is active.\".";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var operation = resolution.intent().contentSaveRequest().operations().getFirst();
        assertEquals(ContentSaveRequest.Binding.MISSING, operation.destination().binding(), resolution.toString());
        assertEquals("Calculate 2 + 2 and tell me which workspace is active.", operation.content().value());
        assertEquals(operation.content().value(), operation.content().source().text(request));
        assertTrue(resolution.contract().expectedTargets().isEmpty(), resolution.toString());
        assertFalse(resolution.contract().mutationAllowed(), resolution.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Save `story.txt`.", "Save file `My Story.txt`."})
    void explicitQuotedPathOperandsStillNeedContent(String request) {
        var save = ContentSaveRequest.parse(request);
        assertTrue(save.operations().getFirst().destination().resolved(), save.toString());
        assertEquals(ContentSaveRequest.Binding.MISSING, save.operations().getFirst().content().binding());
        assertTrue(save.clarificationRequired());
    }

    @Test void quotedPayloadAndFilenameReachOnlyTheirOwnedDownstreamRoles() {
        String request = "Please save \"Do not write forbidden.txt.\" as `My Story.txt`.";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertTrue(resolution.contract().mutationRequested(), resolution.toString());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("My Story.txt"), resolution.contract().expectedTargets(), resolution.toString());
        assertTrue(resolution.contract().forbiddenTargets().isEmpty(), resolution.toString());
        assertEquals("Do not write forbidden.txt.",
                resolution.intent().contentSaveRequest().operations().getFirst().content().value());
    }

    @Test void quotedPayloadCannotConcealAnUnquotedGlobalProhibition() {
        String request = "Please save \"Do not write forbidden.txt.\" as story.txt, but do not change any files.";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertFalse(resolution.contract().mutationAllowed(), resolution.toString());
        assertTrue(TaskContractResolver.explicitlyForbidsMutation(request));
    }

    @Test void literalPayloadMaskKeepsQuotedExternalForbiddenOperands() {
        String request = "Save \"Read secret.txt and do not write forbidden.txt.\" as story.txt, but do not use `notes.txt`.";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("story.txt"), resolution.contract().expectedTargets(), resolution.toString());
        assertEquals(Set.of("notes.txt"), resolution.contract().forbiddenTargets(), resolution.toString());
        assertTrue(resolution.contract().sourceEvidenceTargets().isEmpty(), resolution.toString());
    }

    @Test void unresolvedSaveRetainsLocalProhibitionWithoutARequiredWriteToItsTarget() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Save a short story, but do not overwrite notes.txt.");
        assertTrue(resolution.intent().contentSaveRequest().clarificationRequired(), resolution.toString());
        assertTrue(resolution.contract().mutationRequested(), resolution.toString());
        assertFalse(resolution.contract().mutationAllowed(), resolution.toString());
        assertTrue(resolution.contract().expectedTargets().isEmpty(), resolution.toString());
        assertEquals(Set.of("notes.txt"), resolution.contract().forbiddenTargets(), resolution.toString());
        assertTrue(resolution.intent().targets().targets().stream()
                .filter(target -> target.path().equals("notes.txt"))
                .allMatch(target -> target.role() == TargetRole.FORBIDDEN), resolution.toString());
    }

    @Test void quotedProhibitionIsPayloadNotALocalTargetRestriction() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Save \"Do not overwrite notes.txt.\" as story.txt.");
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("story.txt"), resolution.contract().expectedTargets(), resolution.toString());
        assertTrue(resolution.contract().forbiddenTargets().isEmpty(), resolution.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Read helper.py and create a new file named helper_copy.py containing the exact same code as helper.py.",
            "Read helper.py and write a file called helper_copy.py with an explanation using the same examples as helper.py."
    })
    void contentClauseComparisonsCannotCounterbindTheDeclaredDestination(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var save = resolution.intent().contentSaveRequest();
        assertFalse(save.clarificationRequired(), save.toString());
        assertEquals(Set.of("helper_copy.py"), save.resolvedTargets(), save.toString());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("helper_copy.py"), resolution.contract().expectedTargets(), resolution.toString());
        assertEquals(Set.of("helper.py"), resolution.contract().sourceEvidenceTargets(), resolution.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void emptyInterpretationRetainsLegacyCanonicalSource(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertFalse(resolution.intent().contentSaveRequest().present());
        assertEquals(resolution.intent().originalUserRequest(),
                resolution.intent().contentSaveRequest().originalRequest());
        assertFalse(resolution.contract().mutationAllowed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I want to learn how to rename old.txt to new.txt.",
            "I plan to rename old.txt to new.txt.",
            "I intend to move old.txt to new.txt.",
            "I do not want to rename old.txt to new.txt.",
            "If needed, I want to rename old.txt to new.txt.",
            "The operator wants to rename old.txt to new.txt.",
            "The guide says I want to rename old.txt to new.txt.",
            "\"I want to rename old.txt to new.txt.\""
    })
    void firstPersonWorkspaceScopeControlsKeepExactOperationAuthorityClosed(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertTrue(resolution.workspaceOperationPlan().authorizedOperations().isEmpty(), resolution.toString());
        assertFalse(resolution.intent().contentSaveRequest().present(), resolution.toString());
    }

    @Test void explicitWorkspaceOperationRetainsExactTypedPlan() {
        var resolution = TaskContractResolver.resolveFromUserRequest("Rename old.txt to new.txt.");
        assertEquals(1, resolution.workspaceOperationPlan().authorizedOperations().size(), resolution.toString());
        var operation = resolution.workspaceOperationPlan().authorizedOperations().getFirst();
        assertEquals(WorkspaceOperationObligation.Kind.RENAME_PATH, operation.kind());
        assertEquals("old.txt", operation.sourcePath());
        assertEquals("new.txt", operation.destinationPath());
        assertFalse(resolution.intent().contentSaveRequest().present());
    }

    @Test void sourceReadObligationsSurviveSaveRecognition() {
        var resolution = TaskContractResolver.resolveFromUserRequest(
                "Read source.md. I want to write and save a file named result.txt with a summary based on source.md.");
        assertTrue(resolution.contract().mutationRequested(), resolution.toString());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("result.txt"), resolution.contract().expectedTargets());
        assertTrue(resolution.contract().sourceEvidenceTargets().contains("source.md"), resolution.toString());
    }

    @Test void independentSaveDirectivesKeepTheirOwnTargets() {
        var save = ContentSaveRequest.parse("Save a story as first.txt. Save a poem as second.txt.");
        assertEquals(2, save.operations().size());
        assertEquals(Set.of("first.txt", "second.txt"), save.resolvedTargets());
        assertFalse(save.clarificationRequired());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Create a README.md file.",
            "Create the README.md file you need in this workspace.",
            "Create a file named README.md.",
            "Create a file at README.md.",
            "Create `README.md`."
    })
    void namedCreationCarriesAGenerationSpecificationWithoutRequiringSuppliedBytes(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var save = resolution.intent().contentSaveRequest();
        var operation = save.operations().getFirst();
        assertTrue(operation.ready(), save.toString());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of("README.md"), resolution.contract().expectedTargets());
        assertEquals(request, operation.content().value());
        assertEquals(request, operation.content().source().text(request));
        assertEquals(request, save.withoutQuotedLiteralPayloads());
        assertTrue(dev.talos.runtime.expectation.TaskExpectationResolver.resolve(resolution.contract()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"README", ".env"})
    void quotedCreationOutputsOwnTheirSpanBeforePayloadMasking(String target) {
        String request = "Create `" + target + "`. Update style.css.";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var save = resolution.intent().contentSaveRequest();
        var operation = save.operations().getFirst();
        assertEquals(target, operation.destination().value());
        assertEquals(target, operation.destination().source().text(request));
        assertEquals(request, save.withoutQuotedLiteralPayloads());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertEquals(Set.of(target, "style.css"), resolution.contract().expectedTargets());
        assertEquals(1, resolution.sequentialMutationPlan().occurrenceDecisions().size());
    }

    @Test
    void staticWebsiteObjectLabelDoesNotBecomeAnAdditionalFileOutput() {
        String request = "Create a complete modern dark synthwave static website for a band called Retrocats. "
                + "Use exactly index.html, style.css, and script.js as the local files. "
                + "Do not create a local tailwind.min.css file. "
                + "The site must preserve these required visible facts: Retrocats, Costanza, "
                + "Berlin 22 July 2026.";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var save = resolution.intent().contentSaveRequest();

        assertFalse(save.mutationRequested(), save.toString());
        assertFalse(save.resolvedTargets().contains("Retrocats"), save.toString());
        assertEquals(Set.of("index.html", "style.css", "script.js"), resolution.contract().expectedTargets());
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertTrue(resolution.contract().forbiddenTargets().contains("tailwind.min.css"));
        assertTrue(dev.talos.runtime.capability.StaticWebCapabilityProfile
                .prefersFullFileWriteForInitialApply(resolution.contract()), resolution.toString());
        assertEquals(request, resolution.contract().originalUserRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Create notes.txt about a band called Retrocats.",
            "Create notes.txt containing an example named Demo.",
            "Create notes.txt with a character described as Morgan."
    })
    void boundCreationOutputCannotBeDisplacedByNamesInsideGeneratedContent(String request) {
        var save = ContentSaveRequest.parse(request);
        var operation = save.operations().getFirst();

        assertTrue(operation.ready(), save.toString());
        assertEquals(Set.of("notes.txt"), save.resolvedTargets());
        assertEquals("notes.txt", operation.destination().source().text(request));
        assertEquals(request, operation.content().value());
        assertEquals(request, save.withoutQuotedLiteralPayloads());
    }

    @Test
    void quotedExtensionlessCreationOutputRetainsOwnershipBeforeAContentLabel() {
        String request = "Create `README` containing an example named Demo.";
        var save = ContentSaveRequest.parse(request);
        var operation = save.operations().getFirst();

        assertTrue(operation.ready(), save.toString());
        assertEquals(Set.of("README"), save.resolvedTargets());
        assertEquals("README", operation.destination().source().text(request));
        assertEquals(request, save.withoutQuotedLiteralPayloads());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Create a sculpture called Aurora.",
            "Create a character named Morgan.",
            "Create an illustration described as Sunrise."
    })
    void nonFileCreationObjectLabelsDoNotSupplyFileDestinations(String request) {
        var save = ContentSaveRequest.parse(request);
        assertFalse(save.present(), save.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Create a file at report.txt or backup.txt.",
            "Create `README` or `LICENSE`."
    })
    void directCreationOutputAlternativesStillRequireClarification(String request) {
        var save = ContentSaveRequest.parse(request);
        assertTrue(save.clarificationRequired(), save.toString());
        assertEquals(ContentSaveRequest.Binding.AMBIGUOUS, save.operations().getFirst().destination().binding());
    }

    @Test void fileObjectLocativeKeepsUnsafeTargetForTheNormalPathGuard() {
        String request = "Create a file at ../outside-talos-qa.txt with the text hello from Talos.";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        var operation = resolution.intent().contentSaveRequest().operations().getFirst();
        assertEquals("../outside-talos-qa.txt", operation.destination().value());
        assertEquals("../outside-talos-qa.txt", operation.destination().source().text(request));
        assertFalse(resolution.intent().contentSaveRequest().clarificationRequired());
        assertEquals(Set.of("../outside-talos-qa.txt"), resolution.contract().expectedTargets());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Create a directory named workspace-notes.",
            "Create `cache` directory.",
            "Edit index.html so it works, then create \".config\" and assets, then copy style.css to .config/style.css."
    })
    void directoryAndNaturalWorkspaceOperandsAreNotContentSaveOperations(String request) {
        var save = ContentSaveRequest.parse(request);
        assertFalse(save.present(), save.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Save story.txt.",
            "Save it there.",
            "Write a file named story.txt.",
            "Create it as story.txt."
    })
    void creationReadinessDoesNotSupplyUnknownSavedContent(String request) {
        var save = ContentSaveRequest.parse(request);
        assertTrue(save.clarificationRequired(), save.toString());
        assertEquals(ContentSaveRequest.Binding.MISSING, save.operations().getFirst().content().binding());
        assertFalse(TaskContractResolver.fromUserRequest(request).mutationAllowed(), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Do not create a README.md file.",
            "If needed, create a README.md file.",
            "The report says create a README.md file.",
            "Explain how to create a README.md file."
    })
    void generationSpecificationsDoNotPromoteScopedCreationAuthority(String request) {
        var save = ContentSaveRequest.parse(request);
        assertFalse(save.mutationRequested(), save.toString());
        assertFalse(TaskContractResolver.fromUserRequest(request).mutationAllowed(), request);
    }
}
