package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskIntentResolverLexicalEvidenceTest {

    @Test
    void boundedAbbreviationsCannotTerminateOwnedTailOrReopenMutationAuthority() {
        String request = "Edit index.html so e.g. "
                + "update style.css only as a proposal.";
        RequestLexicalView view = RequestLexicalView.of(request);
        int abbreviationEnd = request.indexOf("e.g.") + "e.g.".length() - 1;
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        CoordinatedMutationTailPlan.TargetDecision style =
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow();

        assertFalse(view.isSentenceBoundaryAt(abbreviationEnd), request);
        assertEquals(request.length(), view.sentenceEndAfter(
                request.indexOf("so")), request);
        assertEquals(Set.of("index.html"),
                resolution.contract().expectedTargets(), request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                style.authority(),
                request);
        assertEquals(TargetRole.MENTIONED_ONLY,
                resolution.intent().targets().find("style.css")
                        .orElseThrow().role(),
                request);
        assertEquals(style.sourceText(),
                request.substring(style.startOffset(), style.endOffset()),
                request);
    }

    @Test
    void commonAndMultiInitialAbbreviationsShareOneBoundedBoundaryRule() {
        for (String abbreviation : List.of("i.e.", "etc.", "U.S.")) {
            String request = "Edit index.html so " + abbreviation
                    + " update style.css only as a proposal.";
            RequestLexicalView view = RequestLexicalView.of(request);
            int abbreviationEnd =
                    request.indexOf(abbreviation) + abbreviation.length() - 1;

            assertFalse(view.isSentenceBoundaryAt(abbreviationEnd), request);
            assertEquals(request.length(),
                    view.sentenceEndAfter(request.indexOf("so")),
                    request);
        }
    }

    @Test
    void realSentenceEndAfterAbbreviationStillBoundsTheOwnedTail() {
        String request = "Edit index.html so e.g. update style.css only as a "
                + "proposal. Update script.js.";
        RequestLexicalView view = RequestLexicalView.of(request);
        int proposalEnd =
                request.indexOf("proposal.") + "proposal.".length() - 1;

        assertTrue(view.isSentenceBoundaryAt(proposalEnd), request);
        assertEquals(proposalEnd + 1,
                view.sentenceEndAfter(request.indexOf("so")),
                request);
    }

    @Test
    void explicitReadEvidenceSurvivesOptionalMutationOfSameTarget() {
        String request = "Edit index.html so read style.css, "
                + "and update style.css if needed.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        TargetRef style =
                resolution.intent().targets().find("style.css")
                        .orElseThrow();
        CoordinatedMutationTailPlan.TargetDecision styleDecision =
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow();

        assertEquals(Set.of("index.html"),
                resolution.contract().expectedTargets(), request);
        assertEquals(Set.of("style.css"),
                resolution.contract().sourceEvidenceTargets(), request);
        assertEquals(TargetRole.SOURCE_EVIDENCE, style.role(), request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.OPTIONAL,
                styleDecision.authority(),
                request);
        assertFalse(
                resolution.intent().targets()
                        .pathsByRole(TargetRole.MAY_MUTATE)
                        .contains("style.css"),
                request);
        assertEquals(styleDecision.sourceText(),
                request.substring(
                        styleDecision.startOffset(),
                        styleDecision.endOffset()),
                request);
    }

    @Test
    void explicitReadEvidenceCombinesWithRequiredConstraintTailMutation() {
        String request =
                "Edit index.html so read style.css, then update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                Set.of("index.html", "style.css"),
                resolution.contract().expectedTargets(),
                request);
        assertEquals(
                Set.of("style.css"),
                resolution.contract().sourceEvidenceTargets(),
                request);
        assertEquals(
                TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow()
                        .authority(),
                request);
    }

    @Test
    void reportedOrNegatedReadsDoNotPreserveTailOnlySourceAuthority() {
        for (String request : List.of(
                "Edit index.html so the report says to read style.css, "
                        + "and update style.css if needed.",
                "Edit index.html so the report says e.g. read style.css, "
                        + "and update style.css if needed.",
                "Edit index.html so the report says then read style.css, "
                        + "and update style.css if needed.",
                "Edit index.html so Alice says then read style.css, "
                        + "and update style.css if needed.",
                "Edit index.html so maybe then read style.css, "
                        + "and update style.css if needed.",
                "Edit index.html so only as a proposal, then read style.css, "
                        + "and update style.css if needed.",
                "Edit index.html so do not read style.css, "
                        + "and update style.css if needed.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertTrue(
                    resolution.contract().sourceEvidenceTargets().isEmpty(),
                    request);
            assertEquals(TargetRole.MENTIONED_ONLY,
                    resolution.intent().targets().find("style.css")
                            .orElseThrow().role(),
                request);
        }
    }

    @Test
    void onlyIndependentlyRequiredOutsideTailReadCanRetainSourceAuthority() {
        String positive =
                "Read style.css. Edit index.html so update style.css if needed.";
        TaskResolution required =
                TaskContractResolver.resolveFromUserRequest(positive);

        assertEquals(
                Set.of("style.css"),
                required.contract().sourceEvidenceTargets(),
                positive);
        assertEquals(
                TargetRole.SOURCE_EVIDENCE,
                required.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                positive);

        String freshThen =
                "The report says no, then read style.css. "
                        + "Edit index.html so update style.css if needed.";
        TaskResolution freshRequired =
                TaskContractResolver.resolveFromUserRequest(freshThen);
        assertEquals(
                Set.of("style.css"),
                freshRequired.contract().sourceEvidenceTargets(),
                freshThen);

        for (String negative : List.of(
                "The report says then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "Alice says then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "Maybe then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "Only as a proposal, then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "Would it help to then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "Could it make sense to then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "You could then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "I propose that we then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "For review only, then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "One option would be to then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "My suggestion is to then read style.css. "
                        + "Edit index.html so update style.css if needed.",
                "According to the report, then read style.css. "
                        + "Edit index.html so update style.css if needed.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(negative);

            assertTrue(
                    resolution.contract().sourceEvidenceTargets().isEmpty(),
                    negative);
            assertEquals(
                    TargetRole.MENTIONED_ONLY,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    negative);
        }
    }

    @Test
    void restrictiveReadScopeContinuesAcrossSemicolonAndLineBreaks() {
        String request =
                "Edit index.html so maybe read style.css;\n"
                        + "read script.js;\n"
                        + "and update style.css if needed.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertTrue(
                resolution.contract().sourceEvidenceTargets().isEmpty(),
                request);
        assertEquals(
                Set.of("index.html"),
                resolution.contract().expectedTargets(),
                request);
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
    }

    @Test
    void quotedReadLikePayloadCannotMintConstraintTailSourceAuthority() {
        String request =
                "Edit index.html so the report says \"then read style.css\", "
                        + "and update style.css if needed.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertTrue(
                resolution.contract().sourceEvidenceTargets().isEmpty(),
                request);
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
    }

    @Test
    void earlierRequiredMutationSurvivesLaterRestrictiveDuplicates() {
        for (String middle : List.of(
                "Maybe update style.css.",
                "The report says update style.css.",
                "Update style.css only as a proposal.")) {
            String request =
                    "Update style.css. "
                            + middle
                            + " Edit index.html so maybe update style.css.";
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertEquals(
                    Set.of("index.html", "style.css"),
                    resolution.contract().expectedTargets(),
                    request);
            assertEquals(
                    TargetRole.MUST_MUTATE,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertTrue(
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .isEmpty(),
                    request);
        }
    }

    @Test
    void independentMutationAuthorityUsesSentenceNotLineBoundaries() {
        for (String separator : List.of("\n", "\r\n")) {
            String continuedOptional =
                    "Maybe update style.css;" + separator
                            + "update style.css. "
                            + "Edit index.html so maybe update style.css.";
            TaskResolution restricted =
                    TaskContractResolver.resolveFromUserRequest(
                            continuedOptional);
            assertEquals(
                    Set.of("index.html"),
                    restricted.contract().expectedTargets(),
                    continuedOptional);
            assertEquals(
                    TargetRole.MENTIONED_ONLY,
                    restricted.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    continuedOptional);
        }

        String freshSentence =
                "Maybe update style.css. Update style.css. "
                        + "Edit index.html so maybe update style.css.";
        TaskResolution required =
                TaskContractResolver.resolveFromUserRequest(freshSentence);
        assertEquals(
                Set.of("index.html", "style.css"),
                required.contract().expectedTargets(),
                freshSentence);
        assertEquals(
                TargetRole.MUST_MUTATE,
                required.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                freshSentence);

        String quoted =
                "\"Update style.css.\" "
                        + "Edit index.html so maybe update style.css.";
        TaskResolution quotedResolution =
                TaskContractResolver.resolveFromUserRequest(quoted);
        assertEquals(
                Set.of("index.html"),
                quotedResolution.contract().expectedTargets(),
                quoted);

        String interveningTarget =
                "Update a.md, maybe update b.md so maybe update a.md.";
        TaskResolution interveningResolution =
                TaskContractResolver.resolveFromUserRequest(
                        interveningTarget);
        assertEquals(
                Set.of("a.md", "b.md"),
                interveningResolution.contract().expectedTargets(),
                interveningTarget);
        assertEquals(
                TargetRole.MUST_MUTATE,
                interveningResolution.intent().targets()
                        .find("a.md")
                        .orElseThrow()
                        .role(),
                interveningTarget);
    }

    @Test
    void openAdvisoryHeadersCannotResetIntoConstraintTailMutationAuthority() {
        for (String header : List.of(
                "would it help to then ",
                "could it make sense to then ",
                "you could then ",
                "I propose that we then ",
                "for review only, then ",
                "one option would be to then ",
                "my suggestion is to then ",
                "according to the report, then ")) {
            String request =
                    "Edit index.html so " + header + "update style.css.";
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertEquals(
                    Set.of("index.html"),
                    resolution.contract().expectedTargets(),
                    request);
            assertFalse(
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .map(decision -> decision.authority()
                                    == CoordinatedMutationTailPlan.Authority
                                            .REQUIRED)
                            .orElse(false),
                    request);
        }
    }

    @Test
    void thirdPartyPlansAndAttributionsCannotGrantMutationAuthority() {
        for (String tail : List.of(
                 "Alice plans to update style.css.",
                 "Alice really wants to update style.css.",
                 "Alice Smith Jr. wants to update style.css.",
                 "Team 7 wants to update style.css.",
                 "Alice O'Neil wants to update style.css.",
                 "Alice, the build bot, wants to update style.css.",
                 "Alice intends to update style.css.",
                 "Alice wants to update style.css.",
                 "Alice needs to update style.css.",
                "Alice is going to update style.css.",
                "Alice wants you to update style.css.",
                "Alice needs Talos to then update style.css.",
                "Alice expects you to update style.css.",
                "Alice would like you to update style.css.",
                "the report expects us to update style.css.",
                "Alice wants the build bot to update style.css.",
                "Alice expects Bob Smith to update style.css.",
                 "Alice needs the local runtime to then update style.css.",
                 "the report would like the assistant process to update style.css.",
                 "the report expects Bob, the build bot, to update style.css.",
                 "Alice wants Bob Jr. to update style.css.",
                 "Alice wants team 7 to update style.css.",
                 "Alice wants Bob (the build bot) to update style.css.",
                 "Alice wants Bob / the build bot to update style.css.",
                 "Alice wants Bob & the build bot to update style.css.",
                 "Alice wants build bot #7 to update style.css.",
                 "according to Alice, update style.css.",
                 "per Alice, update style.css.",
                 "the report's instruction is to update style.css.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertEquals(
                    Set.of("index.html"),
                    resolution.contract().expectedTargets(),
                    request);
            assertEquals(
                    TargetRole.MENTIONED_ONLY,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow()
                            .authority(),
                    request);
        }

         for (String request : List.of(
                 "Edit index.html so please update style.css.",
                 "Edit index.html so you really need to update style.css.",
                 "Edit index.html so Talos really needs to update style.css.",
                 "Edit index.html so we definitely need to update style.css.",
                 "Edit index.html so I need you to update style.css.",
                 "Edit index.html so we want Talos to update style.css.",
                 "Edit index.html so now need to update style.css.",
                 "Edit index.html so still need to update style.css.",
                 "Edit index.html so also need to update style.css.",
                 "Edit index.html so next need to update style.css.",
                 "Edit index.html so really need to update style.css.",
                 "Edit index.html so the report says no, then update style.css.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertEquals(
                    Set.of("index.html", "style.css"),
                    resolution.contract().expectedTargets(),
                    request);
            assertEquals(
                    TargetRole.MUST_MUTATE,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow()
                            .authority(),
                    request);
        }
    }

    @Test
    void nearestMeaningfulActorOwnsThirdPartyIntentAuthority() {
        for (String tail : List.of(
                "I think Alice wants to update style.css.",
                "I know Alice needs to update style.css.",
                "we heard the report wants to update style.css.",
                "Talos observes Alice wants to update style.css.",
                "I finished, Alice wants to update style.css.",
                "Alice thinks you should update style.css.",
                "Alice believes Talos must update style.css.",
                "the report notes you should update style.css.",
                "the report indicates Talos must update style.css.")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle("Edit index.html so " + tail),
                    tail);
        }
    }

    @Test
    void directCollaborativeActorsRemainAuthoritativeAcrossIntroductoryText() {
        for (String tail : List.of(
                "for clarity, you really need to update style.css.",
                "as discussed, Talos needs to update style.css.",
                "in this step, we need to update style.css.",
                "after that, we need to update style.css.",
                "you and Alice need to update style.css.",
                "Alice and you need to update style.css.",
                "Talos and the build bot need to update style.css.",
                "the build bot and Talos need to update style.css.",
                "we and Team 7 need to update style.css.",
                "Team 7 and we need to update style.css.",
                "I request you update style.css.",
                "I ask you to update style.css.",
                "I instruct you to update style.css.",
                "I require you to update style.css.",
                "we request Talos update style.css.",
                "we require Talos to update style.css.")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    authorityForStyle("Edit index.html so " + tail),
                    tail);
        }
    }

    @Test
    void explicitCollaborativeAuthorityResetsAThirdPartyMutationClause() {
        String request = "Edit index.html so Alice wants to update "
                + "README.md, but you need to update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                TargetRole.MENTIONED_ONLY,
                resolution.intent().targets()
                        .find("README.md")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                resolution.mutationTailPlan()
                        .decision("README.md")
                        .orElseThrow()
                        .authority(),
                request);
        assertEquals(
                TargetRole.MUST_MUTATE,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow()
                        .authority(),
                request);

        String openReported = "Edit index.html so Alice wants to update "
                + "README.md, then you need to update style.css.";
        TaskResolution openReportedResolution =
                TaskContractResolver.resolveFromUserRequest(openReported);
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                openReportedResolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                openReported);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                openReportedResolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow()
                        .authority(),
                openReported);
    }

    @Test
    void directFirstPersonDirectivesDoNotMasqueradeAsReportedAuthority() {
        for (String tail : List.of(
                "Alice requests you update style.css.",
                "Alice asks you to update style.css.",
                "Alice instructs Talos to update style.css.",
                "Alice requires Talos to update style.css.",
                "you said to update style.css.",
                "Talos reported that we should update style.css.")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle("Edit index.html so " + tail),
                    tail);
        }
    }

    @Test
    void unknownDeclarativeAndPassiveInfinitivesFailClosed() {
        for (String tail : List.of(
                "Alice decided to update style.css.",
                "Alice agreed to update style.css.",
                "Alice offered to update style.css.",
                "Alice promised to update style.css.",
                "Alice was authorized to update style.css.",
                "Alice is forbidden to update style.css.",
                "I was going to update style.css.",
                "I decided to update style.css.",
                "I genuinely want to update style.css.",
                "I really want the build bot to update style.css.",
                "we promise to update style.css.",
                "I asked you to update style.css.",
                "we requested Talos to update style.css.",
                "I requested Alice to update style.css.",
                "I direct the build bot to update style.css.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertEquals(Set.of("index.html"),
                    resolution.contract().expectedTargets(), request);
            assertEquals(
                    TargetRole.MENTIONED_ONLY,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            CoordinatedMutationTailPlan.TargetDecision decision =
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow();
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    decision.authority(),
                    request);
            assertEquals(
                    decision.sourceText(),
                    request.substring(
                            decision.startOffset(),
                            decision.endOffset()),
                    request);
        }
    }

    @Test
    void permissionOnlyAndHypotheticalDirectivesRemainNonAuthoritative() {
        for (String tail : List.of(
                "I permit you to update style.css.",
                "I allow Talos to update style.css.",
                "you are allowed to update style.css.",
                "you have permission to update style.css.",
                "I might ask you to update style.css.")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle("Edit index.html so " + tail),
                    tail);
        }
    }

    @Test
    void explicitProhibitionLanguageCannotBecomeMutationAuthority() {
        for (String tail : List.of(
                "I forbid you to update style.css.",
                "you are forbidden to update style.css.",
                "I deny permission to update style.css.",
                "I revoke your permission to update style.css.",
                "I require you not to update style.css.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow()
                            .authority(),
                    request);
            assertEquals(
                    TargetRole.FORBIDDEN,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertFalse(
                    resolution.contract().expectedTargets()
                            .contains("style.css"),
                    request);
        }
    }

    @Test
    void disjunctiveActorsCannotPromoteMutationAuthorityByWordOrder() {
        for (String subject : List.of(
                "you and/or Alice",
                "Alice and/or you",
                "you or Alice",
                "Alice or you")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle(
                            "Edit index.html so " + subject
                                    + " need to update style.css."),
                    subject);
        }
    }

    @Test
    void unsupportedActorCoordinatorsFailClosedInEveryWordOrder() {
        for (String subject : List.of(
                "you & Alice",
                "Alice & you",
                "you + Alice",
                "Alice + you",
                "you as well as Alice",
                "Alice as well as you",
                "you together with Alice",
                "Alice together with you",
                "neither you nor Alice",
                "neither Alice nor you")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle(
                            "Edit index.html so " + subject
                                    + " need to update style.css."),
                    subject);
        }
    }

    @Test
    void collaborativeActorWordsEmbeddedInLargerSubjectsFailClosed() {
        for (String subject : List.of(
                "Alice assumes Bob and you",
                "Alice thinks you and Bob",
                "Alice anticipates Bob and you",
                "the report says Alice and you",
                "the policy directs Bob and Talos")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle(
                            "Edit index.html so " + subject
                                    + " should update style.css."),
                    subject);
        }
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                authorityForStyle(
                        "Edit index.html so Alice anticipates I need you to "
                                + "update style.css."));
    }

    @Test
    void supportedProperNamesEndingInLyRemainCollaborativeSubjects() {
        for (String subject : List.of(
                "Sally and you",
                "Emily and Talos")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    authorityForStyle(
                            "Edit index.html so " + subject
                                    + " need to update style.css."),
                    subject);
        }
    }

    @Test
    void questionsAndHypotheticalFramesCannotMintMutationAuthority() {
        for (String tail : List.of(
                "may you update style.css?",
                "suppose you update style.css.",
                "assuming you update style.css.",
                "in case it helps, update style.css.")) {
            assertFalse(
                    authorityForStyle("Edit index.html so " + tail)
                            == CoordinatedMutationTailPlan.Authority.REQUIRED,
                    tail);
        }
    }

    @Test
    void unknownReportingPredicatesFailClosedStructurally() {
        for (String predicate : List.of(
                "assumes",
                "expects",
                "understands",
                "remembers",
                "imagines",
                "predicts",
                "warns",
                "confirms",
                "hopes",
                "wishes",
                "suspects",
                "reckons")) {
            for (String actor : List.of("you should", "Talos must")) {
                String tail = "Alice " + predicate + " " + actor
                        + " update style.css.";
                assertEquals(
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                        authorityForStyle("Edit index.html so " + tail),
                        tail);
            }
        }
    }

    @Test
    void declarativeTextAfterATargetCannotRetroactivelyGrantAuthority() {
        for (String tail : List.of(
                "update style.css is what Alice proposed.",
                "update style.css was Alice's suggestion.",
                "update style.css appears in the report.",
                "update style.css is only an example.",
                "update style.css would be one option.",
                "update style.css might be useful.",
                "update style.css is optional.",
                "update style.css is merely hypothetical.",
                "update style.css, Alice said.",
                "update style.css, the report recommends.",
                "update style.css, as Alice proposed.",
                "update style.css, Alice suggested.",
                "update style.css, or so Alice says.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            CoordinatedMutationTailPlan.TargetDecision decision =
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow();

            assertFalse(
                    decision.authority()
                            == CoordinatedMutationTailPlan.Authority.REQUIRED,
                    request);
            assertEquals(Set.of("index.html"),
                    resolution.contract().expectedTargets(), request);
            assertEquals(
                    decision.sourceText(),
                    request.substring(
                            decision.startOffset(),
                            decision.endOffset()),
                    request);
        }
    }

    @Test
    void declarativeProhibitionAfterATargetRemainsForbidden() {
        for (String tail : List.of(
                "update style.css is forbidden.",
                "update style.css is not allowed.",
                "update style.css is prohibited.",
                "update style.css was never authorized.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            CoordinatedMutationTailPlan.TargetDecision decision =
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow();

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    decision.authority(),
                    request);
            assertEquals(
                    TargetRole.FORBIDDEN,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertEquals(
                    decision.sourceText(),
                    request.substring(
                            decision.startOffset(),
                            decision.endOffset()),
                    request);
        }
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                authorityForStyle(
                        "Edit index.html so update style.css was rejected."));
    }

    @Test
    void positiveImperativeAndCurrentPerformativeFormsRemainAuthoritative() {
        for (String tail : List.of(
                "make sure to update style.css.",
                "be sure to update style.css.",
                "remember to update style.css.",
                "don't forget to update style.css.",
                "do not forget to update style.css.",
                "don't fail to update style.css.",
                "do not fail to update style.css.",
                "avoid failing to update style.css.",
                "please don't forget to update style.css.",
                "kindly avoid failing to update style.css.",
                "I am asking you to update style.css.",
                "I hereby request you to update style.css.",
                "we do require Talos to update style.css.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            CoordinatedMutationTailPlan.TargetDecision decision =
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow();

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    decision.authority(),
                    request);
            assertEquals(
                    Set.of("index.html", "style.css"),
                    resolution.contract().expectedTargets(),
                    request);
            assertEquals(
                    decision.sourceText(),
                    request.substring(
                            decision.startOffset(),
                            decision.endOffset()),
                    request);
        }
    }

    @Test
    void positiveImperativeExceptionsDoNotWeakenRealProhibitions() {
        for (String tail : List.of(
                "do not update style.css.",
                "don't update style.css.",
                "avoid updating style.css.",
                "do not fail to not update style.css.",
                "remember not to update style.css.",
                "make sure not to update style.css.")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    authorityForStyle("Edit index.html so " + tail),
                    tail);
        }
    }

    @Test
    void positiveDirectiveFramesCannotEscapeReportedOrPastTenseOwnership() {
        for (String tail : List.of(
                "Alice says make sure to update style.css.",
                "I was asking you to update style.css.",
                "the report says: make sure to update style.css.",
                "Alice says, don't forget to update style.css.",
                "the report says\nI hereby request you to update style.css.")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle("Edit index.html so " + tail),
                tail);
        }
        assertEquals(
                CoordinatedMutationTailPlan.Authority.OPTIONAL,
                authorityForStyle(
                        "Edit index.html so maybe: make sure to "
                                + "update style.css."));
    }

    @Test
    void completedQuotedReportAllowsOnlyAFreshExplicitMutationClause() {
        for (String[] quote : List.of(
                new String[]{"'", "'"},
                new String[]{"\u2018", "\u2019"},
                new String[]{"\"", "\""},
                new String[]{"`", "`"},
                new String[]{"```", "```"})) {
            for (String connector : List.of(", but ", ", then ")) {
                String request = "Edit index.html so Alice said "
                        + quote[0] + "update README.md" + quote[1]
                        + connector + "update style.css.";
                TaskResolution resolution =
                        TaskContractResolver.resolveFromUserRequest(request);

                assertEquals(
                        Set.of("index.html", "style.css"),
                        resolution.contract().expectedTargets(),
                        request);
                CoordinatedMutationTailPlan.TargetDecision style =
                        resolution.mutationTailPlan()
                                .decision("style.css")
                                .orElseThrow(() -> new AssertionError(request));
                assertEquals(
                        CoordinatedMutationTailPlan.Authority.REQUIRED,
                        style.authority(),
                        request);
                assertEquals(
                        style.sourceText(),
                        request.substring(
                                style.startOffset(),
                                style.endOffset()),
                        request);
            }
        }
    }

    @Test
    void completedQuotedReportAllowsFreshMutationAfterDeclaredBoundaries() {
        for (String[] quote : List.of(
                new String[]{"'", "'"},
                new String[]{"\"", "\""},
                new String[]{"`", "`"},
                new String[]{"```", "```"})) {
            for (String delimiter : List.of(
                    "; ",
                    ": ",
                    " \u2013 ",
                    " \u2014 ",
                    "\n",
                    "\r\n")) {
                String request = "Edit index.html so Alice said "
                        + quote[0] + "update README.md" + quote[1]
                        + delimiter + "update style.css.";
                TaskResolution resolution =
                        TaskContractResolver.resolveFromUserRequest(request);
                CoordinatedMutationTailPlan.TargetDecision style =
                        resolution.mutationTailPlan()
                                .decision("style.css")
                                .orElseThrow(() -> new AssertionError(request));

                assertEquals(
                        CoordinatedMutationTailPlan.Authority.REQUIRED,
                        style.authority(),
                        request);
                assertEquals(
                        Set.of("index.html", "style.css"),
                        resolution.contract().expectedTargets(),
                        request);
                assertEquals(
                        style.sourceText(),
                        request.substring(
                                style.startOffset(),
                                style.endOffset()),
                        request);
            }
        }
    }

    @Test
    void completedQuotedReportCannotEraseAnOuterRestrictiveOwner() {
        assertEquals(
                CoordinatedMutationTailPlan.Authority.OPTIONAL,
                authorityForStyle(
                        "Edit index.html so maybe Alice said "
                                + "\"update README.md\"; update style.css."));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                authorityForStyle(
                        "Edit index.html so only as a proposal, Alice said "
                                + "\"update README.md\": update style.css."));
    }

    @Test
    void onlyAnImmediateCompletedReportedQuoteCanResetAuthority() {
        for (String tail : List.of(
                "Proposal: update style.css.",
                "Alice said \"update README.md\" and update style.css.",
                "Alice said \"update README.md; update style.css.\"",
                "Alice said \"example\" and then described update README.md: "
                        + "update style.css.",
                "Alice said \"update README.md; update style.css.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertFalse(
                    resolution.contract().expectedTargets()
                            .contains("style.css"),
                    request);
            assertFalse(
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .map(decision -> decision.authority()
                                    == CoordinatedMutationTailPlan.Authority
                                            .REQUIRED)
                            .orElse(false),
                    request);
        }
    }

    @Test
    void ordinaryPostTargetMutationInstructionsRemainRequired() {
        for (String tail : List.of(
                "update style.css.",
                "update style.css carefully.",
                "update style.css to match index.html.",
                "update style.css to contain \"is forbidden\".")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            CoordinatedMutationTailPlan.TargetDecision style =
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow();

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.REQUIRED,
                    style.authority(),
                    request);
            assertEquals(
                    style.sourceText(),
                    request.substring(
                            style.startOffset(),
                            style.endOffset()),
                    request);
        }
    }

    @Test
    void equivalentPostTargetDeclarativesRetainRestrictivePrecedence() {
        for (String tail : List.of(
                "update style.css is never authorized.",
                "update style.css was forbidden.",
                "update style.css is disallowed.",
                "update style.css is no longer permitted.",
                "update style.css, which is forbidden.")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    authorityForStyle("Edit index.html so " + tail),
                    tail);
        }
        for (String tail : List.of(
                "update style.css is just an example.",
                "update style.css is a proposal.",
                "update style.css is important.",
                "update style.css seems useful.")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle("Edit index.html so " + tail),
                    tail);
        }
        assertEquals(
                CoordinatedMutationTailPlan.Authority.OPTIONAL,
                authorityForStyle(
                        "Edit index.html so update style.css may be useful."));
    }

    @Test
    void caseSensitiveEnvironmentalQualifierIsNotAHypotheticalFrame() {
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                authorityForStyle(
                        "Edit index.html so in case-sensitive mode, "
                                + "update style.css."));
    }

    @Test
    void openRestrictiveScopesSurviveUnicodeDashesBeforeThen() {
        for (String dash : List.of("\u2013", "\u2014")) {
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.OPTIONAL,
                    authorityForStyle(
                            "Edit index.html so maybe " + dash
                                    + " then update style.css."));
            for (String restrictive : List.of(
                    "only as a proposal",
                    "the report says")) {
                assertEquals(
                        CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                        authorityForStyle(
                                "Edit index.html so " + restrictive + " "
                                        + dash + " then update style.css."));
            }
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    authorityForStyle(
                            "Edit index.html so do not " + dash
                                    + " then update style.css."));
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle(
                            "Edit index.html so the report says no " + dash
                                    + " then update style.css."));
        }
    }

    @Test
    void optionalAndReportedScopesSurviveEveryDeclaredContinuationDelimiter() {
        for (String delimiter : List.of(
                ", ",
                "; ",
                ": ",
                " \u2013 ",
                " \u2014 ",
                "\n",
                "\r\n",
                "\n- ",
                "\r\n* ")) {
            String printable = delimiter.replace("\r", "\\r")
                    .replace("\n", "\\n");
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.OPTIONAL,
                    authorityForStyle(
                            "Edit index.html so maybe"
                                    + delimiter
                                    + "update style.css."),
                    "optional delimiter: " + printable);
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle(
                            "Edit index.html so the report says"
                                    + delimiter
                                    + "update style.css."),
                    "reported delimiter: " + printable);
        }
        assertEquals(
                CoordinatedMutationTailPlan.Authority.OPTIONAL,
                authorityForStyle(
                        "Edit index.html so maybe, and then update style.css."));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                authorityForStyle(
                        "Edit index.html so the report says, and then "
                                + "update style.css."));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                authorityForStyle(
                        "Edit index.html so maybe, then update style.css."));
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                authorityForStyle(
                        "Edit index.html so the report says, but "
                                + "update style.css."));
    }

    @Test
    void caseDistinctProhibitionDoesNotEraseRequiredMutationAuthority() {
        String request = "Update A.md so do not edit a.md.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(Set.of("A.md"),
                resolution.contract().expectedTargets(), request);
        assertEquals(Set.of("a.md"),
                resolution.contract().forbiddenTargets(), request);
        assertEquals(
                TargetRole.MUST_MUTATE,
                resolution.intent().targets()
                        .find("A.md")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                TargetRole.FORBIDDEN,
                resolution.intent().targets()
                        .find("a.md")
                        .orElseThrow()
                        .role(),
                request);
    }

    @Test
    void caseDistinctConstraintTargetCannotWidenMutationAuthority() {
        String request =
                "Update A.md so a.md remains valid, and maybe update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                Set.of("A.md"),
                resolution.contract().expectedTargets(),
                request);
        assertEquals(
                TargetRole.MUST_MUTATE,
                resolution.intent().targets()
                        .find("A.md")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                TargetRole.VERIFY_ONLY,
                resolution.intent().targets()
                        .find("a.md")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
    }

    @Test
    void manyConstraintTailsUseOneBoundedNonrecursiveSourcePass() {
        StringBuilder request = new StringBuilder();
        for (int index = 0; index < 30; index++) {
            request.append("Edit out")
                    .append(index)
                    .append(".md so maybe update opt")
                    .append(index)
                    .append(".md. ");
        }

        TaskResolution resolution = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> TaskContractResolver.resolveFromUserRequest(
                        request.toString()));

        assertTrue(resolution.contract().sourceEvidenceTargets().isEmpty());
        assertEquals(
                30,
                resolution.contract().expectedTargets().stream()
                        .filter(path -> path.startsWith("out"))
                        .count());
    }

    @Test
    void directSourceToOutputConnectorSurvivesALaterOptionalCommandTail() {
        String request = "Create dijkstra.py and test_dijkstra.py according "
                + "to problem.md, then run pytest if available.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                Set.of("problem.md"),
                resolution.contract().sourceEvidenceTargets(),
                request);
        assertEquals(
                Set.of("dijkstra.py", "test_dijkstra.py"),
                resolution.contract().expectedTargets(),
                request);
        assertEquals(
                TargetRole.SOURCE_EVIDENCE,
                resolution.intent().targets().find("problem.md")
                        .orElseThrow()
                        .role(),
                request);
    }

    @Test
    void quotedReportedAndProposedSourceConnectorsCannotCreateReadAuthority() {
        for (String request : List.of(
                "Create dijkstra.py. The report says according to "
                        + "problem.md, then run pytest if available.",
                "Create dijkstra.py. The proposal is to create it according "
                        + "to problem.md, then run pytest if available.",
                "Create dijkstra.py. Preserve the text \"according to "
                        + "problem.md\", then run pytest if available.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertFalse(
                    resolution.contract().sourceEvidenceTargets()
                            .contains("problem.md"),
                    request);
        }
    }

    @Test
    void genericReportedSourceConnectorsCannotCreateReadAuthority() {
        for (String request : List.of(
                "Create result.md. Alice says according to source.md.",
                "Create result.md. The build bot stated based on source.md.",
                "Create result.md. According to the report, secret.md must not be read.",
                "Proposal: create result.md based on secret.md.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertFalse(
                    TaskIntentResolver
                            .hasAuthoritativeDirectSourceToOutputBinding(
                                    request,
                                    request.contains("secret.md")
                                            ? "secret.md"
                                            : "source.md"),
                    request);
            assertFalse(
                    resolution.contract().sourceEvidenceTargets()
                            .contains(request.contains("secret.md")
                                    ? "secret.md"
                                    : "source.md"),
                    request);
        }
    }

    @Test
    void freshContrastiveClauseAfterGenericReportKeepsDirectSourceAuthority() {
        String request = "Alice says no, but create result.md according to "
                + "source.md.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertTrue(
                TaskIntentResolver.hasAuthoritativeDirectSourceToOutputBinding(
                        request,
                        "source.md"),
                request);
        assertEquals(
                Set.of("source.md"),
                resolution.contract().sourceEvidenceTargets(),
                request);
        assertEquals(
                Set.of("result.md"),
                resolution.contract().expectedTargets(),
                request);
    }

    @Test
    void directSourceBindingRejectsDescendantPrefixesAndNormalizesWindowsSeparators() {
        String descendant = "Create result.md based on dir.md\\secret.txt.";
        assertFalse(
                TaskIntentResolver.hasAuthoritativeDirectSourceToOutputBinding(
                        descendant,
                        "dir.md"),
                descendant);

        String exact = "Create result.md based on docs\\source.md.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(exact);
        assertTrue(
                TaskIntentResolver.hasAuthoritativeDirectSourceToOutputBinding(
                        exact,
                        "docs/source.md"),
                exact);
        assertEquals(
                Set.of("docs/source.md"),
                resolution.contract().sourceEvidenceTargets(),
                exact);
    }

    @Test
    void directSourceBindingSelectsOneOrderedExactOperandList() {
        String request =
                "Create result.md according to a.md and \"./docs/b.md\".";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        List<TaskIntentResolver.DirectSourceBinding> bindings =
                TaskIntentResolver.directSourceBindings(request);

        assertEquals(Set.of("a.md", "docs/b.md"),
                resolution.contract().sourceEvidenceTargets(), request);
        assertEquals(1, bindings.size(), request);
        var binding = bindings.getFirst();
        assertEquals(List.of("result.md"), binding.outputPaths(), request);
        assertEquals("according to", binding.connectorText(), request);
        assertEquals(binding.connectorText(),
                request.substring(
                        binding.connectorStart(),
                        binding.connectorEnd()),
                request);
        assertEquals(
                List.of("a.md", "docs/b.md"),
                binding.sourceOperands().stream()
                        .map(TaskIntentResolver.DirectSourceOperand::path)
                        .toList(),
                request);
        for (var operand : binding.sourceOperands()) {
            assertEquals(
                    operand.sourceText(),
                    request.substring(
                            operand.startOffset(),
                            operand.endOffset()),
                    request);
        }
    }

    @Test
    void quotedCurrentDirectoryAndWindowsSourceOperandsRetainRawOwnership() {
        for (String request : List.of(
                "Create result.md based on \"./docs/source.md\".",
                "Create result.md based on \"docs\\source.md\".")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            var binding = TaskIntentResolver.directSourceBindings(request)
                    .getFirst();
            var operand = binding.sourceOperands().getFirst();

            assertEquals(
                    Set.of("docs/source.md"),
                    resolution.contract().sourceEvidenceTargets(),
                    request);
            assertEquals("docs/source.md", operand.path(), request);
            assertEquals(
                    operand.sourceText(),
                    request.substring(
                            operand.startOffset(),
                            operand.endOffset()),
                    request);
        }
    }

    @Test
    void optionalAdvisoryAndDisjunctiveSourceClausesCannotMintReadAuthority() {
        for (String request : List.of(
                "Create result.md. Maybe update result.md based on secret.md.",
                "Create result.md. Options: update result.md based on secret.md.",
                "Create result.md. Update result.md where needed based on secret.md.",
                "Create result.md. Update result.md only as a proposal based on secret.md.",
                "Create result.md according to a.md or b.md.",
                "Create result.md according to a.md and/or b.md.")) {
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertTrue(
                    TaskIntentResolver.directSourceBindings(request).isEmpty(),
                    request);
            assertTrue(
                    resolution.contract().sourceEvidenceTargets().isEmpty(),
                    request);
        }
    }

    @Test
    void directSourceListStopsBeforeANewActionWithoutLosingPriorSource() {
        String request =
                "Create result.md according to a.md, then update b.md.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                Set.of("a.md"),
                resolution.contract().sourceEvidenceTargets(),
                request);
        assertFalse(
                resolution.contract().sourceEvidenceTargets()
                        .contains("b.md"),
                request);
    }

    @Test
    void plainButAndThenResetCompletedNotOpenReportedAuthority() {
        String commaBut =
                "The report says update draft.md, but create result.md "
                        + "according to source.md.";
        assertEquals(
                Set.of("source.md"),
                TaskContractResolver.fromUserRequest(commaBut)
                        .sourceEvidenceTargets(),
                commaBut);

        for (String request : List.of(
                "The report says update draft.md but create result.md "
                        + "according to source.md.",
                "The report says update draft.md then create result.md "
                        + "according to source.md.",
                "The report says update draft.md and then create result.md "
                        + "according to source.md.",
                "The report says then create result.md based on source.md.")) {
            assertTrue(
                    TaskContractResolver.fromUserRequest(request)
                            .sourceEvidenceTargets()
                            .isEmpty(),
                    request);
        }
    }

    @Test
    void directSourceConnectorCannotBridgeUnclassifiedDescriptiveText() {
        for (String request : List.of(
                "Create result.md, while the existing draft is based on "
                        + "secret.md.",
                "Create result.md and discuss it based on secret.md.")) {
            assertTrue(
                    TaskIntentResolver.directSourceBindings(request).isEmpty(),
                    request);
            assertTrue(
                    TaskContractResolver.fromUserRequest(request)
                            .sourceEvidenceTargets()
                            .isEmpty(),
                    request);
        }
    }

    @Test
    void conditionalCommaThenCannotPromoteSourceAuthority() {
        for (String request : List.of(
                "Create base.md. If needed, then update result.md based on "
                        + "secret.md.",
                "Create base.md. The report says if needed, then update "
                        + "result.md based on secret.md.")) {
            assertTrue(
                    TaskIntentResolver.directSourceBindings(request).isEmpty(),
                    request);
            assertTrue(
                    TaskContractResolver.fromUserRequest(request)
                            .sourceEvidenceTargets()
                            .isEmpty(),
                    request);
        }
    }

    @Test
    void explicitMultiSourceIntroducersRetainEveryAdjacentOperand() {
        for (String request : List.of(
                "Create result.md according to both a.md and b.md.",
                "Create result.md according to the source files a.md and "
                        + "b.md.")) {
            assertEquals(
                    Set.of("a.md", "b.md"),
                    TaskContractResolver.fromUserRequest(request)
                            .sourceEvidenceTargets(),
                    request);
        }
    }

    @Test
    void caseDistinctOutputAndSourceRetainExactLinuxIdentity() {
        String request = "Create A.md based on a.md.";
        var binding = TaskIntentResolver.directSourceBindings(request)
                .getFirst();

        assertEquals(List.of("A.md"), binding.outputPaths(), request);
        assertEquals(
                List.of("a.md"),
                binding.sourceOperands().stream()
                        .map(TaskIntentResolver.DirectSourceOperand::path)
                        .toList(),
                request);
        assertEquals(
                Set.of("A.md"),
                TaskContractResolver.fromUserRequest(request)
                        .expectedTargets(),
                request);
        assertEquals(
                Set.of("a.md"),
                TaskContractResolver.fromUserRequest(request)
                        .sourceEvidenceTargets(),
                request);
    }

    @Test
    void caseDistinctReportedTailCannotInheritEarlierReadAuthority() {
        String request =
                "Read A.md and edit index.html so the report says update a.md.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                Set.of("index.html"),
                resolution.contract().expectedTargets(),
                request);
        assertEquals(
                Set.of("A.md"),
                resolution.contract().sourceEvidenceTargets(),
                request);
        assertEquals(
                TargetRole.SOURCE_EVIDENCE,
                resolution.intent().targets()
                        .find("A.md")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                resolution.intent().targets()
                        .find("a.md")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                resolution.mutationTailPlan()
                        .decision("a.md")
                        .orElseThrow()
                        .authority(),
                request);
    }

    @Test
    void slashNormalizationDoesNotCollapseCaseDistinctSourceAuthority() {
        String request =
                "Read Docs\\A.md and edit index.html so the report says "
                        + "update Docs/a.md.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                Set.of("Docs/A.md"),
                resolution.contract().sourceEvidenceTargets(),
                request);
        assertEquals(
                TargetRole.SOURCE_EVIDENCE,
                resolution.intent().targets()
                        .find("Docs/A.md")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                resolution.intent().targets()
                        .find("Docs/a.md")
                        .orElseThrow()
                        .role(),
                request);
    }

    @Test
    void directSourceOverlapRetainsRequiredPostReadMutationRole() {
        String request =
                "Update config.json based on config.json and defaults.json.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);

        assertEquals(
                Set.of("config.json"),
                resolution.contract().expectedTargets(),
                request);
        assertEquals(
                Set.of("config.json", "defaults.json"),
                resolution.contract().sourceEvidenceTargets(),
                request);
        assertEquals(
                TargetRole.MUST_MUTATE_AFTER_SOURCE_EVIDENCE,
                resolution.intent().targets()
                        .find("config.json")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                TargetRole.SOURCE_EVIDENCE,
                resolution.intent().targets()
                        .find("defaults.json")
                        .orElseThrow()
                        .role(),
                request);
    }

    @Test
    void articleLedReportedSubjectCannotGrantEmbeddedActorAuthority() {
        String request = "Edit index.html so the reviewer anticipates Bob "
                + "and you should update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        CoordinatedMutationTailPlan.TargetDecision style =
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow();

        assertEquals(Set.of("index.html"),
                resolution.contract().expectedTargets(), request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                style.authority(),
                request);
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                style.sourceText(),
                request.substring(style.startOffset(), style.endOffset()),
                request);
    }

    @Test
    void recognizedLowercaseCollaboratorCanShareDirectMutationAuthority() {
        String request = "Edit index.html so build bot and you need to "
                + "update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        CoordinatedMutationTailPlan.TargetDecision style =
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow();

        assertEquals(Set.of("index.html", "style.css"),
                resolution.contract().expectedTargets(), request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.REQUIRED,
                style.authority(),
                request);
        assertEquals(
                TargetRole.MUST_MUTATE,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                style.sourceText(),
                request.substring(style.startOffset(), style.endOffset()),
                request);
    }

    @Test
    void negatedFailureDirectiveCannotEraseEarlierTargetProhibition() {
        String request = "Edit index.html so never modify style.css; "
                + "please don't forget to update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        CoordinatedMutationTailPlan.TargetDecision style =
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow();

        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                style.authority(),
                request);
        assertEquals(Set.of("index.html"),
                resolution.contract().expectedTargets(), request);
        assertEquals(Set.of("style.css"),
                resolution.contract().forbiddenTargets(), request);
        assertEquals(
                TargetRole.FORBIDDEN,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                style.sourceText(),
                request.substring(style.startOffset(), style.endOffset()),
                request);
    }

    @Test
    void finitePostTargetProhibitionsRemainForbidden() {
        for (String tail : List.of(
                "update style.css has been forbidden.",
                "update style.css is not permitted.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            CoordinatedMutationTailPlan.TargetDecision style =
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow();

            assertEquals(Set.of("index.html"),
                    resolution.contract().expectedTargets(), request);
            assertEquals(Set.of("style.css"),
                    resolution.contract().forbiddenTargets(), request);
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    style.authority(),
                    request);
            assertEquals(
                    TargetRole.FORBIDDEN,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertEquals(
                    style.sourceText(),
                    request.substring(
                            style.startOffset(),
                            style.endOffset()),
                    request);
        }
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                authorityForStyle(
                        "Edit index.html so update style.css has been rejected."));
    }

    @Test
    void negatedFailureFrameCannotEraseEarlierGeneralProhibition() {
        String request = "Edit index.html so do not proceed and please "
                + "don't forget to update style.css.";
        assertTrue(
                CanonicalMutationClauseMatcher
                                .positiveNegatedFailureDirectiveStart(
                                        "do not proceed and please "
                                                + "don't forget to ")
                        > 0,
                request);
        assertTrue(
                CanonicalMutationClauseMatcher.hasOpenProhibitionScope(
                        "do not proceed and please don't forget to "),
                request);
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        CoordinatedMutationTailPlan.TargetDecision style =
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow();

        assertEquals(
                CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                style.authority(),
                request);
        assertEquals(Set.of("index.html"),
                resolution.contract().expectedTargets(), request);
        assertEquals(Set.of("style.css"),
                resolution.contract().forbiddenTargets(), request);
        assertEquals(
                TargetRole.FORBIDDEN,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                style.sourceText(),
                request.substring(style.startOffset(), style.endOffset()),
                request);
    }

    @Test
    void titleCasePredicateCannotMasqueradeAsCollaboratingActor() {
        String request = "Edit index.html so Alice Anticipates Bob and you "
                + "should update style.css.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        CoordinatedMutationTailPlan.TargetDecision style =
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow();

        assertEquals(Set.of("index.html"),
                resolution.contract().expectedTargets(), request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                style.authority(),
                request);
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
        assertEquals(
                style.sourceText(),
                request.substring(style.startOffset(), style.endOffset()),
                request);
    }

    @Test
    void equivalentGeneralProhibitionsRemainOpenAcrossPositiveFrames() {
        for (String prohibition : List.of(
                "do not go ahead",
                "do not take action",
                "don't carry on")) {
            String request = "Edit index.html so " + prohibition
                    + " and please don't forget to update style.css.";
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            CoordinatedMutationTailPlan.TargetDecision style =
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow();

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    style.authority(),
                    request);
            assertEquals(Set.of("index.html"),
                    resolution.contract().expectedTargets(), request);
            assertEquals(Set.of("style.css"),
                    resolution.contract().forbiddenTargets(), request);
            assertEquals(TargetRole.FORBIDDEN,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertEquals(request.indexOf(prohibition),
                    style.startOffset(), request);
            assertEquals(
                    style.sourceText(),
                    request.substring(style.startOffset(), style.endOffset()),
                    request);
            assertTrue(style.sourceText().contains(prohibition), request);
        }
    }

    @Test
    void perfectPassiveSuffixesCannotCreateMutationAuthority() {
        for (String tail : List.of(
                "update style.css has not been authorized.",
                "update style.css hasn't been authorized.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            CoordinatedMutationTailPlan.TargetDecision style =
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow();

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    style.authority(),
                    request);
            assertEquals(Set.of("index.html"),
                    resolution.contract().expectedTargets(), request);
            assertEquals(Set.of("style.css"),
                    resolution.contract().forbiddenTargets(), request);
            assertEquals(TargetRole.FORBIDDEN,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
        }

        String absentRequest =
                "Edit index.html so update style.css has never been requested.";
        TaskResolution absentRequestResolution =
                TaskContractResolver.resolveFromUserRequest(absentRequest);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                absentRequestResolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow()
                        .authority(),
                absentRequest);
        assertEquals(
                Set.of("index.html"),
                absentRequestResolution.contract().expectedTargets(),
                absentRequest);
        assertEquals(
                Set.of(),
                absentRequestResolution.contract().forbiddenTargets(),
                absentRequest);
        assertEquals(
                TargetRole.MENTIONED_ONLY,
                absentRequestResolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                absentRequest);

        String request =
                "Edit index.html so update style.css has been proposed.";
        TaskResolution resolution =
                TaskContractResolver.resolveFromUserRequest(request);
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                resolution.mutationTailPlan()
                        .decision("style.css")
                        .orElseThrow()
                        .authority(),
                request);
        assertEquals(Set.of("index.html"),
                resolution.contract().expectedTargets(), request);
        assertEquals(TargetRole.MENTIONED_ONLY,
                resolution.intent().targets()
                        .find("style.css")
                        .orElseThrow()
                        .role(),
                request);
    }

    @Test
    void equivalentPostfixAttributionsRemainNonAuthoritative() {
        for (String tail : List.of(
                "update style.css, Alice requested.",
                "update style.css, the report requires.",
                "update style.css, Alice instructed.",
                "update style.css, said Alice.",
                "update style.css \u2014 Alice said.",
                "update style.css, according to Alice.")) {
            String request = "Edit index.html so " + tail;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            CoordinatedMutationTailPlan.TargetDecision style =
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow();

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    style.authority(),
                    request);
            assertEquals(Set.of("index.html"),
                    resolution.contract().expectedTargets(), request);
            assertEquals(TargetRole.MENTIONED_ONLY,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
            assertEquals(
                    style.sourceText(),
                    request.substring(style.startOffset(), style.endOffset()),
                    request);
        }
    }

    @Test
    void restrictiveAdverbsCannotUseTheGenericModifierAuthorityPath() {
        for (String adverb : List.of("reportedly", "hypothetically")) {
            String request = "Edit index.html so " + adverb
                    + " update style.css.";
            assertEquals(
                    CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                    authorityForStyle(request),
                    request);
        }
        String conditional =
                "Edit index.html so conditionally update style.css.";
        assertEquals(
                CoordinatedMutationTailPlan.Authority.NON_AUTHORITATIVE,
                authorityForStyle(conditional),
                conditional);
    }

    @Test
    void contractedProhibitionsOverrideEarlierRequiredAuthority() {
        for (String prohibition : List.of(
                "you shouldn't update style.css.",
                "you mustn't update style.css.",
                "you aren't allowed to update style.css.")) {
            String request = "Edit index.html so update style.css, then "
                    + prohibition;
            TaskResolution resolution =
                    TaskContractResolver.resolveFromUserRequest(request);

            assertEquals(
                    CoordinatedMutationTailPlan.Authority.FORBIDDEN,
                    resolution.mutationTailPlan()
                            .decision("style.css")
                            .orElseThrow()
                            .authority(),
                    request);
            assertEquals(Set.of("index.html"),
                    resolution.contract().expectedTargets(), request);
            assertEquals(Set.of("style.css"),
                    resolution.contract().forbiddenTargets(), request);
            assertEquals(TargetRole.FORBIDDEN,
                    resolution.intent().targets()
                            .find("style.css")
                            .orElseThrow()
                            .role(),
                    request);
        }
    }

    private static CoordinatedMutationTailPlan.Authority authorityForStyle(
            String request
    ) {
        return TaskContractResolver.resolveFromUserRequest(request)
                .mutationTailPlan()
                .decision("style.css")
                .orElseThrow()
                .authority();
    }
}
