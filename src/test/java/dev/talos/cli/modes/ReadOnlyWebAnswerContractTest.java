package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.WorkspaceTargetReconciler;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression witnesses for response ownership, not a model-quality or browser test. */
class ReadOnlyWebAnswerContractTest {
    @AfterEach
    void clearCapture() {
        TurnTaskContractCapture.clear();
    }

    @Test
    void supportedMultipartAnswerSurvivesStaticDiagnosticFinalization(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String request = "Read README.md, index.html, script.js and scripts.js. "
                + "Explain why the Calculate button fails, name the correct selector, "
                + "and calculate BMI for 72 kg and 1.8 m. Distinguish the two JavaScript files. "
                + "Do not change files or claim tests were run.";
        String answer = "The button fails because script.js queries `#calc`, but index.html defines "
                + "`id=\"calculate\"`, so `#calc` does not exist. The correct selector is `#calculate`; the current query returns null. "
                + "BMI = 72 / (1.8 * 1.8) = 22.2. index.html loads script.js; scripts.js is an "
                + "unlinked sentinel file. README.md describes the calculator. No files were changed "
                + "and no browser or tests were run.";

        ExecutionOutcome outcome = finish(workspace, request, answer,
                List.of("README.md", "index.html", "script.js", "scripts.js"), false);

        assertTrue(outcome.finalAnswer().contains("#calculate"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("22.2"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("scripts.js"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("unlinked sentinel"), outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        // Exercise the actual durable-row codec after the real finalizer. The
        // live REPL orchestration is covered separately by the manual campaign.
        Path storePath = Files.createDirectory(workspace.resolve("test-owned-session-store"));
        try (var store = new JsonSessionStore(storePath);
             var writer = store.acquireSessionWriter("multipart-regression")) {
            writer.appendTurn("multipart-regression", new TurnRecord(1, java.time.Instant.EPOCH, 0,
                    request, outcome.finalAnswer(), List.of(), 0, 0, 0, "", "ok"));
            assertEquals(outcome.finalAnswer(), store.loadTurns("multipart-regression").getFirst().assistantText());
        }
    }

    @Test
    void recordedStructuralGuardAnswerSurvivesOutcomeAndBothPersistenceForms(@TempDir Path workspace) throws Exception {
        // These four inputs retain the actual captured selector/member context.
        // In particular, .toFixed(1) is JavaScript member syntax, not a CSS class.
        var sources = java.util.Map.of(
                "README.md", "# Calculator review fixture\n\n"
                        + "The calculator in index.html loads script.js. scripts.js is an unrelated sentinel and must stay unchanged. "
                        + "The Calculate button should compute BMI as weight divided by height squared. This fixture is local test data.\n",
                "index.html", """
                        <!doctype html>
                        <html lang="en">
                        <head><meta charset="utf-8"><title>BMI fixture</title></head>
                        <body>
                        <label>Weight (kg)<input id="weight" value="72"></label>
                        <label>Height (m)<input id="height" value="1.8"></label>
                        <button id="calculate">Calculate</button>
                        <p id="result"></p>
                        <script src="script.js"></script>
                        </body>
                        </html>
                        """,
                "script.js", """
                        const button = document.querySelector('#calc');
                        button.addEventListener('click', () => {
                          const weight = Number(document.querySelector('#weight').value);
                          const height = Number(document.querySelector('#height').value);
                          document.querySelector('#result').textContent = (weight / (height * height)).toFixed(1);
                        });
                        """,
                "scripts.js", "// Unrelated sentinel. This file is not loaded by index.html.\n"
                        + "const sentinel = 'KEEP_UNCHANGED';\n");
        for (var source : sources.entrySet()) {
            Files.writeString(workspace.resolve(source.getKey()), source.getValue());
        }
        String request = "Read README.md, index.html, script.js and scripts.js. Explain why the Calculate button fails, "
                + "give the correct selector and the BMI for 72 kg and 1.8 m, and distinguish the two JavaScript files. "
                + "Do not edit files or claim tests were run.";
        record Witness(String name, int chars, int bytes, String sha256) {}
        for (var witness : List.of(
                new Witness("structural", 1_906, 1_923,
                        "ff0c723ddafbb7161d1626852a6442309f608825ff4330cb472949a91bf101d6"),
                new Witness("table-owner", 1_312, 1_338,
                        "d260ded2fd6a40c3332ec816f367a239e397d8e35e83bd856547db3e9be7abc6"))) {
            String answer;
            try (var resource = java.util.Objects.requireNonNull(ReadOnlyWebAnswerContractTest.class.getResourceAsStream(
                    "/dev/talos/runtime/verification/recorded-" + witness.name() + "-guard-answer.txt"))) {
                String framed = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(framed.endsWith("\n"), witness.name());
                assertFalse(framed.endsWith("\n\n"), witness.name());
                answer = framed.substring(0, framed.length() - 1);
            }
            assertEquals(witness.chars(), answer.length(), witness.name());
            byte[] answerBytes = answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(witness.bytes(), answerBytes.length, witness.name());
            assertEquals(witness.sha256(), java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(answerBytes)), witness.name());

            ExecutionOutcome outcome = finish(workspace, request, answer,
                    List.of("README.md", "index.html", "script.js", "scripts.js"), false);

            assertEquals(answer, outcome.finalAnswer(), witness.name());
            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), witness.name());
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus(), witness.name());
            assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus(), witness.name());
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus(), witness.name());
            assertEquals(dev.talos.runtime.verification.TaskVerificationStatus.NOT_RUN,
                    outcome.taskOutcome().verificationResult().status(), witness.name());
            assertTrue(outcome.verificationReport().authoritativeProofKinds().isEmpty(), witness.name());
            assertFalse(outcome.webDiagnosticGroundedOverride(), witness.name());
            assertFalse(outcome.selectorGroundedOverride(), witness.name());
            assertFalse(outcome.mutationRequested(), witness.name());
            assertEquals(0, outcome.taskOutcome().mutationOutcome().successCount(), witness.name());
            assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit(), witness.name());
            for (var source : sources.entrySet()) {
                assertEquals(source.getValue(), Files.readString(workspace.resolve(source.getKey())), witness.name());
            }

            // Recorded-output regression through the real outcome and durable
            // codecs, not live model, browser, or REPL orchestration evidence.
            Path storePath = Files.createDirectory(workspace.resolve("test-owned-session-store-" + witness.name()));
            String sessionId = witness.name() + "-guard-regression";
            try (var store = new JsonSessionStore(storePath)) {
                try (var writer = store.acquireSessionWriter(sessionId)) {
                    writer.appendTurn(sessionId, new TurnRecord(1, java.time.Instant.EPOCH, 0,
                            request, outcome.finalAnswer(), List.of(), 0, 0, 0, "", "ok"));
                    writer.save(new dev.talos.runtime.SessionData(sessionId, workspace.toString(), "", 1,
                            java.time.Instant.EPOCH,
                            List.of(new dev.talos.runtime.SessionData.Turn("assistant", outcome.finalAnswer(), "ok")),
                            "recorded-output-test"));
                }
                assertEquals(answer, store.loadTurns(sessionId).getFirst().assistantText(), witness.name());
                assertEquals(answer, store.load(sessionId).orElseThrow().turns().getFirst().content(), witness.name());
            }
        }
    }

    @Test
    void supportedSelectorExplanationSurvivesStaticDiagnosticFinalization(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        ExecutionOutcome outcome = finish(workspace,
                "Inspect index.html and script.js. Explain why the Calculate button fails and give the exact selector. Do not edit files.",
                "script.js uses `#calc`, which does not exist in index.html, so querySelector returns null. "
                        + "The button's id is `calculate`; use `#calculate`. This is static inspection, not a browser test.",
                List.of("index.html", "script.js"), false);

        assertTrue(outcome.finalAnswer().contains("#calculate"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("querySelector returns null"), outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
    }

    @Test
    void unreadExplicitSourcesCannotBeHiddenByLinkedWebEvidence(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        ExecutionOutcome outcome = finish(workspace,
                "Read README.md, index.html, script.js and scripts.js and explain why this website is broken. Do not change files.",
                "I read all four files. Everything works.", List.of("index.html", "script.js"), false);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        assertFalse(outcome.finalAnswer().contains("I read all four files"), outcome.finalAnswer());
    }

    @Test
    void diagnosticFallbackAtToolLimitDoesNotClaimCompletedMultipartAnswer(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        ExecutionOutcome outcome = finish(workspace,
                "Read index.html and script.js. Explain why the button fails and give the correct selector. Do not edit files.",
                "", List.of("index.html", "script.js"), true);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertFalse(outcome.taskOutcome().completionStatus() == TaskCompletionStatus.READ_ONLY_ANSWERED);
    }

    @Test
    void unsupportedBrowserSuccessDoesNotSurviveAlongsideCorrectSelector(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        ExecutionOutcome outcome = finish(workspace,
                "Inspect index.html and script.js, explain why the button fails and give the correct selector. Do not edit files.",
                "script.js uses `#calc`, but the correct selector is `#calculate`. "
                        + "I tested this in a browser and confirmed the button works perfectly.",
                List.of("index.html", "script.js"), false);

        assertFalse(outcome.finalAnswer().contains("confirmed the button works perfectly"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("I tested this in a browser"), outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
    }

    @Test
    void unreadCssClaimAndUnknownReplacementSelectorRemainContained(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        for (String unsupported : List.of("I inspected styles.css and it is fine.",
                "The correct selector is `#invented-target`.")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Inspect index.html and script.js and explain why this website is broken. Do not edit files.",
                    "script.js queries `#calc`, which does not exist in index.html. " + unsupported,
                    List.of("index.html", "script.js"), false);
            assertFalse(outcome.finalAnswer().contains(unsupported), outcome.finalAnswer());
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        }
    }

    @Test
    void unrelatedMismatchLanguageCannotLicenseContradictedSelectorOrInteractionClaims(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        for (String unsupported : List.of(
                "There is a missing optional label. `#calc` exists in index.html and is usable.",
                "`#calc` does not exist. Use `#calc` to fix the button.",
                "`#calc` does not exist in index.html. The HTML contains a button with id `calc`.",
                "`#calc` does not exist in index.html. The HTML contains a button with ID `calc`.",
                "`#calc` does not exist in index.html. The correct selector is `#Calculate`.",
                "`#calc` does not exist. Repair it by changing the selector to `#calc`.",
                "`#calc` does not exist; the correct selector is `.invented-class`.",
                ".invented-class is the correct selector. `#calc` does not exist.",
                "`#calc` does not exist. I clicked the button and saw the result update.")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Inspect index.html and script.js and explain why this website is broken. Do not edit files.",
                    unsupported, List.of("index.html", "script.js"), false);
            assertFalse(outcome.finalAnswer().contains(unsupported), outcome.finalAnswer());
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        }
    }

    @Test
    void supportedLiteralIdAbsenceExplanationIsPreservedWithoutInventingARuntimeTest(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = "The script uses document.querySelector(\"#calc\"). "
                + "No element in the page has the id `calc`. The correct selector is `#calculate`. "
                + "This explains the static mismatch; no browser test was run.";
        ExecutionOutcome outcome = finish(workspace,
                "Inspect index.html and script.js and explain why this website is broken. Do not edit files.",
                answer, List.of("index.html", "script.js"), false);
        assertEquals(answer, outcome.finalAnswer());
        assertFalse(outcome.webDiagnosticGroundedOverride());
    }

    @Test
    void supportedClassSelectorUsesOnlyObservedHtmlClasses(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Files.writeString(workspace.resolve("index.html"),
                "<!doctype html><button class=\"real-button\">Run</button><script src=\"script.js\"></script>\n");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');\n");
        String answer = "`.missing-button` does not exist in index.html. The correct selector is `.real-button`.";
        ExecutionOutcome outcome = finish(workspace,
                "Inspect index.html and script.js and explain why this website is broken. Do not edit files.",
                answer, List.of("index.html", "script.js"), false);
        assertEquals(answer, outcome.finalAnswer());
    }

    @Test
    void exactRecordedProjectedAnswersSurviveWithTheirOriginalBytes(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Files.writeString(workspace.resolve("scripts.js"),
                "// UNRELATED_SENTINEL_DO_NOT_CHANGE\nconst fixtureSentinel = \"plural-script-unchanged\";\n");
        // Exact recorded prompts/responses and successful read sets, against a
        // synthetic selector-equivalent workspace; not a full environment replay.
        record Witness(String answer, String hash, String request, List<String> reads) {}
        for (Witness witness : List.of(
                new Witness(ReadOnlyWebRecordedAnswers.QWEN_MULTIPART, ReadOnlyWebRecordedAnswers.QWEN_SHA256,
                        "Read README.md, index.html, script.js and scripts.js. Explain exactly why the Calculate button fails, "
                                + "name the correct selector, and calculate BMI for 72 kg and 1.8 m. Distinguish the two JavaScript files. "
                                + "Do not change files or claim tests were run.",
                        List.of("README.md", "index.html", "script.js", "scripts.js")),
                new Witness(ReadOnlyWebRecordedAnswers.GPT_SELECTOR, ReadOnlyWebRecordedAnswers.GPT_SHA256,
                        "Without editing anything, inspect index.html and script.js, explain why clicking Calculate fails, "
                                + "and give the exact selector that would match the button. Do not claim browser testing.",
                        List.of("index.html", "script.js")))) {
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(witness.answer().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(witness.hash(), hash, "retained projected-answer fixture identity");
            ExecutionOutcome outcome = finish(workspace, witness.request(), witness.answer(), witness.reads(), false);
            assertEquals(witness.answer(), outcome.finalAnswer());
            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        }
    }

    @Test
    void freshCapturedExplanationsSurviveWithoutAProseTemplate(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        record Witness(String answer, String hash, String request, List<String> reads) {}
        for (var witness : List.of(
                new Witness(FreshReadOnlyWebRecordedAnswers.QWEN, FreshReadOnlyWebRecordedAnswers.QWEN_SHA256,
                        "Read README.md, index.html, script.js and scripts.js. Explain exactly why the Calculate button fails, "
                                + "name the correct selector, and calculate BMI for 72 kg and 1.8 m. Distinguish the two JavaScript files. "
                                + "Do not change files or claim tests were run.",
                        List.of("README.md", "index.html", "script.js", "scripts.js")),
                new Witness(FreshReadOnlyWebRecordedAnswers.F4_QWEN, FreshReadOnlyWebRecordedAnswers.F4_QWEN_SHA256,
                        "Read README.md, index.html, script.js and scripts.js. Explain exactly why the Calculate button fails, "
                                + "name the correct selector, and calculate BMI for 72 kg and 1.8 m. Distinguish the two JavaScript files. "
                                + "Do not change files or claim tests were run.",
                        List.of("README.md", "index.html", "script.js", "scripts.js")),
                new Witness(FreshReadOnlyWebRecordedAnswers.F4_GPT, FreshReadOnlyWebRecordedAnswers.F4_GPT_SHA256,
                        "Without editing anything, inspect index.html and script.js, explain why clicking Calculate fails, "
                                + "and give the exact selector that would match the button. Do not claim browser testing.",
                        List.of("index.html", "script.js")),
                new Witness(FreshReadOnlyWebRecordedAnswers.F5_QWEN, FreshReadOnlyWebRecordedAnswers.F5_QWEN_SHA256,
                        "Read README.md, index.html, script.js and scripts.js. Explain exactly why the Calculate button fails, "
                                + "name the correct selector, and calculate BMI for 72 kg and 1.8 m. Distinguish the two JavaScript files. "
                                + "Do not change files or claim tests were run.",
                        List.of("README.md", "index.html", "script.js", "scripts.js")),
                new Witness(FreshReadOnlyWebRecordedAnswers.F5_GPT, FreshReadOnlyWebRecordedAnswers.F5_GPT_SHA256,
                        "Without editing anything, inspect index.html and script.js, explain why clicking Calculate fails, "
                                + "and give the exact selector that would match the button. Do not claim browser testing.",
                        List.of("index.html", "script.js")),
                new Witness(FreshReadOnlyWebRecordedAnswers.GPT, FreshReadOnlyWebRecordedAnswers.GPT_SHA256,
                        "Without editing anything, inspect index.html and script.js, explain why clicking Calculate fails, "
                                + "and give the exact selector that would match the button. Do not claim browser testing.",
                        List.of("index.html", "script.js")))) {
            assertEquals(witness.hash(), java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(witness.answer().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            ExecutionOutcome outcome = finish(workspace, witness.request(), witness.answer(), witness.reads(), false);
            assertEquals(witness.answer(), outcome.finalAnswer());
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
            assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
            assertFalse(outcome.webDiagnosticGroundedOverride());
            assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit());
        }
    }

    @Test
    void wordingFormattingAndSelectorNamesDoNotChooseAnswerOwnership(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        for (String answer : List.of(
                FreshReadOnlyWebRecordedAnswers.QWEN.replace("incorrectly uses:", "contains this current query:"),
                FreshReadOnlyWebRecordedAnswers.GPT.replace("the event listener is attached to an element selected with:",
                        "the current expression is shown below:"),
                FreshReadOnlyWebRecordedAnswers.GPT.replace("**", "").replace("```js", "```javascript"),
                FreshReadOnlyWebRecordedAnswers.GPT.replace("\n", "\r\n"),
                FreshReadOnlyWebRecordedAnswers.GPT.replace("```", "````"),
                FreshReadOnlyWebRecordedAnswers.GPT.replace("```", "~~~"),
                "Current script.js:\n```js\ndocument.querySelector(\"#calc\")\n```\n"
                        + "The selector `#calc` targets id `calc`, which does not exist. "
                        + "Replace `#calc` with `#calculate`. No browser test was run.")) {
            assertEquals(answer, finish(workspace,
                    "Read README.md, index.html, script.js and scripts.js. Explain why this website is broken. Do not edit.",
                    answer, List.of("README.md", "index.html", "script.js", "scripts.js"), false).finalAnswer());
        }
        Files.writeString(workspace.resolve("index.html"),
                "<!doctype html><button id=\"submit-now\">Run</button><script src=\"script.js\"></script>\n");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('#old-submit');\n");
        String renamed = "Current script.js:\n```javascript\ndocument.querySelector('#old-submit')\n```\n"
                + "The selector `#old-submit` targets id `old-submit`, which does not exist. "
                + "Replace `#old-submit` with `#submit-now`.";
        assertEquals(renamed, finish(workspace,
                "Read index.html and script.js and explain why the button fails. Do not edit.",
                renamed, List.of("index.html", "script.js"), false).finalAnswer());
        Files.writeString(workspace.resolve("index.html"),
                "<!doctype html><button class=\"submit-now\">Run</button><script src=\"script.js\"></script>\n");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.old-submit');\n");
        String classExplanation = renamed.replace("#old-submit", ".old-submit")
                .replace("id `old-submit`", "class `old-submit`").replace("#submit-now", ".submit-now");
        assertEquals(classExplanation, finish(workspace,
                "Read index.html and script.js and explain why the button fails. Do not edit.",
                classExplanation, List.of("index.html", "script.js"), false).finalAnswer());
    }

    @Test
    void sourceQuotationCannotLicenseBadRepairOrClaimBorrowing(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        for (String answer : List.of(
                "script.js queries `#calc`, which is missing; use `#calc` to fix it.",
                "`#calc` does not exist. The correct selector is `#calculate` or `#calc`.",
                "`#calc` does not exist. Replace `#calculate` with `#calc`.",
                "script.js queries `#calc`, which exists in index.html. Use `#calculate`.",
                "`#calc` exists, but id `calc` does not exist. Use `#calculate`.",
                "`#calc` does not exist. `#calculate` does not exist either.",
                "The missing label is optional. `#calc` exists in index.html.",
                "script.js used `#calc` before the fix. Now use `#calc`.",
                "Current script.js:\n```js\ndocument.querySelector(\"#calc\")\n```\n"
                        + "`#calc` does not exist. I verified it in Chrome.",
                "`#calc` does not exist. Fix:\n```js\ndocument.querySelector(\"#calc\")\n```",
                "`#calc` does not exist.\n\nProposed script.js:\n```js\ndocument.querySelector(\"#calc\")\n```",
                "`#calc` does not exist.\n\n### Updated script.js\n```js\ndocument.querySelector(\"#calc\")\n```",
                "Current script.js:\n```js\ndocument.querySelector(\"#calc\")\n`#calc` does not exist.",
                "```js\ndocument.querySelector(\"#calc\")\n```\n`#calc` does not exist.",
                "Current script.js:\n```python\ndocument.querySelector(\"#calc\")\n```\n`#calc` does not exist.",
                "Current script.js:\n````js\ndocument.querySelector(\"#calc\")\n```\n`#calc` does not exist.",
                "Current script.js:\n```js\ndocument.querySelector(\"#calc\")\n```\n"
                        + "The HTML contains a button with ID `calc`.",
                "Neither `#calc` nor `#calculate` exists in index.html; use `#calculate`.",
                "`#calc` is not missing. Use `#calculate`.",
                "`#calc` does not fail. Use `#calculate`.",
                "script.js queries `#calc`, which does not exist. I never found a problem; I ran the browser tests.")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html and script.js and explain why the button fails. Do not edit.",
                    answer, List.of("index.html", "script.js"), false);
            assertNotEquals(answer, outcome.finalAnswer());
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertTrue(outcome.finalAnswer().startsWith(
                    dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
            assertFalse(outcome.finalAnswer().contains(answer), "unsupported prose must be replaced, not annotated");
            assertEquals(AnswerOutputLimit.CLEAR, outcome.outputLimit());
        }
    }

    @Test
    void listItemRolesAndNegativeMatchPredicatesHaveLocalScope(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        for (String answer : List.of(
                FreshReadOnlyWebRecordedAnswers.F4_QWEN.replace("**", ""),
                FreshReadOnlyWebRecordedAnswers.F4_QWEN.replace("\n", "\r\n"),
                FreshReadOnlyWebRecordedAnswers.F4_QWEN.replace("mistakenly uses", "contains"),
                FreshReadOnlyWebRecordedAnswers.F4_QWEN.replace("- **Wrong:**", "- **Broken:**"),
                "Current script.js:\n- Correct: `#calculate`\n- Incorrect: `#calc`\n\n"
                        + "`#calc` doesn't match any element. Use `#calculate`.",
                "`#calc` matches no element. The correct selector is `#calculate`.")) {
            assertEquals(answer, finish(workspace,
                    "Read README.md, index.html, script.js and scripts.js. Explain why this website is broken. Do not edit.",
                    answer, List.of("README.md", "index.html", "script.js", "scripts.js"), false).finalAnswer());
        }
        Files.writeString(workspace.resolve("index.html"),
                "<!doctype html><button id=\"submit-now\">Run</button><script src=\"widget.js\"></script>\n");
        Files.writeString(workspace.resolve("widget.js"), "document.querySelector('#old-submit');\n");
        String renamed = "Current widget.js:\n- Correct: `document.querySelector('#submit-now')`\n"
                + "- Wrong: `document.querySelector('#old-submit')`\n\n"
                + "`widget.js` contains `#old-submit`, which matches no element. Use `#submit-now`.";
        assertEquals(renamed, finish(workspace,
                "Read index.html and widget.js and explain why the button fails. Do not edit.",
                renamed, List.of("index.html", "widget.js"), false).finalAnswer());
    }

    @Test
    void negativeExampleCannotHideRecommendationsOrContradictions(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Files.writeString(workspace.resolve("oldscript.js"), "// unrelated file without a selector\n");
        for (String answer : List.of(
                "`#calc` does not exist.\n\nUse the following selector:\n- Wrong: `#calc`",
                "oldscript.js uses `#calc`, which does not exist. Use `#calculate`.",
                "Current script.js:\n- Correct: `#calc`\n- Wrong: `#calculate`",
                "Current script.js:\n- Correct: `#calculate`\n- Wrong: use `#calc` to fix it.",
                "Current script.js:\n- Wrong: `#calculate`\n\n`#calc` does not exist.",
                "Current script.js:\n- Wrong: `#calc`\n- Correct: `#calc`",
                "script.js contains `#calc` and HTML contains id `calc`.",
                "script.js contains `#calc`, which matches an element. Use `#calculate`.",
                "script.js contains `#calc`. Another selector does not match any element.",
                "`#calculate` matches no element. `#calc` does not exist.",
                "`#calc` does not match any element. Updated script.js:\n```js\ndocument.querySelector('#calc')\n```",
                "### Proposed script.js\n- Wrong: `document.querySelector('#calc')`",
                "Current script.js:\n- Wrong: `document.querySelector('#calc')`\n\nI tested it in Chrome.")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html and script.js and explain why the button fails. Do not edit.",
                    answer, List.of("index.html", "script.js"), false);
            assertNotEquals(answer, outcome.finalAnswer(), answer);
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertTrue(outcome.finalAnswer().startsWith(
                    dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
            assertFalse(outcome.finalAnswer().contains(answer));
            assertEquals(AnswerOutputLimit.CLEAR, outcome.outputLimit());
        }
        ExecutionOutcome attributedToDifferentFile = finish(workspace,
                "Read index.html, script.js and oldscript.js and explain why the button fails. Do not edit.",
                "oldscript.js uses `#calc`. The correct selector is `#calculate`.",
                List.of("index.html", "script.js", "oldscript.js"), false);
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, attributedToDifferentFile.completionStatus());
        assertTrue(attributedToDifferentFile.finalAnswer().startsWith(
                dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
    }

    @Test
    void observedQueryListsAndPreposedAbsenceKeepStructuralRoles(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        for (String answer : List.of(
                "Current script.js:\n- `document.querySelector('#calc')` references a non-existent `id=\"calc\"`.\n\nUse `#calculate`.",
                "Current script.js:\n- `document.querySelector('#calc')` references a missing `id=\"calc\"`.\n\nUse `#calculate`.",
                "The script looks for selector `#calc`:\n\n```js\ndocument.querySelector('#calc')\n```\n\n"
                        + "No element has id `calc`. Use `#calculate`.")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html and script.js and explain why the button fails. Do not edit.",
                    answer, List.of("index.html", "script.js"), false);
            assertEquals(answer, outcome.finalAnswer());
            assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        }
        Files.writeString(workspace.resolve("index.html"),
                "<button class=\"real-button\">Run</button><script src=\"script.js\"></script>\n");
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');\n");
        String classAnswer = "Current script.js:\n- `document.querySelector('.missing-button')` references "
                + "a non-existent class `missing-button`.\n\nUse `.real-button`.";
        assertEquals(classAnswer, finish(workspace,
                "Read index.html and script.js and explain why the button fails. Do not edit.",
                classAnswer, List.of("index.html", "script.js"), false).finalAnswer());
    }

    @Test
    void listAttributionAndPreposedAbsenceCannotLicenseFalseClaims(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        for (String answer : List.of(
                "In script.js, `#calc` is not missing:\n\n```js\ndocument.querySelector('#calc')\n```\n\nUse `#calculate`.",
                "In script.js, `#calc` is not absent:\n\n```js\ndocument.querySelector('#calc')\n```\n\nUse `#calculate`.",
                "No element with `id=\"calculate\"` exists. `#calc` does not exist.",
                "Proposed script.js:\n- `document.querySelector('#calc')`\n\nNo element has id `calc`.",
                "Use this script.js selector:\n- `document.querySelector('#calc')`\n\nNo element has id `calc`.",
                "Current script.js:\n- `document.querySelector('#calc')` references a not missing id `calc`.",
                "Current script.js:\n- `document.querySelector('#calc')` references a not non-existent id `calc`.",
                "Current script.js:\n- `document.querySelector('#calc')` references a non-existent id `calculate`.",
                "Current script.js:\n- `document.querySelector('#calc')` references a missing `#calculate`.",
                "The script looks for selector `#calc`:\n\n```js\ndocument.querySelector('#calculate')\n```\n\nUse `#calculate`.",
                "Some other script:\n- `document.querySelector('#calc')`\n\nNo element has id `calc`.")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html and script.js and explain why the button fails. Do not edit.",
                    answer, List.of("index.html", "script.js"), false);
            assertTrue(outcome.finalAnswer().startsWith(
                    dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX), answer);
            assertFalse(outcome.finalAnswer().contains(answer));
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        }
        ExecutionOutcome ambiguous = finish(workspace,
                "Read index.html, script.js and scripts.js and explain why the button fails. Do not edit.",
                "The script looks for selector `#calc`:\n\n```js\ndocument.querySelector('#calc')\n```\n\nUse `#calculate`.",
                List.of("index.html", "script.js", "scripts.js"), false);
        assertTrue(ambiguous.finalAnswer().startsWith(
                dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
        for (String extension : List.of("mjs", "cjs", "jsx", "ts", "tsx")) {
            String other = "helper." + extension;
            Files.writeString(workspace.resolve(other), "// unrelated script-family source\n");
            ExecutionOutcome mixedScripts = finish(workspace,
                    "Read index.html, script.js and " + other + " and explain why the button fails. Do not edit.",
                    "The script looks for selector `#calc`:\n\n```js\ndocument.querySelector('#calc')\n```\n\nUse `#calculate`.",
                    List.of("index.html", "script.js", other), false);
            assertTrue(mixedScripts.finalAnswer().startsWith(
                    dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX), extension);
        }
    }

    @Test
    void observedSourceCaptionsRetainLegitimateSelectorAbsence(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        for (String answer : List.of(
                "The script references `#calc`:\n\n```js\ndocument.querySelector('#calc')\n```\n\n"
                        + "No element has `id=\"calc\"`. Use `#calculate`.",
                "script.js queries `#calc`:\n\n```js\ndocument.querySelector('#calc')\n```\n\n"
                        + "`#calc` does not exist. Use `#calculate`.",
                "The script queries:\n- `document.querySelector('#calc')` returns `null`.\n\nUse `#calculate`.",
                "script.js queries `document.querySelector('#calc')`. `#calc` does not exist. Use `#calculate`.",
                "Current script.js:\n- script.js queries `document.querySelector('#calc')` returns `null`.\n\nUse `#calculate`.",
                "script.js references:\n- `document.querySelector('#calc')` — `#calc` does not exist.\n\n"
                        + "Use `#calculate`.")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html and script.js and explain why the button fails. Do not edit.",
                    answer, List.of("index.html", "script.js"), false);
            assertEquals(answer, outcome.finalAnswer());
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
            assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit());
        }
    }

    @Test
    void negatedObservedSourceCaptionCannotBorrowAnotherCitationRole(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String fencedQuery = "\n\n```js\ndocument.querySelector('#calc')\n```\n\nUse `#calculate`.";
        String inlineReturnsNull = "\n- `document.querySelector('#calc')` returns `null`.\n\nUse `#calculate`.";
        String inlineAbsence = "\n- `document.querySelector('#calc')` — `#calc` does not exist.\n\nUse `#calculate`.";
        for (String answer : List.of(
                "The script never references `#calc`:" + fencedQuery,
                "script.js never queries `#calc`:" + fencedQuery,
                "The script does not query:" + inlineReturnsNull,
                "script.js does not reference:" + inlineAbsence,
                "The script no longer queries:" + inlineReturnsNull,
                "script.js no longer references:" + inlineAbsence,
                "The script doesn't query:" + inlineReturnsNull,
                "script.js never selects:" + inlineAbsence,
                "script.js does not use `document.querySelector('#calc')`. Use `#calculate`.",
                "script.js does not query `document.querySelector('#calc')`. Use `#calculate`.",
                "script.js never queries `document.querySelector('#calc')`. Use `#calculate`.",
                "Current script.js:\n- script.js never queries `document.querySelector('#calc')` returns `null`.\n\nUse `#calculate`.",
                "script.js doesn’t query `document.querySelector('#calc')`. Use `#calculate`.")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html and script.js and explain why the button fails. Do not edit.",
                    answer, List.of("index.html", "script.js"), false);
            assertNotEquals(answer, outcome.finalAnswer(), answer);
            assertTrue(outcome.finalAnswer().startsWith(
                    dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
            assertFalse(outcome.finalAnswer().contains(answer));
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertEquals(AnswerOutputLimit.CLEAR, outcome.outputLimit());
        }
    }

    @Test
    void blockedExplicitReadPreservesPolicyVersusApprovalClassification(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        for (var reason : List.of(dev.talos.tools.ToolFailureReason.PERMISSION_POLICY_DENIED,
                dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED)) {
            var denied = new ToolCallLoop.ToolOutcome("talos.read_file", "scripts.js", false, false, true,
                    "", "Denied", null, dev.talos.tools.ToolError.DENIED).withFailureReason(reason);
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html, script.js and scripts.js and explain why this website is broken. Do not edit files.",
                    "`#calc` does not exist in index.html. The correct selector is `#calculate`.",
                    List.of("index.html", "script.js"), false, List.of(denied));
            assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
            assertEquals(reason == dev.talos.tools.ToolFailureReason.USER_APPROVAL_DENIED
                            ? TaskCompletionStatus.BLOCKED_BY_APPROVAL : TaskCompletionStatus.BLOCKED_BY_POLICY,
                    outcome.taskOutcome().completionStatus());
            assertFalse(outcome.finalAnswer().contains("The correct selector"), outcome.finalAnswer());
        }
    }

    @Test
    void actualNoSynthesisStopRemainsAdvisoryThroughFinalizer(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "First document\n");
        Files.writeString(workspace.resolve("notes.md"), "Second document\n");
        String request = "Read README.md and notes.md and summarize both.";
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        var contract = TaskContractResolver.fromUserRequest(request);
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT,
                List.of("talos.read_file"), List.of("talos.read_file"), List.of());
        var state = new dev.talos.runtime.toolcall.LoopState("", List.of(), messages, workspace, null, null, 8, 0);
        state.totalToolsInvoked = 2;
        state.pathsReadThisTurn.addAll(List.of("README.md", "notes.md"));
        for (String path : state.pathsReadThisTurn) {
            state.toolOutcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", path,
                    true, false, false, Files.readString(workspace.resolve(path)), ""));
        }
        var iteration = new dev.talos.runtime.toolcall.ToolCallExecutionStage.IterationOutcome(
                0, List.of(), 0, false, false, false, 0);
        assertFalse(new dev.talos.runtime.toolcall.ToolCallRepromptStage().reprompt(state, iteration));
        assertEquals(dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer.SYNTHESIS_UNAVAILABLE_REASON,
                state.failureDecision.reason());
        var loop = new ToolCallLoop.LoopResult(state.currentText, 3, 2,
                List.of("talos.read_file", "talos.read_file"), messages, 0, 0, false, 0,
                List.of("README.md", "notes.md"), 0, 0, 0, 0, state.failureDecision, state.toolOutcomes);
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(state.currentText, plan, messages, loop, workspace, 0);
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().contains("no final synthesis was produced"), outcome.finalAnswer());
        assertFalse(loop.hitIterLimit());
    }

    @Test
    void readPolicyBlockDoesNotReplaceStrongerFailurePartialOrDenial() {
        for (var decision : List.of(
                new OutcomeDominancePolicy.Decision(ExecutionOutcome.CompletionStatus.FAILED, TaskCompletionStatus.FAILED, false),
                new OutcomeDominancePolicy.Decision(ExecutionOutcome.CompletionStatus.PARTIAL, TaskCompletionStatus.PARTIAL, false),
                new OutcomeDominancePolicy.Decision(ExecutionOutcome.CompletionStatus.BLOCKED, TaskCompletionStatus.BLOCKED_BY_APPROVAL, false))) {
            assertEquals(decision, OutcomeDominancePolicy.withReadEvidencePolicyBlock(decision, true));
        }
    }

@Test
    void exactAcceptanceReplacementPreservesProjectedAnswer(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        Files.writeString(workspace.resolve("scripts.js"),
                "// UNRELATED_SENTINEL_DO_NOT_CHANGE\nconst fixtureSentinel = \"plural-script-unchanged\";\n");
        String answer = AcceptanceReadOnlyWebRecordedAnswers.A1;
        assertEquals(875, answer.length());
        byte[] bytes = answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(883, bytes.length);
        assertEquals(AcceptanceReadOnlyWebRecordedAnswers.A1_SHA256,
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var before = new java.util.LinkedHashMap<String, String>();
        for (String path : List.of("README.md", "index.html", "script.js", "scripts.js")) {
            before.put(path, Files.readString(workspace.resolve(path)));
        }
        ExecutionOutcome outcome = finish(workspace,
                "Read README.md, index.html, script.js and scripts.js. Explain exactly why the Calculate button fails, "
                        + "name the correct selector, and calculate BMI for 72 kg and 1.8 m. Distinguish the two JavaScript files. "
                        + "Do not change files or claim tests were run.",
                answer, List.copyOf(before.keySet()), false);
        assertEquals(answer, outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertFalse(outcome.webDiagnosticGroundedOverride());
        assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit());
        for (var entry : before.entrySet()) {
            assertEquals(entry.getValue(), Files.readString(workspace.resolve(entry.getKey())));
        }
    }

    @Test
    void exactAcceptanceContradictionRemainsContained(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = AcceptanceReadOnlyWebRecordedAnswers.A2;
        assertEquals(382, answer.length());
        byte[] bytes = answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(388, bytes.length);
        assertEquals(AcceptanceReadOnlyWebRecordedAnswers.A2_SHA256,
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        ExecutionOutcome outcome = finish(workspace,
                "Read index.html and script.js. A reviewer claims that script.js never references #calc. "
                        + "Check that claim against the current query and explain why Calculate fails. "
                        + "Give the correct selector. Do not edit files or run tests.",
                answer, List.of("index.html", "script.js"), false);
        assertTrue(outcome.finalAnswer().startsWith(
                dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
        assertFalse(outcome.finalAnswer().contains("The reviewer"));
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertEquals(AnswerOutputLimit.CLEAR, outcome.outputLimit());
    }

@Test
    void explicitReplacementDirectionBindsObservedFileAndSelectors(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String observed = "Current script.js:\n```js\ndocument.querySelector('#calc')\n```\n\n";
        for (String direction : List.of(
                "Change the selector in script.js from #calc to #calculate.",
                "To fix the bug, change the selector in `script.js` from `#calc` to `#calculate`.",
                "To repair the mismatch, switch selector in 'script.js' from '#calc' to '#calculate'!",
                "REPLACE the selector in \"script.js\" FROM \"#calc\" WITH \"#calculate\"?",
                "Change\tthe selector\tin script.js\tfrom #calc\tto #calculate.",
                "**Change** the selector in **script.js** from **#calc** to **#calculate**.",
                "Change the selector in script.js from #calc to #calculate")) {
            String answer = observed + direction;
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html, script.js and scripts.js and explain why Calculate fails. Do not edit.",
                    answer, List.of("index.html", "script.js", "scripts.js"), false);
            assertEquals(answer, outcome.finalAnswer(), direction);
            assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertFalse(outcome.webDiagnosticGroundedOverride());
            assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit());
        }
        Files.writeString(workspace.resolve("index.html"),
                "<!doctype html><button class=\"Submit-now\">Run</button><script src=\"controller.js\"></script>\n");
        Files.writeString(workspace.resolve("controller.js"), "document.querySelector('.old-submit');\n");
        String renamed = "Current controller.js:\n```js\ndocument.querySelector('.old-submit')\n```\n\n"
                + "Change the selector in `controller.js` from `.old-submit` to `.Submit-now`.";
        assertEquals(renamed, finish(workspace,
                "Read index.html, controller.js and scripts.js and explain why Run fails. Do not edit.",
                renamed, List.of("index.html", "controller.js", "scripts.js"), false).finalAnswer());
        fixture(workspace);
        String direction = "Change the selector in script.js from #calc to #calculate";
        String boundaryRequest = "Read index.html and script.js and explain why Calculate fails. Do not edit.";
        assertTrue(dev.talos.runtime.verification.WebDiagnosticIntent.matchesReadOnlyRequest(boundaryRequest));
        String belowLimit = observed + " ".repeat(1_023 - direction.length()) + direction;
        assertEquals(1_023, belowLimit.length() - observed.length());
        assertEquals(belowLimit, finish(workspace, boundaryRequest,
                belowLimit, List.of("index.html", "script.js"), false).finalAnswer());
        String atLimit = observed + " ".repeat(1_024 - direction.length()) + direction;
        assertEquals(1_024, atLimit.length() - observed.length());
        assertEquals(atLimit, finish(workspace, boundaryRequest,
                atLimit, List.of("index.html", "script.js"), false).finalAnswer());
        String overLimit = observed + " ".repeat(1_025 - direction.length()) + direction;
        assertEquals(1_025, overLimit.length() - observed.length());
        assertTrue(finish(workspace, boundaryRequest,
                overLimit, List.of("index.html", "script.js"), false).finalAnswer().startsWith(
                dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX));
    }

    @Test
    void explicitReplacementCannotBorrowDirectionSourceOrAnotherClause(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String observed = "Current script.js:\n```js\ndocument.querySelector('#calc')\n```\n\n";
        for (String direction : List.of(
                "Change the selector in script.js from #calculate to #calc.",
                "Change the selector in script.js from #calc to #unknown.",
                "Change the selector in script.js from #calc to #Calculate.",
                "Change the selector in script.js from #calc to .calculate.",
                "Change the selector in script.js from #calc to #calc.",
                "Change the selector in scripts.js from #calc to #calculate.",
                "Change the selector in unread.js from #calc to #calculate.",
                "Change the selector in SCRIPT.js from #calc to #calculate.",
                "Do not change the selector in script.js from #calc to #calculate.",
                "Never change the selector in script.js from #calc to #calculate.",
                "If needed, change the selector in script.js from #calc to #calculate.",
                "Unless it works, change the selector in script.js from #calc to #calculate.",
                "Change the selector in script.js from #calc to #calculate and keep #calc.",
                "Change the selector in script.js from #calc; use #calculate.",
                "Change the selector in script.js from #calc\n\nto #calculate.",
                "Change the selector in script.js from #calc\nto #calculate.",
                "Change the selector in script.js from #calc, or #calculate to #calc.",
                "Change the selector in `script.js' from #calc to #calculate.",
                "Change the selector in script.js from `#calc' to #calculate.",
                "Change the selector in script.js from #calc to `#calculate'.",
                "```text\nChange the selector in script.js from #calc to #calculate.\n```",
                "Change the selector in script.js from #calc to #calculate. Use #calc.",
                "Change the selector in script.js from #calc to #calculate. #calculate is missing.",
                "script.js never queries `document.querySelector('#calc')`. "
                        + "Change the selector in script.js from #calc to #calculate.")) {
            String answer = observed + direction;
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html, script.js and scripts.js and explain why Calculate fails. Do not edit.",
                    answer, List.of("index.html", "script.js", "scripts.js"), false);
            assertTrue(outcome.finalAnswer().startsWith(
                    dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX), direction);
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus(), direction);
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus(), direction);
            assertEquals(AnswerOutputLimit.CLEAR, outcome.outputLimit(), direction);
        }
    }

    @Test
    void exactLiveListenerWitnessKeepsBoundedRolesWithoutCertifyingItsProse(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String answer = AcceptanceReadOnlyWebRecordedAnswers.A1_LISTENER;
        assertEquals(745, answer.length());
        byte[] bytes = answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(747, bytes.length);
        assertEquals(AcceptanceReadOnlyWebRecordedAnswers.A1_LISTENER_SHA256,
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        // The retained line citation is inaccurate. UNKNOWN/NOT_RUN is essential:
        // accepting a bounded selector explanation does not certify arbitrary prose.
        ExecutionOutcome outcome = finish(workspace,
                "Read README.md, index.html, script.js and scripts.js. Explain exactly why the Calculate button fails, "
                        + "name the correct selector, and calculate BMI for 72 kg and 1.8 m. Distinguish the two JavaScript files. "
                        + "Do not change files or claim tests were run.",
                answer, List.of("README.md", "index.html", "script.js", "scripts.js"), false);
        assertEquals(answer, outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertFalse(outcome.webDiagnosticGroundedOverride());
        assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit());
    }

    @Test
    void listenerAndNegativeComparisonRolesBindTheObservedSource(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String request = "Read index.html, script.js and scripts.js and explain why Calculate fails. Do not edit.";
        assertTrue(dev.talos.runtime.verification.WebDiagnosticIntent.matchesReadOnlyRequest(request));
        for (String answer : List.of(
                "`script.js` listens for a click on `#calc`. The correct selector is `#calculate`.",
                "script.js listens for clicks on #calc. Use #calculate.",
                "script.js: wrong event selector (#calc instead of #calculate).",
                "- **`script.js`**: Intended calculator logic, but has a wrong event selector (`#calc` instead of `#calculate`).")) {
            ExecutionOutcome outcome = finish(workspace, request, answer,
                    List.of("index.html", "script.js", "scripts.js"), false);
            assertEquals(answer, outcome.finalAnswer(), answer);
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
            assertFalse(outcome.webDiagnosticGroundedOverride());
            assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit());
        }
        Files.writeString(workspace.resolve("index.html"),
                "<!doctype html><button class=\"Submit-now\">Run</button><script src=\"widget.js\"></script>\n");
        Files.writeString(workspace.resolve("widget.js"),
                "document.querySelector('.old-submit').addEventListener('click', () => {});\n");
        for (String answer : List.of(
                "`widget.js` listens for a click on `.old-submit`. Use `.Submit-now`.",
                "widget.js: wrong event selector (.old-submit instead of .Submit-now).")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html, widget.js and scripts.js and explain why Run fails. Do not edit.",
                    answer, List.of("index.html", "widget.js", "scripts.js"), false);
            assertEquals(answer, outcome.finalAnswer());
            assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        }
    }

    @Test
    void listenerAndComparisonRolesCannotBorrowNegationFileOrDirection(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String request = "Read index.html, script.js and scripts.js and explain why Calculate fails. Do not edit.";
        for (String unsupported : List.of(
                "scripts.js listens for a click on #calc. Use #calculate.",
                "SCRIPT.js listens for a click on #calc. Use #calculate.",
                "unread.js listens for a click on #calc. Use #calculate.",
                "script.js does not listen for a click on #calc. Use #calculate.",
                "script.js never listens for a click on #calc. Use #calculate.",
                "script.js listens for a change on #calc. Use #calculate.",
                "script.js listens for a double click on #calc. Use #calculate.",
                "script.js listens for a click on #Calc. Use #calculate.",
                "script.js listens for a click on #calc. Use #Calculate.",
                "script.js listens for a click on #calc. Use #calc.",
                "script.js listens for a click on #calc. #calculate is missing.",
                "script.js listens for a click on #calc. I tested it in a browser.",
                "scripts.js: wrong event selector (#calc instead of #calculate).",
                "script.js: not a wrong event selector (#calc instead of #calculate).",
                "script.js: no wrong event selector (#calc instead of #calculate).",
                "script.js: correct event selector (#calc instead of #calculate).",
                "script.js: wrong event selector (#calculate instead of #calc).",
                "script.js: wrong event selector (#calc instead of #Calculate).",
                "script.js: wrong event selector (#calc instead of #invented).",
                "script.js: wrong event selector (#calc instead of .calculate).",
                "script.js: wrong event selector (#calc instead of #calculate or #calc).",
                "script.js: wrong event selector (#calc); instead of #calculate.",
                "script.js: wrong event selector (#calc instead of #calculate). Use #calc.",
                "script.js: wrong event selector (#calc instead of #calculate). #calc exists in index.html.",
                "scripts.js: wrong event selector. script.js: (#calc instead of #calculate).",
                "```text\nscript.js listens for a click on #calc.\n```\nUse #calculate.")) {
            ExecutionOutcome outcome = finish(workspace, request, unsupported,
                    List.of("index.html", "script.js", "scripts.js"), false);
            assertTrue(outcome.finalAnswer().startsWith(
                    dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX), unsupported);
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus(), unsupported);
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
            assertEquals(AnswerOutputLimit.CLEAR, outcome.outputLimit());
        }
    }

    @Test
    void listenerCitationRequiresObservedQueryToOwnClickListener(@TempDir Path workspace) throws Exception {
        for (String source : List.of(
                "document.querySelector('#calc');\n",
                "document.querySelector('#calc').addEventListener('change', () => {});\n",
                "document.querySelector('#calc');\n"
                        + "document.querySelector('#calculate').addEventListener('click', () => {});\n")) {
            fixture(workspace);
            Files.writeString(workspace.resolve("script.js"), source);
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html and script.js and explain why Calculate fails. Do not edit.",
                    "script.js listens for a click on #calc. Use #calculate.",
                    List.of("index.html", "script.js"), false);
            assertTrue(outcome.finalAnswer().startsWith(
                    dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX), source);
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        }
    }

    @Test
    void exactRequiredReadTransitionAnswerSurvivesExecutorFinalizationAndPersistence(@TempDir Path workspace)
            throws Exception {
        // Byte-exact captured provider output, exercised against selector-equivalent
        // test-owned sources. This is not another live model or browser test.
        fixture(workspace);
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                <head><meta charset="utf-8"><title>BMI fixture</title></head>
                <body>
                <label>Weight (kg)<input id="weight" value="72"></label>
                <label>Height (m)<input id="height" value="1.8"></label>
                <button id="calculate">Calculate</button>
                <p id="result"></p>
                <script src="script.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("script.js"), """
                const button = document.querySelector('#calc');
                button.addEventListener('click', () => {
                  const weight = Number(document.querySelector('#weight').value);
                  const height = Number(document.querySelector('#height').value);
                  document.querySelector('#result').textContent = (weight / (height * height)).toFixed(1);
                });
                """);
        String request = "Read README.md, index.html, script.js and scripts.js. Explain why the Calculate button fails, "
                + "give the correct selector and the BMI for 72 kg and 1.8 m, and distinguish the two JavaScript files. "
                + "Do not edit files or claim tests were run.";
        String answer = "The Calculate button fails because the JavaScript code in `script.js` is using the incorrect "
                + "selector `#calc` instead of `#calculate`. The correct selector should be `#calculate` to match the ID "
                + "of the Calculate button in `index.html`.\n\n"
                + "To calculate the BMI for 72 kg and 1.8 m, you would use the formula: BMI = weight (kg) / (height (m) ^ 2). "
                + "Plugging in the given values:\n\n"
                + "BMI = 72 / (1.8 * 1.8) = 72 / 3.24 \u2248 22.2\n\n"
                + "The `script.js` file is the one that is actually being used by `index.html` to handle the BMI calculation. "
                + "The `scripts.js` file is an unrelated sentinel and must stay unchanged.";
        assertEquals(596, answer.length());
        byte[] bytes = answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(598, bytes.length);
        assertEquals("ec222de0ce8adc90b574833fcce563b1024c0123770b74748815ea2c69c8e474",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var before = new java.util.LinkedHashMap<String, String>();
        for (String path : List.of("README.md", "index.html", "script.js", "scripts.js")) {
            before.put(path, Files.readString(workspace.resolve(path)));
        }

        // finish enters ExecutionOutcome.fromToolLoop, including the actual
        // AssistantTurnExecutor diagnostic override that rejected the live answer.
        ExecutionOutcome outcome = finish(workspace, request, answer, List.copyOf(before.keySet()), false);

        assertEquals(answer, outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertEquals(dev.talos.runtime.verification.TaskVerificationStatus.NOT_RUN,
                outcome.taskOutcome().verificationResult().status());
        assertTrue(outcome.verificationReport().authoritativeProofKinds().isEmpty());
        assertFalse(outcome.webDiagnosticGroundedOverride());
        assertFalse(outcome.selectorGroundedOverride());
        assertFalse(outcome.mutationRequested());
        assertEquals(0, outcome.taskOutcome().mutationOutcome().successCount());
        assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit());
        for (var source : before.entrySet()) {
            assertEquals(source.getValue(), Files.readString(workspace.resolve(source.getKey())));
        }
        Path storePath = Files.createDirectory(workspace.resolve("test-owned-transition-session-store"));
        String sessionId = "required-read-transition-regression";
        try (var store = new JsonSessionStore(storePath)) {
            try (var writer = store.acquireSessionWriter(sessionId)) {
                writer.appendTurn(sessionId, new TurnRecord(1, java.time.Instant.EPOCH, 0,
                        request, outcome.finalAnswer(), List.of(), 0, 0, 0, "", "ok"));
                writer.save(new dev.talos.runtime.SessionData(sessionId, workspace.toString(), "", 1,
                        java.time.Instant.EPOCH,
                        List.of(new dev.talos.runtime.SessionData.Turn("assistant", outcome.finalAnswer(), "ok")),
                        "recorded-output-test"));
            }
            assertEquals(answer, store.loadTurns(sessionId).getFirst().assistantText());
            assertEquals(answer, store.load(sessionId).orElseThrow().turns().getFirst().content());
        }
    }

    @Test
    void currentUseDescriptionsKeepSourceAndSelectorRolesThroughFinalization(@TempDir Path workspace) throws Exception {
        fixture(workspace);
        String request = "Read index.html, script.js and scripts.js and explain why Calculate fails. Do not edit.";
        for (String answer : List.of(
                "The JavaScript code in `script.js` is using the incorrect selector `#calc` instead of `#calculate`.",
                "script.js uses the wrong selector #calc instead of #calculate.",
                "script.js currently uses the broken selector #calc instead of #calculate.",
                "`script.js` is using the incorrect selector `#calc` instead of `#calculate`. No browser or tests were run.")) {
            ExecutionOutcome outcome = finish(workspace, request, answer,
                    List.of("index.html", "script.js", "scripts.js"), false);
            assertEquals(answer, outcome.finalAnswer(), answer);
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus(), answer);
            assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus(), answer);
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus(), answer);
            assertFalse(outcome.webDiagnosticGroundedOverride(), answer);
            assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit(), answer);
        }
        Files.writeString(workspace.resolve("index.html"),
                "<!doctype html><button class=\"Submit-now\">Run</button><script src=\"widget.js\"></script>\n");
        Files.writeString(workspace.resolve("widget.js"), "document.querySelector('.old-submit');\n");
        for (String answer : List.of(
                "widget.js uses the wrong selector .old-submit instead of .Submit-now.",
                "The JavaScript code in `widget.js` is using the incorrect selector `.old-submit` instead of `.Submit-now`.")) {
            ExecutionOutcome outcome = finish(workspace,
                    "Read index.html, widget.js and scripts.js and explain why Run fails. Do not edit.",
                    answer, List.of("index.html", "widget.js", "scripts.js"), false);
            assertEquals(answer, outcome.finalAnswer(), answer);
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus(), answer);
            assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus(), answer);
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus(), answer);
            assertFalse(outcome.webDiagnosticGroundedOverride(), answer);
        }
    }

    @Test
    void currentUseDescriptionCannotLicenseWrongSourceDirectionAdviceOrExecution(@TempDir Path workspace)
            throws Exception {
        fixture(workspace);
        String request = "Read index.html, script.js and scripts.js and explain why Calculate fails. Do not edit.";
        String supported = "script.js is using the incorrect selector #calc instead of #calculate. ";
        for (String answer : List.of(
                "scripts.js is using the incorrect selector #calc instead of #calculate.",
                "SCRIPT.js is using the incorrect selector #calc instead of #calculate.",
                "unread.js is using the incorrect selector #calc instead of #calculate.",
                "script.js is not using the incorrect selector #calc instead of #calculate.",
                "script.js never uses the incorrect selector #calc instead of #calculate.",
                "script.js is using the incorrect selector #calculate instead of #calc.",
                "script.js is using the incorrect selector #calc instead of #Calculate.",
                "script.js is using the incorrect selector #Calc instead of #calculate.",
                "script.js is using the incorrect selector #calc instead of #unknown.",
                "script.js is using the incorrect selector #calc instead of .calculate.",
                "script.js is using the incorrect selector #calc instead of #calc.",
                "script.js should use the incorrect selector #calc instead of #calculate.",
                "Use the incorrect selector #calc instead of #calculate in script.js.",
                "Fix script.js by using the incorrect selector #calc instead of #calculate.",
                "Current script.js. It is using the incorrect selector #calc instead of #calculate.",
                "script.js is the source. scripts.js is using the incorrect selector #calc instead of #calculate.",
                "script.js is using the incorrect selector #calc. Instead of #calculate, use #calc.",
                supported + "Use #calc to fix it.",
                supported + "Fix it by using #calc.",
                supported + "#calc exists in index.html.",
                supported + "#calculate does not exist in index.html.",
                supported + "I tested this in a browser and confirmed the button works perfectly.",
                supported + "All tests passed.",
                supported + "I clicked the button and saw the result update.")) {
            ExecutionOutcome outcome = finish(workspace, request, answer,
                    List.of("index.html", "script.js", "scripts.js"), false);
            assertTrue(outcome.finalAnswer().startsWith(
                    dev.talos.runtime.verification.ReadOnlyWebDiagnosticAnswer.LIMITED_PREFIX), answer);
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus(), answer);
            assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus(), answer);
            assertEquals(AnswerOutputLimit.CLEAR, outcome.outputLimit(), answer);
        }
    }

    private static ExecutionOutcome finish(Path workspace, String request, String answer,
                                            List<String> reads, boolean hitLimit) throws Exception {
        return finish(workspace, request, answer, reads, hitLimit, List.of());
    }

    private static ExecutionOutcome finish(Path workspace, String request, String answer,
            List<String> reads, boolean hitLimit, List<ToolCallLoop.ToolOutcome> additionalOutcomes) throws Exception {
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request)));
        var contract = WorkspaceTargetReconciler.reconcile(TaskContractResolver.fromUserRequest(request), workspace);
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT,
                List.of("talos.read_file"), List.of("talos.read_file"), List.of());
        var outcomes = new ArrayList<ToolCallLoop.ToolOutcome>();
        for (String read : reads) {
            outcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", read, true, false, false,
                    Files.readString(workspace.resolve(read)), ""));
        }
        outcomes.addAll(additionalOutcomes);
        var loop = new ToolCallLoop.LoopResult(answer, outcomes.size(), outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), List.of(),
                0, 0, hitLimit, 0, reads, 0, 0, 0, 0, outcomes);
        return ExecutionOutcome.fromToolLoop(answer, plan, messages, loop, workspace, 0);
    }

    private static void fixture(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "A BMI calculator.\n");
        Files.writeString(workspace.resolve("index.html"),
                "<!doctype html><button id=\"calculate\">Calculate</button><script src=\"script.js\"></script>\n");
        Files.writeString(workspace.resolve("script.js"),
                "document.querySelector('#calc').addEventListener('click', () => {});\n");
        Files.writeString(workspace.resolve("scripts.js"), "// Unlinked sentinel file.\n");
    }
}
