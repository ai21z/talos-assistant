# Model qualification runbook

Use this runbook after candidate research and before any profile or hardware support claim. It executes the lifecycle documented in the repository without weakening Talos policy.

Machine-readable JSON is authoritative. Markdown guides and reports help reviewers, but they do not replace campaign, run, trace, approval, byte, timing, or resource records.

## Preconditions

- the working tree is clean
- the branch and exact Talos commit are recorded
- the candidate artifact or controlled conversion is frozen
- local SHA-256 and size match the candidate packet
- the qualification harness has no unresolved blocking defect
- the executing protocol-v6 Java finalizer identity is frozen and matches the harness manifest
- the pinned llama.cpp executable and its SHA-256 are available
- the scenario-bank hash is recorded
- the test workspace and `TALOS_HOME` are isolated
- enough disk remains for transcripts, traces, logs, snapshots, and reports

Do not start a model trial while any identity fact is unknown.

The complete qualification packet is local-sensitive evidence, not a
shareable or export-safe artifact. It intentionally preserves exact provider
requests, provider logs, and absolute execution-input paths so later review
can explain what ran. Keep it in ignored local storage and do not publish or
export it. The typed provider-process receipt and every trace, causal, result,
scorecard, and terminal projection derived from that receipt remain path-free:
they contain hashes and closed identity facts, never raw launch arguments,
environment values, provider-log lines, or model content.

The architecture-program freeze remains active while T1151 through T1157,
T1170, or T1187 through T1195 are open. These are minimum causal-evidence
dependencies; fresh campaigns still wait for Phase 6 release closure.
Deterministic synthetic development
fixtures are allowed and are never comparative evidence. Do not run a
provider-backed protocol-v6 installed canary, admission, full campaign,
scorecard, profile disposition change, or hardware claim until its applicable
gates close.

## Local layout

Keep evidence under:

```text
local/model-qualification/<campaign-id>/
  campaign.json
  calibration-rules.json
  calibration-guide.md
  candidates/<alias>/candidate.json
  machines/<machine-id>.json
  engines/<lane-id>.json
  runs/<candidate>/<machine>/<config>/<scenario>/<repetition>/
  scorecards/
```

Keep model weights and conversion intermediates in ignored model storage. Keep reviewer summaries in ignored `reports/`.

## Gate 0, verify identity

Record and verify:

1. branch, commit, version, and clean status
2. built and installed Talos artifact hashes when testing the installed product; protocol v6
   requires the same exact Talos JAR bytes and binds the installed JAR plus the complete
   ordered runtime classpath to the first and only `TRACE_STARTED` event
3. candidate repository, revision, file, size, SHA-256, and license
4. conversion lineage when the GGUF is locally generated
5. llama.cpp tag, source commit, executable SHA-256, backend, and driver
6. machine identity and available resources
7. protocol revision and scenario-bank hash
8. protocol-v6 `execution-input-ledger.json` (schema v1), `harness-manifest.json`
   (schema v2), `runtime-identity.json` (schema v5), and any evidence-gated
   `preflight-manifest.json` (schema v2), including every manifest-to-manifest byte-hash edge
9. the protocol-v6 provider launch-environment contract and digest plus endpoint-observer
   kind and identity digest produced in the exact campaign shell before calibration is sealed

Stop if any value differs from the planned packet.

## Gate 1, freeze calibration

Complete the calibration JSON and reviewer guide before trial one. Hash both into `campaign.json`.

For protocol v6, run the maintained identity producer from the same shell and environment that
will invoke the installed PTY capture. Copy its exact four output fields into both schema-v2
`engine.json` and schema-v2 `calibration-rules.json` as `launchEnvironmentContract`,
`launchEnvironmentSha256`, `endpointObserverKind`, and
`endpointObserverIdentitySha256`. Rerun the command immediately before the first capture and
stop if any value differs. Do not calculate or normalize these identities in PowerShell.

Freezing the observer digest prevents a later environment or helper substitution from gaining
authority; it does not establish platform provenance by itself. Before sealing a Windows lane,
record the actual Windows system directory, `netstat.exe` SHA-256, valid Authenticode signer,
and OS build in ignored installed evidence and stop if they differ from the declared lane.
Before sealing a Linux lane, record the procfs observer contract and the explicit assumption
that the managed provider does not delegate its listener descriptor outside its live process
tree. No schema field may replace that platform evidence with an operator-written `VALID`
assertion.

```powershell
.\gradlew.bat printModelQualificationLaunchEnvironmentIdentity --no-daemon
```

The calibration records model, engine, machine, launch arguments, context, tool mode, reasoning, template, sampling, output caps, timeout, seed policy, permitted deviations, stop rules, and evidence requirements.

Once the first trial starts, calibration is immutable. A material change creates a new campaign or configuration identity.

## Gate 2, direct engine preflight

Start the exact engine outside Talos when model compatibility is not already proven.

Capture:

- full launch command and environment
- load and model metadata lines
- `/props` and `/v1/models`
- no-tool response
- automatic tool response
- required tool response
- multi-argument tool response
- reasoning and modality behavior
- engine shutdown and process cleanup

Classify native handler and generic template behavior separately. Text-tool mode is a different configuration.

Stop on wrong identity, unsupported template, required projector, reasoning mismatch, malformed call, unsupported required-tool behavior, or engine patch requirement.

## Gate 3, run harness checks

Before live trials:

```powershell
.\gradlew.bat modelQualificationBankHash --no-daemon
.\gradlew.bat printModelQualificationFinalizerIdentity --no-daemon
.\gradlew.bat printModelQualificationExecutionInputIdentity --no-daemon
```

Run focused qualification tests when harness code changed. Run the full check before relying on a changed harness.

The hash emitted by `modelQualificationBankHash` must equal the value frozen in the campaign.

Freeze the harness from current inputs, not copied values from an older packet:

1. derive the scenario-bank hash from the declared protocol resources
2. compile the qualification harness before hashing its classes
3. include the admission and full scenario banks for the declared protocol in the harness manifest
4. include the basic, context, privacy, and web fixtures used by that protocol
5. hash the compiled harness, build files, banks, and fixtures only after compilation succeeds
6. record the emitted finalizer implementation SHA-256 in the schema-v2 harness manifest
7. bind the exact harness-manifest bytes, installed runtime bytes, candidate, calibration,
   engine, machine, model artifact, and engine executable through `runtime-identity.json`
8. when an adapted request layout requires preflight evidence, bind the exact
   `preflight-manifest.json` bytes and every file declared by that manifest

Stop if the generator, bank-hash task, frozen manifest, or campaign disagree. Recompiling after the manifest is written invalidates that packet.

After `e2eTestClasses` and the installed distribution are frozen, create the protocol-v6
identity manifests exactly once with explicit artifact paths:

```powershell
.\gradlew.bat writeModelQualificationExecutionIdentity `
  -PqualificationRoot=<absolute-packet-root> `
  -PqualificationBuiltTalosArtifact=<absolute-built-talos.jar> `
  -PqualificationInstalledTalosArtifact=<absolute-installed-root/lib/talos.jar> `
  -PqualificationModelArtifact=<absolute-model.gguf> `
  -PqualificationEngineExecutable=<absolute-llama-server> `
  -PqualificationHarnessInputSpecification=<absolute-harness-inputs.json> `
  --no-daemon
```

Discard the local operator specification when it is no longer needed. Reverify the durable
identity chain and every live frozen input without that specification, then run the pre-trial
calibration validator:

```powershell
.\gradlew.bat verifyModelQualificationExecutionIdentity `
  -PqualificationRoot=<absolute-packet-root> `
  -PqualificationBuiltTalosArtifact=<absolute-built-talos.jar> `
  -PqualificationInstalledTalosArtifact=<absolute-installed-root/lib/talos.jar> `
  -PqualificationModelArtifact=<absolute-model.gguf> `
  -PqualificationEngineExecutable=<absolute-llama-server> `
  --no-daemon
.\gradlew.bat validateModelQualificationCalibration `
  -PqualificationRoot=<absolute-packet-root> `
  --no-daemon
```

The execution-input identity command emits path-free JSON containing the finalizer
implementation hash and the executing Java classpath hash, size, and entry count. Freeze
those values only after `e2eTestClasses` is current.

The schema-v1 harness input specification pins the protocol, full Talos commit, executing
finalizer implementation and classpath SHA-256 values, built and installed Talos JAR hashes,
candidate, calibration, engine, machine, model, and engine-executable hashes, and an ordered
`installedRuntimeClasspath` list of `path` plus `sha256` entries. It also contains exact
lowercase SHA-256 maps named `packetFiles`, `repositoryFiles`, and `buildFiles`. Paths are
canonical forward-slash paths relative to the packet, repository, or installed distribution;
build paths begin with `build/`, and runtime entries are direct `lib/` files.

The role inventory must bind the packet-local scripts that are actually executed:
`tools/finalize-trial.ps1`, `tools/assert-campaign-identity.ps1`,
`tools/assert-trial-stop-conditions.ps1`, `tools/resource-stop-policy.ps1`, and
`tools/timestamp-policy.ps1`. It must also bind the
repository finalizer template
`work-cycle-docs/templates/finalize-model-qualification-result-v6.ps1`. Freezing only a
similarly named repository source script does not identify the copied packet-local program.
The shared harness does not contain executable `trial-stop-policy.ps1` causal inference, and
that path is not a canonical harness role. Do not copy or bind such a policy into a new v6
packet; historical v1-v5 packet-local copies remain immutable.

Keep the operator input specification local and outside the packet; it is an execution input,
not durable evidence, and must not preserve machine-specific paths. The producer writes a
canonical path-sanitized `execution-input-ledger.json` into the packet. That ledger records
ordered logical classpath tokens, ordinal, kind, size, and SHA-256 plus the bounded artifact
identities used to derive the manifests. `harness-manifest.json` hash-binds the ledger bytes,
and `runtime-identity.json` hash-binds the harness manifest.

For the external model and engine executable, the ledger records the SHA-256 of the byte stream
read once by the identity producer while size, modification time, creation time, and any
available platform file key remain unchanged across that read. Later verification deliberately
does not reread multi-gigabyte model content; it verifies the recorded hash against the frozen
candidate/engine manifests and checks only the declared stable-metadata continuity receipt.
This proves a capture-time stream observation plus later metadata continuity under a stable
filesystem. It does **not** prove that a subsequently launched process opened or consumed those
same bytes, and it is not resistant to hostile same-size replacement with restored metadata.
Protocol v6 requires the maintained launcher to emit a mechanically bound process-launch
receipt. A run without that receipt is incomplete/`UNKNOWN` for causal attribution and
comparative scoring; do not manufacture a synthetic receipt from operator prose or repeated
manifest values. The receipt proves the named process/endpoint observations only under its
declared stable-filesystem and stable-listener assumptions. T1188 remains open until the
installed Windows and Ubuntu/WSL real-process matrices establish those bounded claims.

The endpoint observer is itself part of the receipt identity. On Windows, the receipt digest
binds the no-follow `System32\\netstat.exe` bytes, normalized `System32` location, fixed
`-a -n -o` arguments, an explicit hash-bound `SYSTEMROOT`-only helper environment, and
authenticated `System32` working
directory. Preflight treats either an exact-loopback or wildcard listener as occupied, while
readiness, receipt binding, and transport require an exact managed-loopback listener and reject
all wildcard exposure. That digest identifies the observed helper bytes and invocation; it does not by
itself prove Microsoft provenance. Installed Windows evidence must separately record the
helper SHA-256, valid Authenticode signer, and OS build, and must stop if any of them differs
from the frozen lane declaration. On Linux, the observer digest identifies
Talos's versioned procfs managed-process-tree contract, not kernel provenance.
It rejects separate listener inodes and listener sharing by a live managed descendant, but
assumes the managed provider
does not delegate the same listener descriptor outside its live descendant tree and that the
owner remains stable between each observation and network transition.

The receipt's `/props` SHA-256 is the runtime hash of the exact response body read through the
process-specific direct HTTP client and is cross-checked against `MODEL_IDENTITY_RESOLVED`.
The packet does not retain an independent raw `/props` response, so describe this as a bound
runtime observation, not a second artifact-level proof. The managed child starts in the
no-follow parent directory of the frozen engine executable.
The receipt does not identify every dynamically loaded operating-system or accelerator library.

`GENERATION_REQUEST_DISPATCHED` is only local handoff admission for one exact
generation attempt. It does not prove that the send API was entered, socket or
process I/O began, a byte reached the provider, cancellation prevented send, or
the transport settled before the next trial. Protocol v6 therefore requires
T1191's exact transport-operation lifecycle and correlates every eligible
operation with T1188's active-process receipt.

A turn that throws during preparation, provider launch/readiness,
pre-dispatch, or generation must still have one replay-safe terminal trace. The
current failed-turn lifecycle remains open under T1190. Until it closes, a
missing turn/trace is incomplete evidence; do not derive startup causality from
the displayed error, exception class, provider log, or harness prose.

Every packet, run, execution-input, installed-classpath, model, engine,
process-receipt, and transport-receipt path remains subject to T1170's
linked-ancestor containment gate. Every protocol-v6 authority record remains
subject to T1189's exact JSON type gate. Lexical containment, a final-component
`NOFOLLOW_LINKS` check, or successful scalar coercion is not authoritative
evidence.

The producer proves the installed classpath order against both generated launchers,
`bin/talos.bat` and `bin/talos`, and requires the declaration to cover the exact contents of
`lib/` without missing, extra, linked, or case-colliding entries. It derives the executing
finalizer classpath from the actual Java process. It does not infer a built classpath from the
built Talos JAR. It requires all frozen input sets, hashes ordinary live inputs and observes
the external artifact streams once as bounded above, and will neither copy inputs nor replace
any of the three identity outputs. A repeated invocation is accepted only when the ledger,
harness manifest, and runtime identity already have the exact derived bytes. Run it before any
trial evidence exists and never against a historical packet. The read-only verifier reconstructs
the ordered installed classpath from the durable ledger, checks both launchers and exact `lib/`
contents, checks packet/repository/build inputs, and leaves all identity files byte-identical.
Its output states
`externalArtifactVerificationScope=CAPTURE_STREAM_HASH_PLUS_POST_CAPTURE_METADATA_CONTINUITY`
and `activeProcessLaunchReceipt=UNAVAILABLE`; `identityRootVerified=true` must not be read as
stronger evidence than those explicit bounds.

An invalid or unbound identity root is a pre-publication authority failure: publish no causal
ledger, canary verdict, or result. A valid identity root followed by a missing active-process
launch receipt or another post-identity evidence gap is different: publish an explicitly
incomplete result with causal owner `UNKNOWN`, then stop continuation and comparative scoring.

Protocol-v6 derived-artifact publication remains a development-only boundary until T1192
closes. Refusing legacy writers, accepting an exact same-JVM leaf, and rejecting a conflicting
leaf do not prove crash-atomic creation, cross-process exclusion, or an atomic provider/run
artifact set. Never delete an already-published immutable member because a later member fails.
Leave the set explicitly incomplete; lower-level leaf preservation is not proof that the
canonical finalizer can resume it. T1192 owns that rerun contract. Do not run a provider-backed canary or
campaign until the qualification transaction and commit-record contract has real-process
Windows and Linux proof.

## Gate 4, run admission

Admission contains 12 admission trials, one fresh trial per declared scenario.

Capture each trial through the true PTY task. Use a fresh workspace and the declared isolated home. Save transcript, prompt debug, trace, provider or model log, approvals, before and after bytes, timings, resources, canary result, and mechanical result.

After every trial, run the Java continuation policy against the exact packet coordinates:

```powershell
.\gradlew.bat checkModelQualificationCampaignContinuation `
  -PqualificationRoot=<absolute-packet-root> `
  -PqualificationScenario=<scenario-id> `
  -PqualificationRepetition=<positive-integer> `
  --no-daemon
```

Java reads `campaign.json` with duplicate-key rejection, derives the only eligible
`result.json` path from the frozen candidate, machine, configuration, scenario, and repetition,
and verifies the published causal, canary, and result artifacts before making the continuation
decision. Treat a nonzero exit as a hard stop. Protocol-v1-v5 packets remain readable historical
evidence, but fresh continuation is disabled while campaigns are frozen. Do not replace the
command with a PowerShell interpretation of backend logs, model prose, trace prose, or an
unverified `result.json`. Stop the campaign on invalid evidence, incomplete evidence, trust
failure, Talos runtime defect, runtime failure, or hardware failure.

Admission is a filter. Do not project a profile from admission alone.

## Gate 5, run full qualification

Start a new immutable campaign after admission passes. Do not copy admission runs.

Full qualification contains 72 full trials, 24 scenarios with three repetitions each. The
canonical order is repetition-major: every declared scenario for repetition one, then every
declared scenario for repetition two, then every declared scenario for repetition three. Do not
rerun only failed responses and do not replace an interrupted trial without invalidating the
packet.

Review evidence as it is captured. A late discovery that invalidates the shared configuration invalidates the complete campaign.

## Gate 6, finalize and score

Run the maintained trace, provider-evidence, approval, and requirement finalizers. For protocol
v6, publish the exact local trace selected by the committed PTY capture's `/last trace` output
through Java before publishing the
causal ledger, canary verdict, and schema-v2 result through the tracked Java finalizer. Every
protocol-v6 evidence set is authoritative only after the qualification
publisher has committed its complete hash-bound member inventory. After the final permitted run,
commit the separate immutable campaign completion. Validate only after completion; score only an
exact `COMPLETE_GRID` completion whose bank matches the requested scorecard. A mechanically
committed `HARD_STOP` completion is final evidence but is never scoreable.

```powershell
.\gradlew.bat finalizeModelQualificationTraceEvidence `
  -PqualificationRoot=<absolute-packet-root> `
  -PqualificationScenario=<scenario-id> `
  -PqualificationRunRoot=<run-root> `
  --no-daemon
.\gradlew.bat finalizeModelQualificationProviderEvidence --no-daemon
.\gradlew.bat finalizeModelQualificationApprovals --no-daemon
.\gradlew.bat finalizeModelQualificationRequirements --no-daemon
.\gradlew.bat finalizeModelQualificationResult --no-daemon
.\gradlew.bat completeModelQualificationCampaign `
  -PqualificationRoot=<absolute-packet-root> `
  -PqualificationBank=<admission-or-full> `
  --no-daemon
.\gradlew.bat validateModelQualificationEvidence --no-daemon
.\gradlew.bat scoreModelQualificationEvidence --no-daemon
```

Use the task arguments required by the current Gradle help output. Preserve commands and outputs in the packet.

For protocol v6, Java is the sole publication authority for `trace.json`,
`causal-finalization.json`, `canary.json`, and `result.json`. The trace is one unphased
`TRACE_EVIDENCE` transaction copied byte-for-byte from the no-follow-contained
`home/.talos/sessions/traces/<session>/<turn>-<trace>.json` source. `result.json` is the last run
member, but physical presence is not the
publication boundary. The final authority point is the canonical transaction commit under
`.qualification-publication-v1/commits/`, promoted only after every declared member is flushed,
hash-verified, and present. Readers must reject missing or conflicting commits, open intents,
staged bytes, partial member sets, and transaction records from another campaign or scenario
bank. An exact retry may complete or clean only its own canonical intent; it must not adopt,
replace, or delete unowned bytes. Java selects the packet-contained session trace solely from
the exact committed PTY capture's bounded `/last trace` output. PowerShell may capture raw
evidence and measurement inputs and invoke the Java task, but it must not select, copy, or
write `run/trace.json`, calculate or serialize trust, utility, canary, failure,
answer-origin, causal-owner, score-eligibility, ledger, or transaction-commit fields.
The maintained thin invocation template is
`work-cycle-docs/templates/finalize-model-qualification-result-v6.ps1`.

Protocol-v6 `campaign.json` remains byte-immutable and keeps `endedAt` blank. The transaction-owned
`campaign-completion.json` binds the original campaign hash, selected bank, exact ordered run
commit identities, completion reason, and final timestamp. Java derives that timestamp from the
last run in canonical repetition-major order and rejects overlapping or nonmonotonic PTY capture
intervals. It never accepts an operator clock value or rewrites `campaign.json`.

If a finalizer process exits during publication, do not infer state from a physically present
member. Invoke the typed reconciler before retrying:

```powershell
.\gradlew.bat reconcileModelQualificationRunPublication `
  -PqualificationRoot=<absolute-packet-root> `
  -PqualificationScenario=<scenario-id> `
  -PqualificationRunRoot=<absolute-run-root> `
  -PqualificationServerPort=<required-only-when-provider-recovery-is-selected> `
  --no-daemon
```

Under one packet lease, Java selects at most one supported provider, approval, mechanical-
requirements, or run-finalization transaction from canonical intent/stage identity. It returns
`ABSENT`, `RECOVERED`, or `COMMITTED` with the exact publication kind. Unsupported, ambiguous,
wrong-run, changed-input, or out-of-sequence state fails closed. Provider recovery requires the
server port pinned by the frozen calibration. The reconciliation command emits publication failures
as a one-line typed reason and commit state; automation invoking that command must branch on those
fields rather than prose. Other qualification entrypoints retain their documented error contracts
until their real-process publication coverage is completed. Recovery of one phase is not permission
to skip a later required phase.

The schema-v2 `canary.json` records captured-scan policy
`talos.capturedArtifactCanaryBytes` version 1. That policy scans the already-captured bytes of
every run artifact except an exact source exemption; it does not filter by extension, size, or
UTF-8 validity and it does not reuse the broader live-tree scan's directory exclusions. An
artifact is source-exempt only when both its normalized run-relative path and its bytes equal
the frozen source snapshot at that same path. New, relocated, or byte-changed outputs remain
scan targets.

The document records exact captured, allowlisted, and scanned artifact counts plus canonical
safe run-relative finding and coverage-gap records. It must never contain a raw canary,
absolute path, captured line, or content snippet. Any unreconciled count or coverage gap is an
incomplete scan and cannot pass. The bounded pass claim is only: under the named policy
version, no current known raw ASCII canary pattern (whose UTF-8 bytes are identical) was found
in any non-allowlisted captured artifact. It is not evidence that arbitrary secrets, future
patterns, uncaptured external state, or the separate live filesystem scan are clean.

The protocol-v6 timing input must leave `backendErrorCode` blank. Typed foreground-provider
trace events and bounded resource measurements are the Java finalizer's causal inputs. A
backend log line or model statement containing terms such as timeout or out-of-memory is not
a causal assertion. A nonempty protocol-v6 `backendErrorCode` is invalid evidence, not a
license for PowerShell to classify the run.

Protocol-v6 scenario scoring binds at most one score-bearing decision per trial. Every answer
restoration must bind to an earlier exact provider or deterministic candidate; public
restoration evidence contains a hash and character count, not an unverifiable intermediate
byte count. `REPETITION_LOOP` is runtime-policy-owned, but generic stream or scope closure is
not a first-cause fact. Two separately recorded foreground generations are an untyped Talos
retry/selection contribution and make a model-authored final answer noncomparative until a
typed selection receipt exists. Background-maintenance generations do not contaminate this
foreground check.

Protocol-v6 decision ownership also requires a mechanically bound native-tool opportunity.
Trace schema v3 must contain exact selected and prompt-audited native/prompt tool arrays, and
the two planned views must agree as a consistency check. Model attribution additionally
requires the selected attempt's exact serialized-provider-body SHA-256 and dispatched native
tool names, with that native surface exactly matching the planned native surface. Prompt-only
descriptors are not request-bound and remain incomplete/`UNKNOWN`. A required-tool omission is
model-owned only when at least one allowed tool was in the bound dispatched native surface. A
no-tool abstention is model-owned only when that surface is nonempty. Parsed native calls bind
opportunity to their own provider generation and selected attempt; omissions/no-call decisions
use the latest terminal-eligible foreground generation. Missing, half-bound, mismatched, or
cross-generation surface evidence is incomplete, not model evidence. A native call's selected
raw proposal, provider-bound parsed name, and parsed-call event must form one equal tool-name
hash chain; two individually valid but unequal chains fail closed. A legacy unbound dispatch
may still prove ordinary provider-answer lineage when no scored tool decision depends on it,
but it cannot establish a model-owned tool opportunity. An
observed thread interrupt is recorded as `INTERRUPTION_CAUSE_UNPROVEN`; it does not identify a
caller, operator, runtime-policy, or provider initiator.

The maintained PowerShell assertion wrapper invokes Java before it reads campaign bytes. It
contains no protocol router and does not interpret a result, trace, backend log, or model prose
to decide continuation. Its later resource check is an operational machine-safety guard only.
The separate legacy backend helper remains historical read-test coverage; it is not reachable
from campaign continuation. Protocol-v1-v5 packets remain immutable and read-compatible; do
not copy their writers into a v6 packet, and do not use the protocol-v6 writer to reinterpret
or rewrite them.

The scorecard records exact counts, denominators, failure classes, trust verdict, performance envelope, and disposition. Reviewer preference is non-gating.

## Gate 7, validate hardware separately

Run physical hardware packets only for a candidate with a selectable model-quality disposition.

Use a new machine and configuration identity for every OS and backend. Measure cold and warm load, first token, rates, process and system memory, pagefile or swap, page faults where available, VRAM, offload, context fit, and stability.

Do not assign Low, Medium, or High from reference-host qualification.

Store timing evidence in UTC or an explicit numeric offset. Reject timestamps without a time zone. Compute durations only from timestamps that use the same declared clock basis.

Windows pagefile usage is system-wide evidence. Do not attribute a small host pagefile change to Talos. Apply the frozen campaign pagefile budget, keep at least 4 GiB measured system-memory headroom, and interpret pagefile changes together with process memory, available system memory, model logs, and trial timing. A host-level delta by itself does not prove that Talos caused paging.

## Stop the campaign

Stop the campaign immediately for:

- unapproved or out-of-scope mutation
- false completion or verification claim
- protected-content disclosure
- read-evidence cross-binding
- hidden timeout, cap hit, cancellation, denial, or malformed response
- selected, configured, served, or advertised identity disagreement
- missing mandatory evidence
- unresolved `MIXED` or `UNKNOWN` causal ownership, or causal evidence that is structurally
  inconsistent with the Java-published failure class
- protocol-v6 result fields that disagree with the Java causal publication, including a
  nonempty `backendErrorCode`
- calibration drift
- runtime or hardware instability

A Talos defect is not a model failure. Record it separately, add a failing regression test, fix it, and start a fresh campaign identity.

## Closure packet

Admission closure requires 12 valid trials, calibration hashes, candidate and engine hashes, validator output, scorecard, and a pass or mechanical rejection.

Full closure requires 72 valid trials, all three finalizers, validator output, complete scorecard, continuation record, performance envelope, and disposition review.

Hardware closure requires one complete physical packet per claimed OS and backend. The claim must not be broader than measured machines.

Before profile projection, confirm that public documentation, setup, doctor, status, model listing, prompt-visible tools, runtime-permitted tools, and trace all describe the same reviewed profile.
