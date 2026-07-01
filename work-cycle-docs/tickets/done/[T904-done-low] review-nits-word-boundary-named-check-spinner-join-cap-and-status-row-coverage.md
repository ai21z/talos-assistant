# [T904-done-low] Review nits: word-boundary named-check, spinner join-cap, status-row coverage gap

Status: done
Priority: low

## Evidence Summary

- Source: adversarial self-review of T898-T901 (workflow wf_bec7abba) - the confirmed nit/medium polish items across the T900 and T901 dimensions.
- Talos version / commit: 0.10.6 / 1edc7142 (branch improvement/qodana-cleanup)
- Verification status: #5 fixed + tested; #6 documented; #3 documented with rationale (a deterministic test needs a pseudo-TTY).

## Items

### #5 (fixed) T900 named-check used raw substring
[EvidenceGate.isAbsentInferredStaticWebSatellite](src/main/java/dev/talos/runtime/policy/EvidenceGate.java) used `lowerRequest.contains(basename)` to decide a satellite was "user named", so an unrelated file whose name contains the literal "script.js"/"style.css" as a substring (e.g. "refactor myscript.js") would keep an absent inferred `script.js` required and leave a residual false-block. Changed to the existing word-boundary helper `containsWord(lowerRequest, basename)`. This was already the conservative direction (it could only over-require a read, never wrongly drop one, so it was never a trust weakening), but the tightening removes the residual false-block.

### #6 (documented) Spinner join-cap inconsistency
The legacy carriage-return spinner stops with `spinnerThread.join(200)` while the JLine status row caps at 300ms. Added a comment at [RenderEngine.stopSpinner](src/main/java/dev/talos/cli/repl/RenderEngine.java) documenting the bound (the daemon ticks every 120ms, so a clean stop completes within one frame; the cap just guarantees stopSpinner never blocks the single writer thread for long). No behavior change; the caps are independent safety bounds.

### #3 (documented gap) T901 status-row path is untested
The new `RenderEngineTest.SpinnerResumeDuringToolLoop` tests exercise only the legacy carriage-return path (the test RenderEngine is built with `terminal=null`, so `statusRow == null`). The status-row path most modern terminals hit (per-tool-line `statusRow.stop()` then `startSpinner()` -> `statusRow.start()`) has no automated coverage. The review judged it safe-by-design (JLine `Status` owns its scroll region, so this is at worst mild flicker, not corruption), and `spinnerRunning()` only reflects the legacy flag. A deterministic unit test would require a pseudo-TTY advertising change_scroll_region/save_cursor/restore_cursor/cursor_address (StatusRowPresenter.supported()), which the current harness cannot provide without a brittle JLine mock that would assert nothing meaningful. Resolution: documented here, flagged for interactive owner confirmation on the refreshed install. A larger, optional follow-up worth considering is keeping the status row up for the whole turn and only re-labeling it (instead of stop/start per tool line), which would remove the churn entirely; deferred as it is a redesign beyond this nit and the current behavior is safe.

## Non-Goals

- No trust-surface change (all three items are read-evidence conservativeness or presentation chrome).
- No spinner-backend refactor or pseudo-TTY test harness in this ticket.

## Tests / Evidence

[EvidenceGateTest](src/test/java/dev/talos/runtime/policy/EvidenceGateTest.java): `unrelatedFileSharingASatelliteSubstringDoesNotKeepTheAbsentSatellite` ("refactor myscript.js" no longer keeps an absent script.js); existing named/present/absent satellite cases stay green. [RenderEngineTest](src/test/java/dev/talos/cli/repl/RenderEngineTest.java) legacy-path spinner tests stay green.

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One commit (code + test + ticket).
