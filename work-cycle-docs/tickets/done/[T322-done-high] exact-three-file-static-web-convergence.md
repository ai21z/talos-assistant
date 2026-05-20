# T322 - Exact Three-File Static Web Convergence

Severity: High

Status: done - deterministic gates and fresh installed-product live follow-up audit pass

Source: Five scenario big audit, 2026-05-19

## Problem

Talos is safe but not reliably convergent for a realistic frontend request that asks for exactly:

```text
index.html
style.css
script.js
```

The live audit showed:

- correct mutation classification,
- approval-gated file creation,
- three files created,
- false success blocked by verification,
- but static verifier applied irrelevant calculator/form requirements,
- repair target logic drifted to `styles.css` and `scripts.js`.

## Evidence

Local transcript:

```text
local/manual-testing/five-scenario-audit-20260519-221645/20260519-221913/five-web-synthwave-site.txt
```

Related existing tickets:

```text
T297 static-web-edit-reliability-before-beta
T316 static-site-artifact-completeness-verifier
T318 correction-prompts-repair-apply-mode
```

Update 2026-05-20:

- Follow-up classification already has deterministic coverage for transcript-style prompts:
  - `Great! now can you create that site?` inherits apply-capable file creation after a prior synthwave text guide.
  - `But you just changed the index and reduced it. You never put any style in the index` inherits an apply-capable correction contract after a prior site mutation.
- Static verifier already has coverage for styled-web failure when only HTML is written without CSS/inline style.
- Static verifier now distinguishes generic interactive/styled websites from calculator/form tasks. The verifier no longer requires form/input/result elements merely because the site prompt says `interactive`, `functional`, or `functioning`.
- Static verifier no longer treats explicit text-guide requests such as `create a txt file that talks about how to build a synthwave band's web page` as failed static-web artifacts.
- Static verifier now treats `style` plus `JavaScript interaction` follow-ups as web verification candidates even when the current prompt does not literally repeat `website`.
- `ExecutionOutcome` now records embedded static-verification failures from the tool loop as verification `FAILED` in outcome/trace evidence instead of `NOT_RUN`.
- Focused evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.interactiveStyledBandSiteDoesNotRequireCalculatorFormResultElements" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.textGuideAboutBuildingWebPageDoesNotTriggerStaticWebVerification" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.styleAndJavascriptInteractionFollowUpVerifiesMissingScriptReference" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest.embeddedStaticVerificationFailureInBlockedToolLoopIsRecordedInOutcomeAndTrace" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

These focused checks passed on `v0.9.0-beta-dev` after the implementation slices.

Live mini-audit evidence:

```text
local/manual-testing/static-web-synthwave-live-20260520-1aa74c31-r3/artifacts/TRANSCRIPT.txt
local/manual-workspaces/static-web-synthwave-live-20260520-1aa74c31-r3/workspace
```

Result:

- The text-guide turn is no longer falsely failed by static-web coherence verification.
- The site creation turn still created only `index.html` and was classified `COMPLETED_UNVERIFIED`.
- The style/JavaScript follow-up created `style.css` but still missed `script.js`.
- Runtime now blocks the final turn with `Static verification failed - HTML references missing JavaScript file: script.js`.
- `/last trace` now records `Verification: FAILED` for that embedded static failure.
- Artifact canary scan over the r3 live-audit directories passed:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/static-web-synthwave-live-20260520-1aa74c31-r3,local/manual-workspaces/static-web-synthwave-live-20260520-1aa74c31-r3" --no-daemon
```

## Expected Behavior

For:

```text
Create the full synthwave frontend now with exactly index.html, style.css, and script.js.
```

Talos must:

- request approval before mutation,
- create or edit exactly those three files,
- not create `styles.css` or `scripts.js`,
- ensure `index.html` links `style.css` and `script.js`,
- distinguish static coherence checks from browser execution,
- not apply calculator/form-specific verifier requirements unless the task actually requests a calculator/form.

## Regression Tests

Add deterministic tests:

```text
createExactSynthwaveThreeFileSurface_usesIndexStyleScriptOnly       // covered by exact expected-target and preferred target tests; needs live rerun evidence
styledSiteDoesNotTriggerCalculatorResultRequirement                 // added as interactiveStyledBandSiteDoesNotRequireCalculatorFormResultElements
staticRepairPreservesRequestedStyleCssAndScriptJsNames              // covered by repair/follow-up target tests; needs live rerun evidence
plainSiteCorrectionInheritsApplyMode                                // covered by missingStylingCorrectionAfterSiteMutationInheritsApplyCapableContract
```

## Fix Direction

Separate verifier profiles more explicitly:

- styled landing page
- form/calculator
- selector repair
- generic static page

Repair target discovery must preserve explicit user target names over default plural conventions.

Current remaining work:

1. Improve the continuation/repair prompt or expected-target planning so a live model that creates `index.html` linking `script.js` is driven to create `script.js`, not only `style.css`.
2. Decide whether `Great! now can you create that site?` after a guide-writing turn should infer exact static-web target expectations (`index.html`, `style.css`, `script.js`) earlier, so the second turn cannot stop at `COMPLETED_UNVERIFIED` after only `index.html`.
3. Rerun the focused live synthwave audit from a fresh workspace with `/debug prompt on`, `/last trace`, and prompt-debug save after each natural prompt.
4. Keep the ticket open if the live model still writes only HTML/CSS, misses `script.js`, drifts to `styles.css`/`scripts.js`, or claims styling/functionality without files.

Update 2026-05-20, later T322 reduction:

Deterministic fixes added after r3/r4:

- `TaskContractResolver` now infers conventional `index.html`, `style.css`, and `script.js` targets for natural/static synthwave site creation and contextual follow-ups after a web guide/site turn, while preserving text-guide requests as document artifacts.
- `ToolCallRepromptStage` now supports expected-target-scope repair even when missing creation targets have no readback yet.
- `ToolCallLoop` now lets wrong-target attempts during expected-target progress flow through normal pre-approval path policy, so the target-scope repair path can reprompt instead of terminating immediately.
- `ToolSurfacePlanner` narrows exact static-web file target turns to file/read evidence tools and omits workspace operation tools such as `talos.mkdir`, `talos.apply_workspace_batch`, `talos.copy_path`, `talos.move_path`, and `talos.rename_path`.
- `CurrentTurnCapabilityFrame` and target-scope compact repair prompts now explicitly forbid putting required root files under invented `css/`, `js/`, `assets/`, `site/`, or other subdirectories.
- `StaticWebCapabilityProfile` now selects the static-web verifier for deictic site-creation turns when the contract has inferred exact HTML/CSS/JS expected targets.
- `StaticTaskVerifier` now accepts CSS compound selectors whose secondary class is added dynamically through JavaScript `classList.add(...)` or `classList.toggle(...)`, preventing false failures such as `.neon-box.off` when JS adds `off`.

Focused deterministic evidence passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest" --tests "dev.talos.runtime.capability.CapabilityProfileRegistryTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeBlockedMkdirForStaticWebCreationRepromptsToExactFiles" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetProgressWrongFileAttemptRepromptsToRemainingStaticWebTarget" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
.\gradlew.bat installDist --no-daemon
```

Installed-product live evidence:

```text
local/manual-testing/static-web-synthwave-live-20260520-t322-r6/artifacts/TRANSCRIPT.txt
local/manual-workspaces/static-web-synthwave-live-20260520-t322-r6/workspace
```

Result: explicit exact-target prompt created `index.html`, `style.css`, and `script.js` and ended as `COMPLETED_VERIFIED` with `Static web coherence checks passed for 3 mutated target(s).`

Harder transcript-style follow-up evidence:

```text
local/manual-testing/static-web-synthwave-live-20260520-t322-r9-followup/artifacts/TRANSCRIPT.txt
local/manual-workspaces/static-web-synthwave-live-20260520-t322-r9-followup/workspace
local/manual-testing/static-web-synthwave-live-20260520-t322-r10-followup/artifacts/TRANSCRIPT.txt
local/manual-workspaces/static-web-synthwave-live-20260520-t322-r10-followup/workspace
local/manual-testing/static-web-synthwave-live-20260520-t322-r11-followup/artifacts/TRANSCRIPT.txt
local/manual-workspaces/static-web-synthwave-live-20260520-t322-r11-followup/workspace
```

Result: still open. The transcript-style sequence:

```text
1. Create a txt file about how to build a synthwave band's web page.
2. Great! now can you create that site?
```

now gets the correct expected target frame and narrowed tool surface, but the live model still drifts to non-required paths such as `css/style.css`, `synthwave_site/index.html`, and `synthwave_site/`. Runtime blocks those before approval and prevents false success, but the turn still does not reliably converge to all three root targets with static verification.

Current remaining blocker:

```text
The harder deictic follow-up still needs a stronger runtime-owned convergence strategy after partial static-web progress and blocked substitute paths. Prompt steering and tool narrowing reduced the failure surface but did not eliminate live-model drift.
```

Possible next implementation direction:

- Convert partial static-web expected-target progress into a compact, target-only continuation that carries only:
  - current user request,
  - required remaining target(s),
  - already written target summaries,
  - exact root-path constraint,
  - write/edit tools only.
- Consider making same-turn static-web creation repair target-specific after the first blocked substitute path instead of carrying normal conversation history.
- Keep the runtime's current fail-closed behavior: do not allow substitute paths such as `css/style.css` to satisfy required root `style.css`.

Update 2026-05-20, deterministic same-turn target-pollution fix:

- Root cause found while reducing the `JsonScenarioPackTest` static-web failures:
  `TaskContractResolver.withContextualStaticWebTargets(...)` scanned assistant messages
  after the latest user request. During the same tool loop, the model's own JSON tool
  calls mentioning `styles.css` and `script.js` made the original generic website
  request look like a contextual static-web follow-up. That polluted the task contract
  mid-turn and raised a false pending exact-target obligation for `style.css`.
- Fix: contextual static-web inheritance now considers only messages before the latest
  real user message. Current-turn assistant/tool output no longer changes the user's
  target intent.
- Regression added:

```text
TaskContractResolverTest.currentTurnAssistantToolOutputDoesNotCreateContextualStaticWebTargets
ExecutionOutcomeTest.partialInvalidStaticWebRepairRunsStaticVerificationForChangedWorkspace
```

- Focused deterministic evidence passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest.currentTurnAssistantToolOutputDoesNotCreateContextualStaticWebTargets" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.buildWebsitePromptAllowsApply" --tests "dev.talos.harness.JsonScenarioPackTest.staticVerifierFailsBrokenWebAppBuildLinkage" --tests "dev.talos.harness.JsonScenarioPackTest.partialMutationStaticVerificationSurfacesProblems" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

Remaining status:

```text
Still open pending a fresh installed-product live rerun of the harder transcript-style
follow-up. Deterministic E2E no longer shows the same-turn target pollution failure,
but T322 should not close until live evidence proves the deictic guide-to-site flow
converges or a narrower residual ticket is created.
```

Closure update 2026-05-20:

- Added target-pollution regression and compact-repair readback regression.
- Strengthened expected-target compact repair so the model receives readbacks for already-written
  small static-web files such as `index.html` and `style.css` when a remaining linked target
  such as `script.js` must be created.
- Fresh installed-product live audit passed the hard transcript-style sequence:

```text
local/manual-testing/static-web-synthwave-live-20260520-t322-r14-followup/artifacts/TRANSCRIPT.txt
local/manual-workspaces/static-web-synthwave-live-20260520-t322-r14-followup/workspace
```

Observed r14 result:

```text
Turn 2 status: COMPLETE
Outcome: COMPLETED_VERIFIED
Expected targets: index.html, script.js, style.css
Tools: talos.write_file -> index.html [ok], style.css [ok], script.js [ok]
Verification: PASSED - Static web coherence checks passed for 3 mutated target(s).
```

Artifact scan evidence:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/static-web-synthwave-live-20260520-t322-r14-followup,local/manual-workspaces/static-web-synthwave-live-20260520-t322-r14-followup" --no-daemon
```

Result: passed.
