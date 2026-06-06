# T705 - Static-Web Content And Selector Evidence Normalization

Status: done
Priority: medium
Created: 2026-06-06

## Problem

The `test02-12` audit showed the static verifier catching missing facts and selector issues, but some checks are too literal for generated static sites:

- A visible line such as `Rome - 15 July 2026` may fail a requirement expressed as `Rome 15 July 2026`.
- A selector such as `.hero` may be created dynamically from linked JavaScript, while the current static selector inventory can treat it as missing from HTML.
- Some required facts appeared only in linked JavaScript strings, which is weaker than initial HTML visibility but still relevant evidence that should be classified precisely rather than ignored or over-credited.

## Code Evidence

- Static-web content preservation currently checks required facts deterministically from extracted text.
- Static selector checks focus on HTML/CSS relationships and can miss linked-JS-created DOM structures.

## Acceptance Criteria

- Required visible fact matching should normalize simple punctuation and whitespace differences without becoming fuzzy LLM judging.
- Linked JavaScript string evidence may be recorded as weaker evidence, but it must not be overclaimed as first-load visible browser proof unless the browser behavior verifier observes it.
- Selector diagnostics should distinguish "missing from initial HTML" from "possibly created by linked JavaScript" when source evidence supports that distinction.
- No LLM judge is introduced.

## Test Plan

- Add verifier tests where `Rome - 15 July 2026` satisfies `Rome 15 July 2026`.
- Add tests for linked JavaScript string evidence as weak/static evidence.
- Keep a negative test where a genuinely missing required fact fails.

## Notes

This is not visual verification. It is deterministic static-evidence normalization.

## Completion Evidence

- Added RED/GREEN `StaticTaskVerifierTest` coverage for normalized city/date fact matching across simple punctuation.
- Added RED/GREEN `StaticTaskVerifierTest` coverage for linked JavaScript string evidence that is reported as weak static evidence while still failing required visible HTML preservation.
- Added RED/GREEN `StaticWebSelectorAnalyzerTest` coverage for JS-created classes via `className`, `className +=`, and `setAttribute('class', ...)` without inventing initial HTML classes.
- Updated `StaticWebContentPreservationVerifier` with deterministic punctuation/whitespace/entity normalization and conservative JavaScript string evidence extraction.
- Updated `StaticWebSelectorAnalyzer` dynamic class extraction for common class assignment APIs.
- Verified with focused static verifier tests, all `dev.talos.runtime.verification.*` tests, full `.\gradlew.bat check --no-daemon`, and `git diff --check` on 2026-06-06.
