# T246 - Unsupported PDF Requests Must Stay Runtime-Owned

Status: done

Closed: 2026-05-11

Severity: high

## Problem

The live beta transcript showed Talos accepting a PDF request, then later
rejecting `pdf_guide.pdf` but creating `pdf_guide.md` containing model-authored
false capability prose:

```text
I'm unable to generate or produce files directly...
```

That is the wrong product behavior. Talos cannot create valid PDF binaries with
the current text-file tool surface, but it can create supported text artifacts.
Unsupported document capability answers must be runtime-owned, clear, and must
not create fake fallback files unless the user explicitly asks for a supported
alternative.

## Evidence

Live transcript:

- `0I want to create a pdf with instructions for me on how to create a bmi calculator web page!`
- `you should create the pdf guide!`
- `pdf_guide.pdf` failed as unsupported.
- `pdf_guide.md` was created with false model prose.
- `so you cannot create pdf ?` received generic model wording instead of Talos
  product capability wording.

Existing code already has partial guards:

- `UnsupportedDocumentMutationPolicy`
- `FileWriteTool`
- `AssistantTurnExecutor.unsupportedCapabilityPreflightIfNeeded`

This ticket closes the remaining phrasing/follow-up gaps and proves the exact
live transcript shapes.

## Scope

- Add exact live-phrase tests for unsupported PDF creation, including typo-like
  leading characters.
- Add deterministic capability handling for PDF/DOCX/etc. capability questions
  such as `so you cannot create pdf?`.
- Ensure unsupported binary document creation does not call the provider and
  does not create fake `.md` fallback files.
- Keep supported alternatives as suggestions only unless explicitly requested.

## Acceptance

- `I want to create a pdf...` and `you should create the pdf guide!` return a
  runtime-owned unsupported PDF answer without provider calls.
- No `.pdf` or fallback `.md` is created for unsupported PDF creation requests.
- `so you cannot create pdf?` receives a truthful Talos capability answer, not
  model-authored generic AI prose.
- Existing supported Markdown/HTML/text creation remains unchanged.

## Resolution

- Unsupported binary-document creation detection now covers natural format
  artifact phrasing such as `create a pdf ...` and `the pdf guide`.
- Unsupported PDF creation/capability follow-ups are answered by runtime policy
  before calling the model.
- The detector was narrowed after e2e testing so unsupported read requests such
  as `read report.docx` still go through the read-evidence/unsupported-read
  path instead of being misclassified as creation.

## Verification

- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.unsupportedPdfCapabilityQuestionUsesTalosProductAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.unsupportedPdfCreationLivePhraseReturnsCapabilityAnswerWithoutProviderOrFallbackFile' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.unsupportedPdfCreationFollowUpReturnsCapabilityAnswerWithoutProviderOrFallbackFile' --no-daemon`
- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming' --no-daemon`
- `.\gradlew e2eTest --tests 'dev.talos.harness.JsonScenarioPackTest' --no-daemon`
- `.\gradlew test --no-daemon`
- `.\gradlew build --no-daemon`
