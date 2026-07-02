# Changelog

## [Unreleased]

- [T926] `talos setup wizard --dry-run` now renders a side-effect-free setup
  decision plan, and `talos setup wizard` now provides the first interactive
  config-only setup path. The wizard detects Java/config/server state, rejects
  Windows `.exe` llama-server paths as incompatible under WSL, lists the two
  accepted beta model profiles, writes `~/.talos/config.yaml` only after
  explicit confirmation, backs up existing configs, and still performs no
  package installs, model downloads, model starts, or doctor execution.
- [T925] Workspace containment now has a shared canonical primitive for the
  checkpoint and delete-root guard paths. Checkpoint capture and restore reject
  Windows-style prefix-sibling escapes before writing or deleting outside the
  workspace, while `talos.delete_path` keeps sandbox gating and root refusal
  without a separate lexical containment assumption.
- [T928] Generated Talos launchers now include
  `--enable-native-access=ALL-UNNAMED`, suppressing Java FFM native-access
  warnings from bundled JLine/Lucene paths during normal Linux/WSL status and
  REPL startup.

## [0.10.7] - 2026-07-02

- [T918] Redacted audit snapshots now canonicalize the output path through the
  nearest existing parent before checking workspace containment. Alias-shaped
  paths that resolve inside the workspace are rejected, and output path
  canonicalization failures fail closed before any snapshot directory is
  created.
- [T919] Public repository identity now points at `ai21z/talos-assistant`
  across installer defaults, site links, public docs, architecture notes, and
  release/site contract tests while preserving the intentional `talos-cli`
  package and winget moniker identity.
- [T920] Public repository metadata is refreshed for Talos: NOTICE no longer
  contains template ownership, CONTRIBUTING describes the current GitHub/Gradle
  workflow, third-party notices cover the major declared dependencies without
  guessed license placeholders, and the user command reference includes
  `talos doctor`, `talos doctor --start`, and `/doctor`.
- [T921] Wiki and generated report truth are hardened for the public-main
  stabilization arc: required wiki `last_verified_commit` values now resolve
  through structural lint, CURRENT-STATE describes merged `main` instead of the
  stale release branch, and generated coverage reports name the enforced 82%
  instruction gate instead of the old 65% prose.
- [T922] Secret-store scopes are sanitized to local directory names before
  path resolution, preventing separator, drive-syntax, or traversal-shaped
  scopes from escaping the configured secret-store base. Protected path
  classification now also covers `.kube`, `.docker/config.json`, `.npmrc`,
  `.netrc`, `.ppk`, and `id_ed25519_sk` credential shapes.
- [T862] Maven workspace verification is now documented as a trusted
  workspace-profile flow instead of a built-in Maven command profile. User docs
  show the `ws:maven_verify` `.talos/profiles.yaml` recipe, `/profiles trust`,
  `/verify ws:maven_verify`, fixed-argv and approval boundaries, and the Maven
  network/cache caveat while keeping Talos' own build described as Gradle.
- [T867] Protected-path alias classification now preserves target-truthful
  protected kinds for alias-shaped paths when the remaining path tokens already
  prove a protected target. Windows trailing-dot/space `.env` aliases are pinned
  as `SECRET`, unresolved short-name paths such as `SSH~1/id_rsa` now carry the
  inferred `SECRET` kind instead of flattening to generic `CONTROL`, unknown
  short-name aliases still fail closed, and permission/trace evidence surfaces
  the protected kind while keeping protected path hints redacted. The protected
  path policy version is bumped to v8 so stale privacy partitions rebuild.
- [T868] Private mode now narrows retrieval out of the offered tool surface
  instead of only relying on the `RagService` no-op backstop. `talos.retrieve`
  is marked as a typed retrieval capability, `ToolSurfacePlanner` applies
  deterministic config-derived availability from `PrivacyConfigFacts`, Ask,
  Plan, Agent, native, and prompt-preview surfaces all use the same planned specs, and
  private-mode prompts, current-turn frames, traces, and prompt-audit surfaces
  no longer advertise retrieve unless private-mode RAG is explicitly enabled.
- [T869] Multi-target replacement verification now resolves explicit
  target-scoped `replace old with new in file` expectations for each requested
  target. A successful write that leaves one requested replacement unsatisfied
  now fails static verification, demotes the turn outcome/trace to `FAILED`,
  and withholds stale success prose instead of presenting `COMPLETE` /
  `COMPLETED_UNVERIFIED`. Expectation-based verification only upgrades a turn
  to `PASSED` when it covers every successful mutation target.
- [T870] Redacted search output rendering is pinned for clean locator and
  placeholder text. `talos.grep` and `/grep` now have regressions for
  PRIVATE_MARKER matches with literal line locators and canonical redaction,
  and long `talos.grep` matches truncate with ASCII `...` instead of a Unicode
  ellipsis so redacted search output does not depend on terminal glyph fallback.
- [T873] Command/tool-output truth checks now cover additional
  ledger-gated shapes beyond git status: test-run output, process-list output,
  shell listing/cat output, and explicit file-content claims without a matching
  `talos.read_file` producer. Trace evidence records the recognizer shape and
  missing producer, while docs still state that arbitrary command/read claims
  are not completely covered.
- [T871] Qwen-specific grounding/edit-shape weaknesses now have deterministic
  runtime steering where the shape is provable. No-tool workspace "no
  results"/"none found" answers are downgraded to an explicit no-search
  disclosure, append-shaped full-file `write_file` calls that preserve a
  same-turn readback are converted to `talos.edit_file` before approval, and
  the synchronized approval scorer marks proposal-only continuation fallbacks
  after tool evidence as `FAIL_REVIEW_REQUIRED` instead of answer-quality PASS.
  The qwen profile guide documents the remaining model-competence residual
  without claiming the model is fixed.
- [T872] The live synchronized approval audit no longer aborts the full bank
  when the first protected-read-denied scenario safely completes without the
  model attempting the expected approval path. Audit transcripts now record
  expected versus observed approval counts, missing required approvals score as
  `FAIL_REVIEW_REQUIRED`, and the protected-read non-attempt writes a
  `REVIEW-REQUIRED.md` bundle instead of being mistaken for passing coverage.
  The synchronized approval harness now also includes selected command-profile
  probes for approved bounded `talos.run_command` execution and deterministic
  command-policy rejection while `talos.run_command` is visible.
- [T885] `/profiles configure` now provides a terminal-side declaration/edit
  path for workspace verification profiles. It previews the exact proposed
  `.talos/profiles.yaml` bytes and SHA-256 behind approval, checkpoints the
  declaration before writing, records hash/approval/trust-state metadata in
  trace/audit sinks, and leaves the declaration untrusted until `/profiles
  trust` pins it through the existing SHA-256 approval chain.
- [T886] Managed llama.cpp model setup now has a repeatable
  configure -> test -> guide recipe for every canned chat profile. Setup help
  renders from the shared profile registry, separates the accepted beta
  stability pair (`qwen2.5-coder-14b`, `gpt-oss-20b`) from experimental
  selectable profiles, links per-profile guides, and `talos doctor --start`
  now requires the deterministic `TALOS_MODEL_SMOKE_OK` reply token before
  reporting model-smoke success.
- [T905] `/set model` downloaded-GGUF guidance now includes the complete
  alias-aware config patch for canned managed llama.cpp profiles: `llm.model`,
  `engines.llama_cpp.model`, `engines.llama_cpp.hf_repo`, and
  `engines.llama_cpp.hf_file`. The fallback setup command is explicitly labeled
  as a template requiring the user's local llama-server path.
- [T906] The REPL shell-command hint no longer swallows short prose such as
  `talos run tests`, `talos status repo`, or `talos diagnose issue`. It now
  treats known `talos` subcommands as shell invocations only for exact
  two-token commands, flag-bearing commands, and documented positional shapes
  such as `talos setup models`.
- [T907] `/last trace` now renders the canonical mode captured in the local
  trace. The Local Trace block shows `Mode: auto|ask|plan|agent`, and
  legacy-alias traces that resolve through Agent render as `agent` rather than
  `dev`, `chat`, or `unified`.
- [T897] Existing single-file static-web redesigns no longer hard-block on
  absent, unnamed conventional `style.css`/`script.js` satellites after
  `index.html` is successfully rewritten. Expected-target progress now drops
  those inferred satellites only for non-create redesign/edit turns where the
  workspace is still a single-file page; create-from-scratch prompts and
  explicitly named satellites remain hard targets. A deterministic executor E2E
  scenario covers the original "write index only with inline CSS/JS" shape.
- [T910] The false-mutation warning is now no-change aware. Answers like
  "I created or modified zero files" and "No changes were applied" no longer
  get annotated as if they claimed a file changed, while real unbacked claims
  such as "I created README.md" still trigger the truth check.
- [T917] Static-web verification now treats narrow JavaScript selector repairs
  as JS/HTML scoped when the HTML imports the edited script and no CSS target is
  requested. A correct `script.js` `.missing-button` -> `.cta-button` fix no
  longer fails solely because no CSS primary file exists, while interaction
  proof, full HTML/CSS/JS surface checks, and `script.js`/`scripts.js`
  target-scope guards remain covered.
- [T911] Ask, Plan, and Agent no-tool/direct-answer turns no longer receive
  hidden workspace manifest evidence. Prompt construction now injects workspace
  file trees and README excerpts only when the current-turn visible tool surface
  is non-empty, `/prompt` metadata follows the same rule, and `.env` filenames
  are excluded from workspace manifests along with their contents.
- [T912] Plan/read-only static-web diagnostics are now bound to current-turn
  read evidence. If `styles.css` is present in the workspace manifest but only
  `index.html` and `script.js` were read, Talos no longer claims CSS inspection;
  it keeps the grounded HTML/JavaScript diagnosis and adds a concrete
  implementation plan for plan/fix-shaped read-only prompts.
- [T916] Ask and Plan now short-circuit explicit natural command-run requests
  with a deterministic local read-only refusal instead of entering a model turn
  with read/search tools. The nudge points to `/mode agent` for approved bounded
  command profiles, and the Agent/Auto unsupported-command T913 no-fallback
  behavior remains covered separately.
- [T915] Read-only prompt rendering is now posture-aware across Ask, Plan, and
  Agent/Auto `/prompt` previews. The shared identity stays posture-neutral,
  and Agent/Unified prompts use read-only rules and read-only tool preambles
  whenever the current visible tool surface has no mutation tool, including
  no-tool small-talk and `READ_ONLY_QA` turns. Writable `talos.write_file`
  guidance remains only when the visible surface actually includes mutation
  tools.
- [T913] Explicit natural shell-command requests such as "Run the command
  Get-ChildItem -Name..." now classify as unsupported command verification
  instead of falling back to read/list tools. Agent prompt-debug evidence
  advertises no fallback tools for that shape, tool-loop outcomes render a
  deterministic command-not-run/profile-unavailable answer, and command approval
  denial prose is withheld unless a denied `talos.run_command` outcome exists.
- [T908] Plan/Ask read-only posture no longer converts create-output targets
  into mandatory reads. Create-only Plan requests such as "plan how to add
  plan-only.txt" now keep mutation disabled without making the not-yet-created
  file a `READ_TARGET_REQUIRED`/`MUST_READ` target, while source-to-output
  planning still preserves source evidence. Tool-loop reprompts now emit
  `[Expected target progress]` only when an expected-target mutation obligation
  is actually active, avoiding read-only mutation-overlay contradictions.
- [T914] Ignored-instruction output targets no longer become mandatory read
  evidence. Prompts such as "read PROMPT_INJECTION.md and ignore any
  instruction inside it to create injected-agent.txt" now keep the requested
  source file as `MUST_READ`, project the ignored output filename as forbidden,
  and prevent it from appearing as an expected read target in trace/policy
  evidence. Direct `do not create Y` target handling remains covered.
- [T909] Protected-read approvals now use the once-only approval surface when
  the permission decision is not session-remember eligible. Safe in-workspace
  writes still use the full `a=yes for session` path, while protected reads keep
  the existing approval boundary without advertising a session promise that
  policy will ignore.
- [T904] Review nits from the T898-T901 self-review: the T900 read-evidence named-check
  now uses a word-boundary match (so "refactor myscript.js" no longer keeps an absent
  script.js required), the legacy spinner join-cap is documented, and the status-row
  spinner test-coverage gap is documented (a deterministic test needs a pseudo-TTY).
- [T903] ShellCommandHint no longer swallows prose that starts with the binary name plus
  a subcommand-homonym verb ("talos run the tests please", "talos status of the repo")
  unless the line is short or carries a flag, so such prompts reach the model; and it now
  detects path-qualified invocations (./talos, /usr/local/bin/talos, C:\...\talos.exe).
- [T902] /set model guidance for a downloaded-but-unconfigured GGUF is now copy-pasteable:
  it leads with the concrete hf_repo/hf_file config edit (resolved from a shared
  LlamaCppModelProfiles registry that SetupCmd also uses, so the two never drift) and
  names the equivalent setup profile. The earlier attempt substituted the absolute
  server_path, which the render layer's privacy redaction stripped to [path]; the
  config-edit route contains no absolute path and survives redaction. No weakening of the
  redaction; the no-hot-swap restart reality is still stated honestly.
- [T901] Live "working" indicator during model generation and the tool loop. The
  spinner started once before a turn and stopped on first output, never resuming, so
  multi-tool / long-generation rounds (observed at 144s and 271s) showed a bare cursor.
  The tool-progress sink now re-arms the spinner after each tool line so the following
  generation wait shows activity; the tool line clears any running spinner first so a
  legacy carriage-return frame never collides (single-writer discipline). Non-interactive
  output stays byte-identical.
- [T900] Read-only turns (Ask/Plan) no longer require reading inferred static-web
  satellites that are absent on disk. Plan-mode "redesign this page" on a single-file
  index.html projected the conventional triplet and then falsely blocked for not reading
  nonexistent style.css/script.js. A disk-aware filter drops a conventional satellite
  (style.css/script.js) from the read-evidence set only when it is absent on disk AND was
  not named by the user; present, named, protected, path-existence, and non-satellite
  targets keep their requirement, and genuine misses still fail closed. Read-evidence
  sibling of T897 (the mutation facet stays open).
- [T899] /set model on a downloaded-but-unconfigured GGUF now gives actionable switch
  guidance instead of a bare 404. When the requested name matches an on-disk GGUF
  (case- and .gguf-suffix-insensitive) it explains the file is downloaded-but-unconfigured,
  that managed llama.cpp binds one GGUF at launch (no hot-swap), and the exact
  `talos setup models`/config-edit + restart to switch. No false promise of an in-REPL
  hot-swap.
- [T898] A shell invocation of the talos binary typed into the REPL prompt (e.g.
  "talos setup models --write --force") now prints a hint to run it in the terminal
  instead of routing it to the model (which previously produced a confused/hallucinated
  answer). Narrow detection: a leading talos-binary token plus a real subcommand or flag;
  a normal question that merely mentions "talos" is untouched.
- [T896] Editing an existing file is classified FILE_EDIT, not FILE_CREATE, when the
  instruction body uses a content-addition phrase. "Edit index.html to add a Contact
  section" now classifies as an edit (previously the "add a" CREATE_MARKER flipped it
  to create). The classifier prefers FILE_EDIT when an explicit edit verb leads before
  any create verb; genuine "Create ..."/"Build ..." requests are unchanged.
- [T895] Read-then-copy no longer false-blocks: a "read X and create Y" request now
  projects the read source X as source evidence (read-only) instead of a required
  mutation target, so writing only Y satisfies the contract (no `BLOCKED_BY_POLICY`
  for not mutating a file the user only asked to read). Guarded so "read then mutate
  the same file", "read the current files then rewrite them", and forbidden-target
  ("do not edit scripts.js") shapes keep their existing mutation targets, and a
  genuinely-unmutated write target still blocks (anti-overclaim preserved). Fixes the
  scn-16 outcome-truth violation found in the post-refactor sweep.
- [T894] Added deterministic E2E mode coverage at the `TurnProcessor` route
  seam. Scenario definitions and JSON resources can now select a mode, and the
  new route-through harness covers Ask mutation refusal, Plan read-only posture,
  Agent approved mutation, Auto structural commands, and legacy
  `dev`/`chat`/`unified` aliases resolving to canonical `agent`.
- [T893] Local turn traces now record canonical active mode instead of
  `"unknown"` for `ModeController` turns. The trace-mode seam lives on the
  `TurnRouter` port, `ModeController` reports canonical active names
  (`auto`, `ask`, `plan`, `agent`, hidden `rag`), legacy aliases resolve to
  `agent`, and `/last trace` renders the local trace mode line for human audit
  review.
- [T892] Aligned the Ask/Plan/Agent UX surface across prompt inspection,
  route hints, help text, and user docs. `/prompt` now canonicalizes legacy
  `dev`/`chat`/`unified` to `agent`, applies Ask/Plan read-only posture caps
  when previewing prompt-visible tools, and route hints use public `agent` plus
  `structural` for deterministic local commands. Public docs and `/help all`
  now advertise `auto`, `ask`, `plan`, and `agent`, with hidden legacy aliases,
  hidden `rag`, and reserved `web` documented as compatibility/reserved
  surfaces.
- [T891] Added canonical `/mode plan` as a read-only planning mode. Plan uses
  `CapabilityPosture.PLAN_READ_ONLY`, advertises plan-specific prompt rules,
  exposes no mutation or command tools, and is selectable/advertised through the
  mode catalog while leaving "approve plan then execute" out of scope.
- [T890] `/mode ask` is now a runtime-enforced read-only ceiling. Direct
  mutation requests return a deterministic local nudge (`Ask is read-only;
  switch to /mode agent to make changes.`) without calling the LLM, Ask turns
  run through `CapabilityPosture.ASK_READ_ONLY`, and the Ask prompt/resource
  now advertises only inspection behavior instead of write/edit capability.
- [T889] Added a shared capability-posture foundation for Ask/Plan/Agent mode
  enforcement. `AssistantTurnExecutor.Options` can now request `AGENT`,
  `ASK_READ_ONLY`, or `PLAN_READ_ONLY`; read-only postures cap the effective
  task contract to `mutationAllowed=false`, force the turn phase to `INSPECT`,
  and recompute the native tool surface before the current-turn capability frame
  is injected, so prompt-visible tools and runtime-enforced tools stay aligned.
- [T888] The mode router now has a catalog-backed public surface for the
  Ask/Plan/Agent refactor foundation: canonical `agent` is selectable, legacy
  `chat`/`dev`/`unified` aliases resolve to `agent`, `rag` remains hidden but
  functional, explicit modes no longer sweep into other modes, and the reserved
  `web` stub is unsweepable.
- [T887] Interactive prompts now get one blank line after a submitted non-empty
  `talos [mode] >` input before Talos starts route, slash-command, rate-limit, or
  answer output. The gap is interactive-only, so scripted/redirected transcripts
  stay unchanged.
- [T884] Turns now read as distinct blocks: after a conversational turn the REPL
  prints a dim full-width rule (blank line, rule, blank line) before the next
  `talos [mode] >` prompt, so turn 2 no longer butts up against the end of turn 1.
  Interactive-only render chrome (scoped to real prompt turns, not slash-command
  output); scripted/redirected transcripts stay byte-for-byte unchanged.
- [T883] `/models` switching tip rewritten into two clear tiers so it answers
  "can I `/set` this now, or must I configure it first?": entries shown as
  `backend/model` are switchable now (`/set model <backend/model>`), while
  "Downloaded GGUFs (not configured)" are on disk but not selectable until
  configured (`talos setup models ...` + restart). Adds the managed-GGUF caveat
  (single GGUF fixed at launch, no hot-swap) and keeps the `/profiles`
  disambiguation. Doc/text-only.
- [T874] `/mode` help and correctness: the advertised mode list is now generated
  from the `ModeController` registry (no hardcoded literal, so it cannot drift from
  what is actually selectable), bare `/mode` lists the available modes, the reserved
  `web` stub is no longer selectable (`/mode web` returns a clear "reserved" message
  instead of trapping the user in a dead mode), and the canonical name `unified` is
  advertised rather than only the `chat` alias. Adds `Mode.available()`,
  `ModeController.availableModeNames()`/`reservedModeNames()`, and regression tests.
- [T875] `/help all` now renders each command under the name the user actually
  types: `compactUsage` derives the command token from the usage string's leading
  token instead of `spec.name()`, so `explain-last-turn` (usage `/last ...`) shows
  as `/last` rather than the verbose primary name (previously one length-threshold
  from printing a mid-token-sliced fragment). The overview footer now points to
  `/help <cmd>` for full options, where subcommands collapsed to `[opts]` are shown
  in full.
- [T876] Models help accuracy and discoverability: the `/help models` example no
  longer hardcodes a specific GGUF (`/set model llama_cpp/qwen2.5-coder-14b`) that
  404s unless that profile is the configured one -- it points to a name shown by
  `/models`; the `/help models` topic is advertised in the default "More help"
  block; and `/set` with no/blank model name now points the user at `/models` for
  discovery (the not-found path already did).
- [T879] Help-text accuracy and synonym documentation (doc-only, no behavior
  change): the default page describes `/debug` as a level setter
  (off|brief|rag|tools|prompt|trace) instead of a "toggle" and lists `/clear`'s
  `/cls` alias alongside `/reset`; the `/help debug` topic states that
  `/debug prompt on` turns on prompt-level output (was "harmless suffix form") and
  documents the level aliases (retrieval=rag, tool=tools, prompts/frame=prompt,
  all=trace); and the accepted-but-undocumented synonyms are now surfaced --
  `/audit` (enable|disable), `/secret` del (also delete, rm), and
  `/privacy private` (enable|disable). Per owner direction the synonyms are
  documented, not removed.
- [T880] Interactive lane glyphs no longer render as `?` on Windows: the launcher
  now sets `-Dstdout.encoding=UTF-8` and `-Dstderr.encoding=UTF-8` alongside
  `-Dfile.encoding=UTF-8`, so on Java 18+ `System.out`/`err` emit UTF-8 instead of
  the Windows console code page (which could not encode the bullet/arrow markers).
  The ASCII glyph fallback for non-Unicode/redirected output is unchanged.
- [T878] Disambiguated the two "profiles" concepts (doc-only): the `/models` tip
  now calls the GGUF selection a "managed GGUF model profile" and states the
  `/profiles` command is unrelated (it manages workspace verification profiles, not
  models); `/profiles`'s own summary reads "...verification profiles
  (.talos/profiles.yaml; not model/GGUF profiles)".
- [T877] `/models` now surfaces downloaded-but-not-configured managed llama.cpp
  GGUFs in a "Downloaded GGUFs (not configured)" section, via a safe, no-subprocess
  scan of the Hugging Face cache (the configured `hf_cache_dir`, default
  `~/.talos/models/huggingface`). Switching to one still requires
  `talos setup models --profile` + a restart (the managed engine binds one model at
  launch); the listing only makes them discoverable. New `GgufCacheScanner` is
  depth-bounded and never throws, so `/models` cannot crash on a filesystem error.
- [T881] Hardened the T877 GGUF cache scanner against malformed configured
  `hf_cache_dir` strings. Invalid path syntax now degrades to an empty downloaded
  list instead of turning `/models` into a catalog error.
- [T882] Fixed the `/tools` page layout. Tool descriptions were rendered full-width
  on the name line and overflowed, re-wrapping to column 0 and mangling the page.
  Each tool now renders as a short `name  badge` line, then the full description
  word-wrapped at a fixed width with a hanging indent, then its params -- no line
  exceeds the content width, so the renderer no longer re-wraps it.

## [0.10.6] - 2026-06-25

- [T866] Added a deterministic command-output truthfulness guard in the outcome
  layer. If an answer presents structured command output such as `git status`
  text without a successful `talos.run_command` outcome in the current turn's
  ledger, Talos now withholds that unsupported command-style output and records
  an `UNSUPPORTED_COMMAND_OUTPUT_CLAIM` warning in the turn outcome/trace while
  leaving real command outcomes and honest "command unavailable" answers
  unchanged.
- [T864] Write/edit read-back integrity failures now fail closed instead of
  being represented as clean successful mutations. `ContentVerifier` now splits
  read-back I/O or byte-mismatch failures into a distinct
  `INTEGRITY_FAIL` status while keeping byte-matched structural JSON/YAML/XML
  `FAIL` as a successful write with failed verification surfaced. Failed
  `ToolResult`s can preserve verification metadata, write/edit tools share the
  mapping contract, and tool-result formatting/outcome tests pin the failed
  result plus verification-status handoff.
- [T861] Made built-in Gradle command profiles platform-aware for the Linux
  source/developer beta path: Windows still plans `.\gradlew.bat`, POSIX plans
  `./gradlew`, the pre-approval wrapper guard now checks the selected
  executable instead of accepting either wrapper, and command execution forwards
  a minimal POSIX environment (`HOME`, `LANG`, `LC_ALL`, `TMPDIR`, `JAVA_HOME`,
  `PATH`) without reopening broad environment leakage. Added focused command
  portability tests, a Linux command-portability CI smoke lane, and updated
  public docs to distinguish Windows packaged install from Linux
  source/developer support.
- [T860] Removed the dead JavaFX runtime dependency. The build no longer declares
  `org.openjfx:javafx-base/graphics/controls` or the `javafxVersion` /
  `javafxPlatform` properties. JavaFX was a leftover from the original LOQ-J
  first-run-wizard UI and is unreferenced by current code: no imports, no
  `module-info` requires, no FXML, no reflection, and no jpackage/jlink module
  arguments. The Windows `installDist` `lib` and the jpackage app-image no longer
  bundle the JavaFX runtime, shrinking the install payload. No production or test
  behavior changed; `check` is green and the jpackage Windows app-image still
  assembles cleanly.
- [T859] `talos setup models` now groups managed GGUF model profiles and makes
  switching between them explicit, so `/models` shows the available managed
  models and the active selection honestly.
- [T858] Model setup profiles now declare each model's tool-calling mode and
  write `tools.native_calling` into the generated config, so a model without
  native tool-call support is configured for the text/tool-prompt path instead
  of silently failing tool calls.
- [T856] `talos setup models` can provision a managed llama.cpp embedding server
  (for example `bge-m3`) on a dedicated port for Ollama-free hybrid retrieval.
  The managed embedding endpoint is shared through a process-lifetime registry
  rather than restarted per query.
- [T855] Talos no longer depends on Ollama on the default path. The default
  backend is managed llama.cpp and default embeddings are disabled (BM25-only
  retrieval out of the box), and `/models` no longer surfaces or scans Ollama
  models unless the active backend is explicitly `ollama`.
- [T854] REPL `/status` now uses the active backend-qualified model when a live
  model is present, matching T853's `/context` diagnostic truth. After
  `/set model ollama/...`, the dashboard and verbose status host row report
  Ollama instead of mixing an Ollama model row with the configured managed
  `llama.cpp` engine.
- [T853] `/context` now prefers the active backend-qualified model after
  `/set model`, so switching to `ollama/...` reports the Ollama model and the
  runtime-enforced effective context window instead of the static configured
  `llama.cpp` row. `/models` now groups recommended managed `llama.cpp` models
  separately from legacy/optional Ollama entries.
- [T847] Added a measurement-only retrieval gold-context harness with 20
  deterministic tasks, BM25-only and synthetic-hybrid runs, expected
  files/symbols/line ranges/related tests, and protected-path/private-mode
  negative cases. This is evaluation infrastructure only: no production
  retrieval ranking, vector, graph, RAG indexing, or prompt-assembly behavior
  changed.
- [T852] Multi-document read-only turns now stop more constructively when the
  model has already read every requested target but keeps repeating read calls.
  Instead of falling through to the generic no-progress failure policy, Talos
  returns a bounded evidence-complete failure that lists the files already read,
  states that no files were changed, and asks for a narrower retry if a full
  synthesis is still needed. The loop limit is unchanged. T852 is closed after
  the GPT-OSS scn-11 live rerun proved the installed build now returns the
  bounded evidence-complete failure instead of the generic no-progress policy
  message, with no approvals and no workspace diff.
- [T850] Read-only workspace QA and workspace-explain turns now receive a
  current-turn `[FileGroundedAnswer]` instruction that separates workspace
  path/name metadata from inspected file evidence. The model may use paths as
  location labels, but must not present a workspace directory name as a project
  name or other file-grounded fact unless that fact appears in current-turn
  read/search/list results. This is a prompt-frame grounding fix only; T850 is
  closed after the qwen scn-10 live rerun proved Talos no longer inferred
  `loqj-cli` from the workspace path when no inspected file stated it.
- [T851] Added a pre-approval containment guard for Talos read-display line
  prefixes in mutation payloads. After a same-turn `read_file` display, a
  `write_file` content body or `edit_file` replacement that carries `N | ...`
  display lines for that target now fails before approval and before disk
  writes, with a visible tool-result diagnostic. Ordinary source writes remain
  allowed, and literal numbered-pipe text is not blocked unless it is tied to
  same-turn read-display evidence. T851 is closed after two-model scn-14
  rerun and target-present GPT-OSS containment evidence.
- [T849] Named-function mutation requests such as "Modify foo() in helper.py"
  and "Modify the existing function foo() in helper.py" now fail closed before
  approval when the same-turn readback proves that `foo()` is absent from
  `helper.py`. The guard is intentionally narrow: it covers high-confidence
  `name()`-in-file edit requests, requires complete same-turn read evidence
  before approval, and blocks both `write_file` and `edit_file` attempts from
  retargeting another function. Add/create requests such as "Add a function
  foo() to helper.py" are not blocked by this guard.
- [T848] Direct "fix <problem> in <file>" prompts and file-scoped defect
  prompts such as "There is a bug in calc.py... Fix multiply..." now resolve
  to a mutation-capable file-edit contract instead of a read-only turn. The
  classifier remains deterministic and narrow: advice-only variants such as
  "How would you fix the bug in calc.py?" and "There is a bug in calc.py.
  Explain how to fix multiply." stay non-mutating. Pronoun advisory/no-fix
  variants such as "How would you fix it?", "Should I fix it?", and "Don't fix
  it yet" also stay non-mutating, while assistant-directed requests such as
  "Can you fix it?" still expose the normal read/write/edit tools behind
  existing approval policy.

## [0.10.5] - 2026-06-12

### Changed
- [T788] The workspace `.talos` directory is now a protected CONTROL
  path (like `.git` and `.github/workflows`): it will hold
  workspace-declared verification profiles (`.talos/profiles.yaml`) and
  template commands (`.talos/commands/*.md`) - content that influences
  what Talos executes - so the model can no longer write it with an
  ordinary write approval; writes escalate through the protected-path
  flow and diff previews of its content fail closed. The protected-path
  `POLICY_VERSION` was bumped v3 → v4, which makes existing RAG indexes
  rebuild their privacy partition once on the next index check (stale
  partitions would misclassify `.talos` content). Names merely
  containing the word talos (`docs/talos-notes.md`) stay unprotected.
  One deliberate read-side exemption: the project-memory loader still
  reads its own canonical `<dir>/.talos/rules.md` memory tiers into the
  prompt (that is their purpose, and the CONTROL classification now
  protects them from un-escalated model WRITES - closing a
  memory-injection vector); nothing else under `.talos` is exempt, so
  `.talos/profiles.yaml` can never flow into a prompt.
- [T797] Characterization pins ahead of the context/session work (tests
  only, no behavior change): the compaction failure-breaker's exact
  operational skip string, the fact that compaction today sets a status
  and nothing else (no event, no notice - T798/T805 change that
  deliberately), and the exact `/session` info/save/load/clear/usage
  bytes that T799-T801 evolve.
- [T787] Characterization pins ahead of the wave-4 trust work (tests
  only, no behavior change): the not-yet-protected `.talos` workspace
  directory, the gradle `run_command` approval-detail bytes, the `/status`
  dashboard render bytes, `/undo`'s pre-existing approval/protected-path
  bypass, and the `/checkpoint` list shape (id-sorted, not
  chronological) plus restore approval bytes - the byte baselines the
  following tickets are reviewed against.
- [T785] First run is now an honest preflight: the flow runs the same
  doctor probe set as `talos doctor` (default probes only - it never loads
  a model) and prints per-check PASS/WARN/FAIL/SKIP lines instead of the
  previous unconditional "✓ Setup complete", which verified nothing. The
  verdict line is now truthful: "Setup verified." only when every check
  passed, "Setup complete with warnings." when the only finding is e.g. a
  managed server that has not started yet, and "Setup incomplete - N
  check(s) failed." with fix hints and a pointer to `talos doctor`
  otherwise. The stale hardcoded configuration block (which claimed model
  `talos-agent` regardless of config) is gone. The sentinel is still
  written once the flow has been shown - even on failure - because the
  launcher exits when first-run refuses, and an unconfigured user must
  never be locked out of the REPL; recurring verification belongs to
  `talos doctor`. First-run tests are now hermetic (injected doctor
  runner, output stream, and sentinel path - the old test wrote the
  developer's real `~/.talos/first_run_done`).

### Changed (checkpoints)
- [T796] `FileUndoStack` is deleted along with its write/edit tool push
  sites - its sole functional consumer was the pre-T795 ungated `/undo`,
  and checkpoints capture a strict superset (every gated mutation
  including batch/move/delete operations, durable on disk rather than
  20 in-memory entries lost at session end). One undo system remains:
  the governed one. The write/edit tools' output bytes are unchanged
  (the snapshot push was side-effect-only).
- [T795] `/undo` is re-routed through checkpoints - the headline trust
  fix of the wave. It now restores the NEWEST checkpoint behind a full
  approval whose detail shows the capture time, trigger, affected files
  (with explicit "will be DELETED" warnings for paths that did not
  exist at capture), and capped redacted diffs; a safety checkpoint of
  the current state is captured FIRST, so `/undo` is itself undoable
  (`/undo` twice = redo) and a failed safety capture aborts with zero
  changes. The previous implementation popped an in-memory per-file
  stack and wrote files directly - including protected paths like
  `.env` - with no approval gate, no checkpoint, and no protected-path
  classification, and its memory vanished at session end. Semantic
  change to be aware of: undo now operates on the last CHECKPOINTED
  mutation set (which can span multiple files for batch operations),
  not the last single write/edit; with checkpointing disabled, `/undo`
  says so explicitly instead of silently reverting from memory. The
  "Nothing to undo." empty-state wording is preserved byte-for-byte;
  restores are traced (`CHECKPOINT_RESTORED`). Checkpoint metadata also
  gained a monotonic capture `sequence` used as the createdAt tiebreak:
  two checkpoints captured within the same clock tick (exactly the
  undo-then-safety pattern) previously fell to a random UUID tiebreak,
  making "newest" a coin flip.
- [T794] `/checkpoint list` renders the unified timeline - id, local
  capture time, turn number, trigger, file count, and size, newest
  first - and a new `/checkpoint show <id>` renders per-file stats with
  capped, redacted restore diffs (captured content vs the CURRENT
  files, via a new `ApprovalDiffPreview.forRestore` that reuses every
  write/edit guard: protected paths including `.talos`, binary and
  oversized content all fail closed; entries that did not exist at
  capture are annotated "restore DELETES it"). Diff previews cap at 3
  files per show with an honest "(N more...)" marker. The
  `/checkpoint restore` approval description/detail bytes stay frozen -
  the rich previewed restore path is `/undo` (T795).
- [T793] Checkpoints gained a read model: `listSummaries`/`describe`/
  `blob` expose createdAt, turn number, a new human `trigger` (the tool
  and target that caused the capture; pre-T793 checkpoints render
  "(unknown)" - schemaVersion stays 1), file/byte counts, and manifest
  entries. `/checkpoint list` (and `listIds`) is now truly
  newest-first by `createdAt` - it was reverse-lexicographic on random
  UUIDs, i.e. arbitrary. Restores can now be traced
  (`CHECKPOINT_RESTORED`/`CHECKPOINT_RESTORE_FAILED`, counts only,
  best-effort), and a new `captureBeforeRestore` records the CURRENT
  state of the affected paths under a `restore-safety` backend before a
  restore overwrites them - the mechanism that makes `/undo` itself
  undoable in T795. Corrupt or pre-T793 metadata stays listable
  (tolerant reads).

### Changed (verification)
- [T792] A user-approved, successful, verification-class `run_command`
  (gradle_test / gradle_check / gradle_e2e_test, or any trusted `ws:`
  workspace profile) that ran AFTER the turn's last successful mutation
  now upgrades the post-apply verification verdict from READBACK_ONLY to
  PASSED ("Command verification passed: <profile> exited 0.") -
  command-level proof is strictly stronger than readback, and this is
  what makes workspace profiles useful on non-Java workspaces. The
  upgrade is additive only: FAILED is never overridden (failed runs
  already dominate the answer), runs ordered before the mutation prove
  nothing, build profiles (gradle_build/install_dist) are deliberately
  not verification-class in v1, and ambiguous outcome shapes change
  nothing (fail closed). Turns that already ran a passing gradle check
  after a mutation will now read PASSED instead of READBACK_ONLY.

### Added
- [T806] Workspace template commands: a markdown file at
  `.talos/commands/review.md` makes `/review` expand to the file's
  content and run through the unmodified prompt pipeline. Templates are
  workspace content - untrusted - so they get exactly typed-input
  capability: the same classification, tool policy, and approvals as if
  the user had typed the text; expansion is single-level (the result is
  never re-classified as a command) and since T788 the directory is a
  protected CONTROL path the model cannot write with an ordinary
  approval. `$ARGS` is replaced with the typed arguments (appended as a
  new paragraph when absent). Built-ins always win: templates live in a
  separate catalog consulted only on a registry miss, and names
  colliding with any built-in command or alias are dropped at load.
  Limits: 24 templates per workspace, 16 KiB each, names
  `[a-z0-9][a-z0-9-]*`; loaded once at startup (restart to reload - the
  footer in `/help all` says so). Templates appear in `/help all` under
  "Workspace commands" and in tab completion.
- [T805] Automatic context compaction is no longer invisible. When the
  auto-compactor summarizes older exchanges mid-session, one muted line
  now renders after the turn stats - `[context compacted: 6 older
  exchanges summarized · 4 kept verbatim]` - so the user sees their
  context change shape at the moment it happens instead of discovering
  it later through degraded recall. The notice is interactive-only
  render chrome: scripted and redirected transcripts are byte-identical
  to before, it never enters any Result, and it gets a defensive
  history-stripper entry (`UiChrome.CONTEXT_COMPACTED_PREFIX`, the
  WROTE_PREFIX precedent) so a model imitating the visible line cannot
  seed history with fake compaction claims. Driven by the one-shot
  T798 compaction event, polled race-free after the turn completes.
- [T804] `/compact` compacts the conversation on demand. The forced
  path skips the pair-threshold and over-budget gates and runs even
  when the auto-compaction failure breaker is open (explicit user
  intent - a forced failure still counts toward the breaker, a forced
  success resets it). Outcomes are reported honestly: "Compacted: N
  older exchanges summarized - M kept verbatim (~before -> ~after
  tokens, est.)", "Nothing to compact" when everything already fits
  (or the conversation is empty - that fast path never touches the
  model), and a failure renders the full status/category/reason with
  the guarantee that applies: history is preserved verbatim, nothing
  is lost. Uses the same compaction-mode flag as the auto path and
  `/context`, so the budget `/compact` enforces is the budget the
  meter shows.
- [T803] `/context` shows what occupies the context window - previously
  invisible state: an estimated history meter bar against the active
  compaction budget, the configured maximum
  (`limits.llm_context_max_tokens`), the response reserve and
  structural overhead, both mode budgets (assist 55% / rag 25%, active
  one marked), exchange count, sketch size, the last prompt's pinned
  @-files, the auto-compaction rule, and the last compaction attempt's
  full status line. The engine row surfaces the silent divergence
  between `limits.llm_context_max_tokens` and
  `engines.llama_cpp.context`: a smaller engine context warns about
  overflow risk, a larger one is noted as safe-but-unused, and Ollama
  shows "managed by Ollama" (reconciling the two keys stays deferred -
  this makes the gap visible). All figures are the chars/4 estimates
  the budget logic itself uses, labeled `(est.)`. One bootstrap flag
  now feeds the compaction listener, `/context`, and the upcoming
  `/compact`, so the surfaces cannot drift apart.
- [T802] `@file` pinning in prompts (unified/auto mode). Typing
  `@src/Main.java` (or `@"path with spaces"`) in a prompt pins that
  file's content into the turn: up to 4 explicit workspace-relative
  paths per prompt - no fuzzy matching, no directory walks - at 4,000
  chars per file and 12,000 total, with visible truncation markers. The
  content rides as ONE user-role `[PinnedFiles]` message injected
  immediately before the user's line, framed as untrusted reference
  data (ProjectMemory pattern); it never reaches task classification
  and never gains system authority, and the `@token` stays visible in
  the user's own words. Everything skipped says so before the spinner
  starts: missing files, directories, paths outside the workspace,
  files over 2 MiB, and binary content all produce one-line notices -
  and protected paths (`.env`, `.talos/**`, `.git/**`, keys) are
  refused with a pointer at the approval-gated `read_file` flow, with
  the identical refusal whether or not the file exists, so pinning can
  neither leak protected content nor probe for its existence. RAG mode
  (`/mode rag`) keeps its existing implicit pinning unchanged; e-mail
  addresses and mid-word `@` are never treated as pins.
- [T801] `/session export [id-prefix] [path] [--raw]` writes a markdown
  transcript of a stored session - header (id, workspace, created,
  model, exchanges, sketch) plus `## Turn N` blocks - from the snapshot
  when one exists, else from the crash log's completed-ok rows (aborted/
  error-turn residue never leaves the machine as transcript). Content
  was already redacted when it was written to the session store; the
  assembled document gets one more idempotent redaction pass, and a
  seeded placeholder is pinned to survive export verbatim. The default
  target is `~/.talos/exports/talos-session-<id>-<timestamp>.md` - the
  user's own home, never the workspace unless an explicit path says so
  - and explicit paths are never overwritten. No approval is asked
  (PromptCommand precedent: a user-initiated write of the user's own
  data to the user's own directory); the absolute path is reported.
  `--raw` copies the per-turn JSONL beside the markdown.
- [T800] `/session list` and `/session resume [id]`. `list` renders the
  workspace's stored sessions newest-first - display id (the UTC
  timestamp suffix; the one possible legacy file shows its short hash),
  age, exchange count, model, and `(current)`/`(legacy)`/`(crash log
  only)` markers. `resume` restores the latest OTHER session by default
  ("pick up where the previous session left off"); an id prefix selects
  a specific one (matched against the display id, ambiguous prefixes
  list the candidates) and may explicitly target the current session as
  a reload-from-disk. `/session load` is now an alias of `resume` - its
  former meaning ("re-read this workspace's single file") stopped
  existing when T799 introduced per-run instance files. `save`, `clear`,
  and the info block's `Saved file` row now operate on the ACTIVE
  session's instance id (save→quit no longer leaves two files for one
  session), `/session info` gained a `Session:` row showing the active
  instance id, and the usage line is now
  `/session [info|list|resume|save|load|clear|export]` (`export` lands
  in T801). Restored-session bytes ("Session restored: N exchanges
  (saved X ago).") and the no-saved-session/save/clear strings are
  unchanged.
- [T799] One workspace can now hold many sessions. Each REPL run
  persists under its own session instance id
  (`<workspace-hash>-<UTC timestamp>`) instead of overwriting the
  workspace's single bare-hash file: the close snapshot, the per-turn
  crash log, and turn traces all key on the instance id, and `/last`
  reads the active session's log through an injected id rather than
  re-deriving the workspace hash (which would have silently read the
  wrong log). Startup auto-restore and the "saved session found" notice
  pick the NEWEST stored session across legacy and instance files -
  legacy bare-hash files remain loadable and win when they are all that
  exists. Empty sessions (no turns, no sketch, no active task context)
  are no longer saved on close, so quitting an idle REPL leaves no
  file behind. `SessionStore` gains `listSessions(workspaceId)`
  (newest-first summaries covering snapshots, crash logs, and corrupt
  files, which list with epoch timestamps instead of hiding). The
  workspace hash itself is unchanged and still keys checkpoints and
  trace metadata. The `/session` command catches up in T800.
- [T798] Core context-meter and manual-compaction machinery (consumed by
  the `/context`, `/compact`, and compaction-notice tickets):
  `ConversationManager.meter(assistMode)` snapshots history-token
  estimates, the active mode's budget and pair threshold, sketch state,
  and the last compaction status; `compactNow` forces a compaction that
  skips the pair-threshold and over-budget gates and bypasses an OPEN
  failure breaker (explicit user intent - forced failures still count
  toward it, successes reset it) while keeping the recent
  budget-fitting tail verbatim exactly like the auto path (shared body,
  proven behavior-preserving by the T797 pins); and a one-shot
  `pollCompactionEvent()` signal set only by the automatic path, the
  hook for the T805 render-side notice. No user-visible change yet.
- [T791] New `/profiles` and `/verify` commands plus a `Verify` status
  row. `/profiles list` shows the declaration state and resolved
  profiles; `/profiles trust` is the chain's explicit-consent step - it
  renders the resolved profiles (absolute executable paths) and the
  declaration's SHA-256 behind an approval, then pins those exact bytes;
  `/profiles revoke` withdraws the pin. `/verify [ws:<id>]` evaluates
  the declaration and trust live (a just-pinned profile runs
  immediately; the model-facing run_command surface still registers at
  session start), plans through the same validation pipeline, asks
  per-run approval with the standard command detail, and prints the exit
  verdict with capped output tails. `/status`, `talos status`, and the
  startup banner gain a `Verify` row (`none declared` / `N profile(s)
  (untrusted - run /profiles trust)` / `N profile(s) (trusted)` /
  `invalid: ...`) - strictly additive: a blank value renders
  byte-identically to the pre-T791 output, pinned.
- [T790] Trusted workspace profiles are now invocable through
  `talos.run_command` as `ws:<id>`: one merged `CommandProfileRegistry`
  (built at session start) is threaded through the planner, the tool,
  and the turn processor, replacing three hardcoded default-registry
  call sites. Declared profiles register ONLY when the declaration is
  content-hash trusted; an untrusted, changed, invalid, or undeclared
  state is rejected at plan time with an instructive message (review
  with `/profiles trust`) - before any approval prompt is spent, which
  is the chain's proof obligation. Workspace profiles accept no caller
  arguments (declared fixed argv only), keep the per-run approval and
  BUILD_OR_TEST risk gates, and render through the same approval-detail
  format (the gradle byte-pins are unchanged). An invalid declaration
  prints one visible startup notice; the tool descriptor now mentions
  workspace profiles to the model.
- [T789] Workspace verification-profile declaration model (inert until
  T790 registers it): `<workspace>/.talos/profiles.yaml` declares up to 8
  fixed-argv command profiles (id, executable, args, timeout_ms,
  expected_writes - approval, network, interactivity, and risk are NOT
  declarable and always pinned to the hardened values). The loader
  validates fail-closed: one bad profile rejects the whole file with one
  human-readable reason, unknown keys are rejected so typos cannot
  silently default, args are screened against shell syntax, and
  workspace-relative executables (wrappers like `./gradlew`) must exist
  inside the workspace and register as their resolved absolute path
  (displayed in every trust and approval prompt). A new content-hash
  trust store (`~/.talos/trust/workspace-profiles/`) records explicit
  user consent over the declaration's raw bytes - any byte change
  returns the workspace to untrusted and requires re-consent; corrupted
  pins fail closed.
- [T786] New `/doctor` REPL command running the same default doctor probe
  set from inside a session (DEBUG group, listed by `/help`). It
  deliberately has no `--start` equivalent: a slash command must not block
  the session on a multi-minute model load or churn the GPU
  mid-conversation - end-to-end server verification stays CLI-only
  (`talos doctor --start`).
- [T784] New `talos doctor` subcommand: a fast environment preflight that
  verifies the config loads (and the user config parses), the engine
  backend resolves and a model is configured, the managed llama.cpp server
  binary and GGUF model file exist (reusing the engine's own pre-launch
  validation through a new public `LlamaCppPreflight` facade - one source
  of truth, the manager now delegates), the server responds (a managed
  server that simply is not running is honestly a WARN, since Talos starts
  it automatically on first prompt; unreachable connect-only servers FAIL),
  and the index/home directories are writable. Exit code 0 only when no
  check fails. `--start` opt-in additionally starts the managed server and
  runs a one-word chat inside try-with-resources, so the model is always
  released again; doctor never loads a model otherwise. Output is plain
  ASCII one-line-per-probe (`PASS/WARN/FAIL/SKIP`) with fix hints on
  failures. Unlike `talos diagnose` (a retrieval/answer deep dive), doctor
  is side-effect-light and finishes in seconds.

### Fixed
- [T783] `talos.delete_path` is now documented in the README tool table -
  it was registered and approval-gated since its introduction but missing
  from the user-facing table, a claims drift on the most dangerous tool.
  The `/checkpoint` and `/undo` commands gained their missing README rows
  (wording taken from their `spec()` summaries so docs and `/help` agree).
  A new `ReadmeToolTableDriftTest` pins the tool table bidirectionally
  against the canonical descriptor catalog - names and approval columns
  both - so a registered tool can never silently vanish from the docs
  again. Ride-along: `TokenBudgetFromConfigTest` was reading the
  developer's real `~/.talos/config.yaml` (a machine-local 32k
  `llm_context_max_tokens` failed its built-in-default assertion); it now
  removes the machine overlay first, the same hermeticity fix
  `LlmClientSamplingConfigTest` received at 0.10.3.

## [0.10.4] - 2026-06-11

### Changed
- [T782] Added the inline-TUI architecture decision record
  (`docs/architecture/31-inline-tui-strategy-and-fullscreen-rejection.md`):
  full-screen TUI (Lanterna/Jexer/alternate-screen) is rejected with
  evidence - the alternate screen would destroy the plain-scrollback
  transcripts the PTY evidence chain string-matches - and the Wave 3
  standing rules (byte-frozen chrome contracts, byte-identical
  degradation, visible markdown markers, Talos-authored nanorc, single
  authoritative writer, additive status row, JLine bumps as isolated
  PTY-revalidation events) are locked in with explicit revisit criteria.
- [T781] JLine upgraded 3.26.3 → 3.30.13 as an isolated change (not 4.x:
  the JNA provider and parts of the 3.x API are removed there). 3.30.12+
  fixes the status-bar duplication on terminal resize affecting the T779
  status row. The inert `.jna(true)` builder flag (no JNA on the
  classpath - resolution always fell through to the bundled JNI
  provider) is replaced by an explicit `.provider("jni")` pin for
  deterministic provider selection on JDK 21. Found and absorbed one
  3.30 behavior change: a terminal's writer now encodes output with the
  stdout-specific `outputEncoding()` which can differ from `encoding()`;
  `TerminalOutput` now uses the writer's actual charset so non-ASCII
  chrome cannot mangle. Full check green; redirected transcript
  byte-identical to the pre-wave smoke. This bump gates on the wave-close
  fresh true-PTY cycle.
- [T780] The status row now carries live session context next to the
  spinner: routing decision, active model id, and 1-based turn number
  (`⠹ Answering…  12s · route unified · qwen2.5-coder:14b · turn 3`),
  polled per tick and truncated ANSI-aware to the terminal width. All
  values are renderer-owned; the printed route-hint and turn-stat lines
  that the evidence chain matches remain printed scrollback lines,
  byte-unchanged - the row only mirrors them. Broken suppliers degrade
  silently to a context-free row.
- [T779] The thinking spinner now renders as a JLine Status bottom row
  on capable terminals (`cli.ui.StatusRowPresenter`): the row lives in a
  managed scroll region below the output, so no raw `\r` frames ever
  interleave with streamed answers and JLine's cursor model stays
  authoritative (completing T774). Terminals without scroll-region
  capabilities (dumb, legacy consoles) keep the legacy `\r` spinner; the
  capability probe mirrors JLine's own protected check. The status
  region is closed on session shutdown so the terminal scroll area is
  restored. Content is unchanged this ticket (spinner glyph + label +
  elapsed); the row is strictly additive - route hints, turn stats, and
  approval lines remain printed scrollback lines.
- [T778] Fenced code blocks are now syntax-highlighted in capable
  interactive terminals using JLine's bundled nanorc engine with
  Talos-authored minimal syntax definitions under `/nanorc/` (java,
  python, javascript/typescript, json, yaml, bash, diff, xml, html,
  css - GNU nano's GPLv3 files are deliberately not vendored). The
  complete code line is highlighted once and cut ANSI-aware at the pane
  width so token colors survive the cut; unknown languages, missing
  definitions, and parse failures all degrade to plain text, and
  highlighting never alters the characters (pinned).
- [T777] Trusted streaming markdown in capable interactive terminals:
  headings, bullets, inline `**bold**`/`*italic*`/`` `code` `` spans,
  and ``` fence delimiters are styled by a renderer-owned state machine
  (`cli.ui.md.StreamingMarkdownShaper` + `MarkdownLineStyler`) operating
  on already-sanitized, already-wrapped rows. Markers stay visible -
  styling only colors the original characters, so stripping ANSI always
  recovers the plain wrapped text byte-for-byte (the pinned invariant),
  and Talos chrome lines gain zero ANSI. Fenced code preserves spacing
  and hard-cuts at the pane width instead of word-wrapping; an
  unterminated fence flushes plain. Toggle: `ui.markdown` (default on;
  redirected/NO_COLOR/ASCII/dumb output is always plain regardless).
- [T776] Streamed answers now word-wrap at the live pane width in fully
  capable interactive terminals (color + Unicode + non-dumb), fixing the
  rail shear where long model lines overflowed and broke the answer-pane
  border. The new `StreamingAnswerShaper` replicates the block renderer's
  wrap byte-for-byte under arbitrary chunk boundaries (parity-tested
  against `renderBlock` as the oracle under 1-char, word-sized, and
  seeded-random chunkings at widths 60/80/96/120), emitting each row as
  soon as it fills - latency is bounded by one row plus one in-flight
  word. Degraded modes (redirected, scripted, NO_COLOR, ASCII, dumb)
  keep the historical pass-through bytes, pinned by goldens.
- [T775] The true-PTY manual-audit validator's prose-phrase checks (the
  protected-read denial, private-document handoff, and withheld-content
  phrases) now also match a wrap-tolerant view of the transcript: rail
  prefixes are stripped and consecutive pane lines rejoined, so a
  required phrase split by width-reactive soft wrapping (T772/T776)
  still validates. Paragraph breaks deliberately do not rejoin, and
  chrome checks - the byte-frozen approval prompt, isolation markers,
  command echoes, and the approvals counter - keep strict raw matching.
  Landed before the streaming wrap change so the evidence chain cannot
  be broken by a wrap boundary landing inside a required phrase.
- [T774] Interactive sessions now write through a single authoritative
  terminal-backed stream (`cli.ui.TerminalOutput.printStreamFor`): the
  banner, render engine, approval window, spinner, startup notices, and
  streamed answer chunks all flow through the JLine terminal's writer,
  replacing the previous split where streamed chunks used
  `terminal.writer()` while everything else printed to raw `System.out`.
  JLine's cursor/column model now sees every character that reaches the
  terminal, closing the documented Apr 2026 display-corruption class
  where a prompt redraw spliced scrollback into the input line.
  Scripted/redirected runs keep raw `System.out` - verified
  byte-identical against a pre-change transcript.
- [T773] The approval window and the `/status` dashboard resolve their
  width from the live terminal (clamped 60-120) instead of a hardcoded
  80. The approval prompt strings themselves are width-independent and
  stay byte-frozen via `ApprovalPromptText`/the T766 contract test;
  terminal-less paths (scripted approval, `talos status` outside the
  REPL, redirected output) keep the fixed 80 and do not consult
  `COLUMNS`, so their bytes are unchanged by construction.
- [T772] The answer pane resolves its width from the live terminal
  (clamped 60-120) instead of a hardcoded 96; the width is captured at
  stream open, so one streamed answer stays internally consistent and a
  terminal resize takes effect on the next answer. Paths without a
  terminal (redirected, scripted, e2e) keep the historical fixed 96 and
  never consult `COLUMNS`, so their bytes are unchanged by construction.
- [T771] Width resolution is now owned by a single rule
  (`cli.ui.TerminalWidths`): live JLine `Terminal.getWidth()` clamped to
  60-120, then the `COLUMNS` environment variable (same clamp), then the
  caller's surface default passed through unclamped so redirected and
  scripted output stays byte-identical. The startup banner is the first
  consumer - it now renders at the real terminal width in interactive
  runs instead of assuming 80 (`COLUMNS` is never set by default on
  Windows, so the env fallback effectively never fired there).
  Deliberate rule change: `COLUMNS` values of 40-59 previously rendered
  at face value; they now clamp to 60.
- [T770] `TerminalCapabilities.detectDefault()` (the input behind
  color/unicode/glyph selection) now takes its interactivity signal from
  the `isatty` probe instead of `System.console() != null` - the same
  JDK-22 hazard as T769, where redirected output would have been treated
  as interactive and received ANSI color and Unicode glyphs instead of
  byte-identical plain ASCII. The capability decision matrix itself is
  unchanged and remains pinned by `TerminalCapabilitiesTest`.
- [T769] Interactive-terminal detection now uses the OS-level `isatty`
  probe everywhere (new `cli.ui.InteractiveTty`, lifted from RunCmd's
  terminal selection) instead of `System.console() != null`, which on
  JDK 22+ reports a console even for piped/redirected output and would
  have flooded redirected transcripts with spinner carriage returns. The
  fallback for hosts where the JLine natives cannot load honors JDK 22's
  `Console.isTerminal()` via reflection (the build targets JDK 21).
  `RenderEngine` additionally takes its interactivity from the
  bootstrap's terminal selection (`lineReader` presence) rather than
  re-detecting, so scripted and test paths stay plain by construction.
- [T767] The history-chrome line prefixes that
  `MemoryUpdateListener.stripUiChromeForHistory` removes before assistant
  text reaches conversation history (`[Used N tool(s)...]`,
  `[Tool-call limit reached...]`, `[turn aborted...]`, `[Engine error...]`,
  `[Model '...' not found...]`, `✓ Edited/Created ...`,
  `Suggestion: edit_file has failed...`) are now shared constants
  (`core.util.UiChrome`) composed by both the emitters (LlmCallBudget,
  ToolLoopResultSummaryFormatter, ToolLoopFinalAnswerFinalizer,
  ToolMutationStateAccounting, EditFailureRepairStateAccounting,
  ToolReprompt* executors, AssistantTurnExecutor) and the stripper
  (MemoryUpdateListener, JsonTurnLogAppender), so an emitter rewording can
  no longer silently break stripping and reopen the BUG #1
  confidence-trick surface. A round-trip contract test
  (`runtime.UiChromeContractTest`) pins every emitter shape through the
  stripper, including the known gap that `✓ Updated ...` overwrite
  summaries are NOT yet stripped (fixed in T768). No output bytes changed.
- [T766] A cross-surface byte-identity contract test
  (`harness.ApprovalPromptContractTest`) now holds every approval-prompt
  evidence surface to the same bytes: the production gate's line forms,
  the scripted harness's published audit-event prompts, the true-PTY
  manual-audit validator's required transcript substring (exercised
  through a new string-level `auditTranscriptFindings` seam), the
  talosbench forbidden-substring bank (parsed from
  `tools/manual-eval/talosbench-cases.json`), and the process-driver REPL
  prompt. `ScriptedApprovalGate`, the PTY validator, and the approval
  smoke harness now reference `ApprovalPromptText` instead of retyped
  literals, so harness/production prompt drift is now structurally
  impossible rather than merely untested. No output bytes changed.
- [T765] The approval-prompt chrome strings (`Allow? [y=yes, a=yes for
  session, N=no]`, `Allow? [y=yes, N=no]`, the `Allow? [y=yes` prefix, and
  the `approval required` window title) are now owned by a single
  byte-frozen constants class (`cli.ui.ApprovalPromptText`) instead of
  being retyped at each call site; `CliApprovalGate` and
  `ApprovalPromptRenderer` render from the constants, and characterization
  tests pin the exact bytes against typed literals. These strings are
  load-bearing evidence-chain contracts (PTY manual-audit validator,
  talosbench forbidden-substring banks, scripted harness artifacts), so
  Wave 3 rendering work cannot drift them silently. No output bytes
  changed.
- [T764] The synchronized-approval workspace-operation scenarios
  (mkdir/copy/move/rename/delete/batch-apply, scripted and live) now claim
  the rendered outcome in addition to tool usage and file state: an
  approved-and-executed turn that fail-closes as BLOCKED (e.g.
  `OUTCOME_RENDERED {status=BLOCKED, classification=BLOCKED_BY_POLICY}`) now
  fails the harness instead of passing silently - the claim gap that masked
  T763's phantom expected-target block across the 0.10.2/0.10.3 packet
  lanes. PARTIAL outcomes still pass, so legitimate runtime-repair lanes are
  not overclaimed.

### Fixed
- [T768] `✓ Updated <path> (...)` mutation summaries (emitted when
  `talos.write_file` overwrites an existing file) are now stripped from
  conversation history like their `✓ Edited`/`✓ Created` siblings; they
  previously leaked into the model's context, exposing the same
  confidence-trick imitation surface as the documented BUG #1. The dead
  `✓ Wrote ` stripper entry is kept as a documented defensive rule (a
  line with that shape can only be chrome or a model imitating chrome).
- [T763] Task-contract target extraction no longer treats bare English
  function words ("by", "to", "with", "into", "using", ...) as path-like
  expected mutation targets; names with a file extension or path separator
  still extract. This removes the phantom remaining target "by" that the
  workspace-operation retry frame's "mkdir by writing/editing file content"
  wording injected into expected-target progress accounting, which
  fail-closed every approved copy/move/rename/delete retry turn as BLOCKED
  after the operation had already been approved, executed, and checkpointed
  (seen in the 0.10.2/0.10.3 packet sync-approval lanes).

## [0.10.3] - 2026-06-11

### Changed
- [T762] Read-only proposal grounding now derives ungrounded-file detection
  from observed tool evidence (result text plus the paths tools actually
  touched) instead of a hardcoded audit-fixture filename list - claims about
  ANY unread file now trigger the grounding warning, not just the seven
  fixture names. The policy moved from AssistantTurnExecutor into
  `runtime.outcome.ReadOnlyProposalGroundingGuard` per the policy-ownership
  doctrine.
- [T761] The advertised default tool surface is now derived from
  `ToolSurfacePlanner.plan()` over a canonical descriptor catalog instead of
  a hand-maintained duplicate branch tree; read-only turns with expected
  targets now advertise only `talos.read_file` (matching what the runtime
  always enforced - the model can no longer be advertised tools the runtime
  denies). Parity tests pin the catalog against the bootstrap registry.
- [T760] The protected-read answer postcondition now distinguishes blank
  model answers from refusals (truthful trace reasons) and scopes refusal-
  marker detection to the first 240 characters of the answer - long grounded
  answers with tail caveats like "the raw value cannot be shared" are no
  longer destroyed and replaced.
- [T759] Protected-path classification consolidated into a single canonical
  classifier (`ProtectedPathTokens`) with equals-or-suffix word-run matching;
  five divergent local copies (four repair planners + the protected-read
  answer guard) now delegate to it. Fixes `tokenizer.java`-class false
  positives while keeping `mysecrets.txt`/`api_token.txt`-class names
  protected; protected-content policy version bumped to v3 so RAG indexes
  rebuild their privacy partition.
- [T758] Tool failure classification is now driven by typed
  `ToolFailureReason` codes carried from producers through `ToolError` and
  `ToolOutcome`; all six message-sniffing classifier sites are migrated and
  the sniffing deleted, so error-message prose is free to change without
  silently disabling repair or outcome-truth policy. Redaction
  (`sanitizeToolResult`) now preserves the typed reason while rewriting
  prose.
- [T757] Mutation-intent blocking, pre-approval validators, and checkpoint
  capture now read `ToolOperationMetadata` from the registry-resolved tool
  instead of hand-maintained name lists (which failed open for tools missing
  from them); new `ToolMutationGate` treats unresolvable tools as mutating
  and checkpoint-required; `ToolCallSupport`'s duplicate name sets are
  deleted (static classification delegates to `ToolAliasPolicy`, pinned
  against metadata by a parity test).
- [T756] The approval window now shows a colored unified diff for write and
  edit mutations (java-diff-utils; capped at 60 lines; redacted; fail-closed
  skips for protected/oversized/binary targets; plain ASCII under
  NO_COLOR/ASCII terminals). The legacy approval detail stays byte-identical
  with the diff appended after it; a `APPROVAL_DIFF_PREVIEW` trace event
  records hash and line counts; risk inference ignores diff bodies so quoted
  "remove"/"delete" code cannot escalate the risk label.
- [T755] Markdown-commentary sanitization of write/edit content now runs
  once, pre-approval, in the runtime's call normalization - the approval
  preview, trace hashes, checkpoint, and written file all see identical
  bytes (approved bytes == written bytes). Tools write received bytes
  verbatim; sanitization is trace-recorded as a `TOOL_CONTENT_SANITIZED`
  event with redacted summaries.
- [T754] Hardened the bare tool-JSON detection regex (runtime parser and
  protocol stripper, which run on every model response) against catastrophic
  backtracking via possessive quantifiers; the pattern now has a single owner
  in `ToolProtocolText` and adversarial timeout regressions pin linear-time
  failure.

## [0.10.2] - 2026-06-11

### Changed
- [T753] Refreshed local Qodana evidence on the current head via the native
  fallback (Docker mode failed with the documented Windows Gradle-import I/O
  error): provenance now `qodana-results-match-current-candidate`, the three
  T752 findings are gone, zero critical issues, and the two triaged noise
  families are baselined in qodana.yaml with rationale.
- [T752] Behavior-preserving clarity refactors for the stale-scan Qodana
  findings: explicit null-flow in `ContextItem.fromToolResult` and
  `MutationTargetReadbackVerifier`, and try-with-resources around the command
  runner's executor (shutdownNow timeout semantics preserved), each pinned by
  tests.
- [T750] Hardened the coverage gate: INSTRUCTION floor 0.65→0.82, new BRANCH
  floor 0.62, and per-package floors for `runtime.policy`, `safety`, and
  `core.secret` ratcheted to measured actuals; CI workflow triggers repointed
  from the defunct-only `v0.9.0-beta-dev` filter to main + beta-dev +
  codex/feature branches.
- [T749] Added the release gate ledger: schema v1
  (`work-cycle-docs/release-gate-ledger.md`), a retrofitted GATES.json for
  the 0.10.1 packet, and `GatesLedgerTest` validating every ledger - release
  verdicts become machine-checkable artifacts with tooling-sourced SHAs.
- [T748] Added `TicketHygieneTest` (directory/status-token consistency and
  ID uniqueness repo-wide; strict template rules ratcheted for tickets
  T739+) and `scripts/ticket-aging.ps1` for open-queue age visibility.
- [T747] Added `scripts/cut-candidate.ps1`: hermetic scripted candidate cut
  (clean-tree guard, bump, commit, build-from-committed-tree, launcher-vs-HEAD
  cross-check, mandatory post-bump check, summary regeneration with version
  verification, and a tooling-sourced `candidate-manifest.json`), removing the
  provenance-defect class found on the 0.10.1 cut.
- [T751] Codified work-cycle doctrine: AGENTS.md candidate loop now orders
  changelog-before-bump (matching `bump-patch.ps1`), records the dirty-tree
  evidence-downgrade rule and the tooling-only SHA rule, and points at the
  scripted cut; the operator prompt branch reference is packet-anchored; the
  ticket template now requires per-ticket Unreleased CHANGELOG entries.
- [T746] Wave-1 stabilization evidence: first-ever complete Qwen 31-scenario
  synchronized live bank (artifact scan PASS) plus a zero-rescue GPT-OSS
  bank, proving the T739-T744 constraint stack on both audited models; the
  bank-position hypothesis was falsified via byte-identical seeded provider
  bodies.
- [T745] `proposal-only-does-not-mutate` is now runnable as a focused
  scripted/live synchronized-approval scenario (the only scenario exercising
  `talos.retrieve`), enabling clean focused retrieve evidence.
- [T744] Native tool-call arguments now survive the wire losslessly: container
  values (arrays/objects) are preserved as JSON in both argument converters
  (previously silently destroyed or rendered as Java toString), and
  `talos.apply_workspace_batch` advertises a native `operations` array as its
  grammar-constrained shape while still accepting the legacy double-encoded
  `operations_json` string.
- [T743] Escalating mutation repair ladder: malformed tool-protocol debris on
  mutation/workspace-obligation turns now gets one bounded MissingMutationRetry
  pass with escalated constraints (temperature pinned to zero) before the
  no-action notice; genuinely invalid mutating parameters get one corrected
  retry with the tool error echoed. Pre-approval policy rejections (sandbox
  escape, source-evidence blocks) keep fail-fast behavior.
- [T742] The workspace-operation capability frame now includes a literal
  `operations_json` example so 14B-class models see the exact wire format for
  batch operations instead of prose-only key descriptions.
- [T741] Source-evidence repair re-prompts now pin the known required tool via
  named tool choice (read-before-write repair pins `talos.read_file`,
  post-read write repair pins `talos.write_file`) and run near-greedy,
  eliminating the wrong-tool substitution observed in the t325 bank failure.
- [T740] Added provider sampling governance: new `SamplingControls`
  (temperature/top_p/top_k/seed) on `ChatRequestControls`, near-greedy
  defaults on tool-obligation turns, optional `llm.sampling.*` config
  overrides (incl. fixed seed for reproducible audit banks), emitted on the
  llama.cpp wire and rendered in prompt-debug.
- [T739] Wired `WORKSPACE_OPERATION_REQUIRED` turns to provider tool-choice
  enforcement (`required`, or named single-tool pinning when the surface
  exposes exactly one workspace tool), closing the constraint-coverage gap
  behind the Qwen full-bank workspace-batch failures; added
  `LlmClient.supportsNamedToolChoice()`.

## [0.10.1] - 2026-06-10

### Changed
- [T735-done-high] Added a runtime-owned private-document denial notice so the
  user-visible final answer deterministically says private document content was
  withheld from model context instead of relying on model paraphrase.
- [T736-done-high] Made the PTY manual-audit packet self-contained by running
  Talos under a packet-local isolated home, generating a launcher script, and
  requiring transcript evidence of both packet isolation and the ordinary `.env`
  approval prompt.
- [T737-done-high] Repaired approved private-document containment answers so an
  approved handoff can answer narrow yes/no containment questions without
  printing the protected value and without leaking redacted history wording into
  the visible answer.
- [T738-done-medium] Updated PTY validator trace acceptance so current
  approval-count `/last trace` evidence can satisfy the private-document
  approval-trace requirement without relaxing the denial-wording gate.
- Reset candidate provenance for the next release packet: the PTY/manual,
  synchronized-approval, and capability lanes will be rerun from a single clean
  committed candidate instead of mixing evidence across dirty-tree builds.

## [0.10.0] - 2026-06-07

### Added
- Added ArchUnit (`com.tngtech.archunit:archunit-junit5`) bytecode-level
  architecture guards in `dev.talos.architecture.LayeredArchitectureTest`,
  mirroring the six package-direction invariants enforced by the regex-based
  `validateArchitectureBoundaries` ratchet. ArchUnit additionally catches
  dependencies expressed through types, generics, annotations, and exceptions
  that the source scanner cannot see.
- Added a report-only architecture discovery pass
  (`dev.talos.architecture.ArchitectureDiscoveryReportTest`) that uses the
  ArchUnit Core API to write a deterministic Markdown report to
  `build/reports/talos/architecture/architecture-discovery-report.md` (package
  counts, dependency hotspots/fan-in/fan-out, package dependency map,
  runtime-control spine, layer-boundary candidates, and top-level package
  cycles). It never fails the build on findings; it is evidence for manual
  review before any rule is promoted to a hard guard.
- Added a report-only architecture cycle analysis pass
  (`dev.talos.architecture.ArchitectureCycleReportTest`) that slices the
  imported `dev.talos` bytecode at four levels (top-level packages, runtime
  subpackages, cli subpackages, core subpackages) and writes a deterministic
  Markdown report to
  `build/reports/talos/architecture/architecture-cycle-report.md`. Cycles are
  detected by a Tarjan strongly-connected-component pass and cross-checked with
  ArchUnit's caught `beFreeOfCycles` rule; severity is classified per level. It
  never fails the build on detected cycles.
- Added a report-only execution-harness spine access report
  (`dev.talos.architecture.ArchitectureSpineAccessReportTest`) that, for a fixed
  set of runtime-control "spine" classes (e.g. `AssistantTurnExecutor`,
  `ToolCallLoop`, `TaskContractResolver`, the policy/verifier classes,
  `CurrentTurnPlan`, `ExecutionOutcome`, `ConversationManager`), reports
  class-level fan-in/fan-out, top callers/callees, and ArchUnit-resolved
  method/constructor call counts to
  `build/reports/talos/architecture/harness-spine-access-report.md`. Deterministic,
  capped to top-N, and never fails the build on high fan-in/fan-out.
- Added a second generation of hard ArchUnit guards in
  `dev.talos.architecture.LayeredArchitectureTest`, promoted only after the
  report-only passes showed zero edges: `runtime.policy`, `runtime.verification`
  ↛ `cli`; `runtime.toolcall` ↛ `cli.repl`; `tools` ↛ `cli`; and `spi` ↛ `app`.
  Documented hard guards, report-only findings, accepted exceptions, and
  candidate future guards in `docs/architecture/11-architecture-guardrails.md`.
- [T719-done-high] Added a redacted audit snapshot utility and Gradle task for
  canary-clean milestone/manual audit packets, so release-clean scans can use
  sanitized final workspace evidence instead of raw fixture snapshots.

### Changed
- [T334-done-high] Added release-ledger discipline for beta candidates:
  `CHANGELOG.md` now keeps an `Unreleased` section, the patch bump script moves
  those notes into the next numeric candidate version, and `check` validates
  that the top released changelog entry matches `talosVersion`.
- [T335-done-high] Added an architecture hygiene baseline for the next refactor
  sequence, covering package-boundary debt, policy ownership, verifier/repair
  structure, CLI composition, release-evidence gates, and the recommended T336
  boundary-ratchet implementation.
- [T336-done-high] Added a ratcheted architecture-boundary import scanner wired
  into `check`, with an initial baseline of 62 forbidden import
  edges and focused TestKit coverage for new and stale boundary drift.
- [T337-done-medium] Moved tool alias metadata ownership from
  `runtime.toolcall` to `tools`, reducing the architecture-boundary baseline
  from 62 to 61 forbidden import edges without changing alias behavior.
- [T338-done-medium] Moved `WorkspaceSymbolChecker` ownership from CLI modes
  into core indexing, reducing the architecture-boundary baseline from 61 to 60
  forbidden import edges without changing prompt-routing behavior.
- [T339-done-high] Hardened `validateArchitectureBoundaries` so the ratchet
  catches fully-qualified forbidden `dev.talos...` type references as well as
  imports, while ignoring comments and string/char literals.
- [T340-done-medium] Removed the runtime-policy logging dependency from
  `IndexedWorkspaceSymbolChecker`, reducing the architecture-boundary baseline
  from 60 to 59 forbidden references without changing symbol lookup behavior.
- Documented monotonic pre-1.0 beta versioning: do not downsize or reuse
  candidate versions after artifacts, commits, tags, or audit evidence refer to
  them; use `0.9.10+` for narrow candidates, consider `0.10.0` for a broad beta
  milestone, and reserve `1.0.0` for stable beta exit.
- Backfilled the post-0.9.9 beta stabilization ledger with the audit-evidence,
  protected-document, terminal approval, prompt-surface, static-web, office
  document, Python-claim, site, and artifact-canary hardening work landed after
  the 2026-05-15 candidate declaration.
- Strengthened candidate provenance by making placeholder changelog text a hard
  local validation failure instead of a manual review hazard.
- [T720-done-medium] Reworded conditional static-web no-change answers as
  diagnostic inspection, keeping `Verification: NOT_RUN` truthful for
  inspection-only turns.

## [0.9.9] - 2026-05-15

### Changed
- Consolidated post-0.9.8 beta hardening into a named candidate, including the
  runtime control-plane, active-context, evidence-obligation, outcome-dominance,
  protected-read, static-web verification, workspace-operation, command-policy,
  and TalosBench work already landed on `v0.9.0-beta-dev`.
- [T251-done-high] Added managed llama.cpp model setup and config diagnostics,
  including audited `qwen2.5-coder-14b` and `gpt-oss-20b` setup profiles,
  YAML-safe Windows config generation, Talos-owned Hugging Face cache support,
  and verbose malformed-config reporting.
- [T252-done-high], [T255-done-high], and [T257-done-medium] improved natural
  intent routing for directory creation, batch workspace operations, and
  bounded command requests without exposing arbitrary shell execution.
- [T253-done-high], [T254-done-high], [T259-done-high], and [T262-done-high]
  hardened source-derived artifact work so source files are read as evidence,
  output files are tracked as mutation targets, privacy negations stay scoped,
  and derived writes before source reads are blocked before approval.
- [T256-done-high], [T258-done-medium], and [T261-done-medium] corrected
  prior-outcome and session-evidence answers so status and uncertainty
  responses are scoped to the asked artifact or workspace operation instead of
  the latest unrelated turn.
- [T260-done-high] and [T264-done-medium] kept natural list-style prompts on
  filename-only evidence paths, including casual `what is in here?` phrasing,
  without reading file contents.
- [T263-done-medium] and [T265-done-medium] refreshed TalosBench expectations
  and assertion scope so the benchmark checks the current product contract and
  final natural turn where appropriate.
- Added and polished the Talos beta landing page under `site/`, with honest
  placeholder beta calls to action, no fake release artifact URL, static tests,
  and Playwright e2e coverage.
- [T266-done-high] Declared the 0.9.9 beta candidate and produced the candidate
  build/test/site/static-analysis summary evidence packet for release review.

## [0.9.8] - 2026-04-29

### Changed
- [T43-done-medium] Protected reads now display as sensitive/protected reads,
  and denied protected reads are classified as blocked by approval instead of
  completed read-only answers.
- [T44-done-medium] Bounded small-web repair now requires complete
  `write_file` replacements for structural HTML/CSS/JS repair targets, rejects
  brittle `edit_file` attempts for those targets before approval, and continues
  through planned full-write repair targets.
- [T45-done-medium] Simple folder-listing prompts now use `list_dir` only,
  suppress content tools and generic workspace context, and shape filename
  answers from actual directory listing results.
- [T46-done-medium] `/last` and `/last trace` now redact secret-like
  `KEY=value` values from the human-readable user request preview while
  preserving path, tool, and policy metadata.
- [T48-done-high] Added current-turn capability frames and action-obligation
  enforcement so mutation-capable turns cannot final-answer with false
  no-filesystem or no-modification denials.
- [T49-done-high] Added the TalosBench live prompt matrix and failure
  taxonomy.
- [T50-done-high] Added the TalosBench live prompt runner and starter prompt
  cases.
- [T51-done-high] Added TalosBench `/last trace` assertion support.
- [T52-done-high] Documented Terminal-Bench 2 compatibility and task
  classification for Talos.
- [T53-done-high] Added the evaluation failure intake workflow and reusable
  evaluation-derived ticket template.

## [0.9.7] - 2026-04-29

### Changed
- [T29-done-medium] Cleaned current native Qodana high findings and restored
  fresh local Qodana evidence to 0 high and 0 critical applied-profile issues.
- [T30-done-high] Added the post-0.9.6 execution-discipline and local-trust
  architecture spine.
- [T31-done-high] Mapped runtime policy ownership before policy extraction so
  future refactors have a tested responsibility map.
- [T32-done-high] Designed local turn trace model v1, including redaction,
  event shape, storage direction, and T33 implementation criteria.
- [T33-done-high] Implemented local turn trace v1 for task contracts, tool
  surfaces, approvals, blocks, checkpoints, verification, and outcomes.
- [T34-done-high] Designed declarative allow/ask/deny permissions with
  deny-first precedence and protected path defaults.
- [T35-done-high] Implemented declarative local permissions for tools, paths,
  protected resources, approvals, and trace-visible decisions.
- [T36-done-high] Designed local checkpoint/restore as the trust layer before
  approved mutations.
- [T37-done-high] Implemented local checkpoint creation before approved
  mutations and restore support.
- [T38-done-high] Designed bounded repair controller behavior for
  post-verification failures and invalid edit loops.
- [T39-done-high] Implemented bounded repair planning using static verifier
  findings without weakening approval, permission, or stop policies.
- [T40-done-high] Fixed formatting-negation prompts so `do not use angle
  brackets/placeholders` no longer cancels explicit mutation intent.
- [T41-done-high] Ran the installed Talos manual prompt evaluation before the
  0.9.7 candidate and recorded blockers/follow-ups.
- [T42-done-high] Added deterministic exact full-file content expectations so
  literal overwrite requests verify the final file content instead of relying
  on write/readback alone.
## [0.9.6] - 2026-04-28

### Changed
- [T11-done-high] Status questions such as `did you make the changes?`
  now resolve as verify-only/read-only turns instead of mutation turns.
- [T12-done-high] Mutating tool calls missing required arguments are rejected
  before approval, so users are not asked to approve invalid writes or edits.
- [T13-done-high] Tool-call JSON protocol text is kept out of final visible
  answers when the protocol path handles or rejects it.
- [T14-done-high] Repair follow-ups now use one shared task contract for trace,
  prompt read-only mode, native tool selection, and execution policy.
- [T15-done-high] Verification wording now distinguishes file write/readback
  checks from task-specific completion verification.
- [T16-done-high] Added static web-app verification for linked assets,
  placeholders, duplicate asset references, expected DOM elements, and
  JavaScript selector coherence.
- [T17-done-medium] Expected target matching now normalizes paths for Windows
  casing and separator behavior.
- [T18-done-medium] Added idempotent web asset checks so repeated stylesheet or
  script insertions do not look verified.
- [T19-done-high] Prior-change status follow-ups now preserve the latest
  verified outcome instead of overclaiming completion.
- [T20-done-high] Scoped mutation limiters such as `fix only styles.css` now
  allow the intended target while blocking forbidden targets.
- [T21-done-high] Post-denial retry turns reissue the previously denied action
  through approval instead of drifting into no-op answers.
- [T22-done-high] Overwrite, rewrite, replace, repair, and natural
  non-technical artifact requests now classify as mutation-capable when they
  ask Talos to modify local files.
- [T23-done-high] Repair retries after static verification failure now include
  verifier findings and steer small web-file repair toward bounded full-file
  replacement when edit anchors are brittle.
- [T24-done-high] Mutating tool protocol blocked by read-only policy is now
  sanitized with truthful no-action wording instead of leaking raw JSON or fake
  approval prose.
- [T25-done-high] Chat-mode small talk, capability prompts, and explicit
  privacy-negated prompts no longer expose or call workspace tools.
- [T26-done-medium] Repeated status follow-ups now return direct,
  deduplicated verified-outcome summaries.
- [T27-done-high] Malformed Talos tool-call-like output is sanitized and
  reported without leaking protocol text or stalling the turn.
- [T28-done-high] Functional web verification now fails when a scripted web
  task has no JavaScript behavior, even if HTML and CSS were written.
## [0.9.5] - 2026-04-27

### Changed
- [T02-done-high] Required read-only workspace evidence for `VERIFY_ONLY`
  confirmation turns and grounded web completion checks with static diagnostics
  before accepting final answers.
- [T03-done-high] Buffered natural workspace-explain turns and retried no-tool
  or list-only underinspection with read-only inspection from the current
  workspace.
- [T07-done-high] Added JSON-backed multi-turn coverage so follow-up change
  summaries preserve partial/static verification truth.
- [T08-done-high] Filtered `/last` output to active-process turns so unloaded
  saved session history is not presented as the current trace.
- [T04-done-medium] Added read-only deictic follow-up intent inheritance without
  carrying mutation permission.
- [T05-done-medium] Answered capability/onboarding small talk as Talos instead
  of generic base-model boilerplate.
- [T06-done-medium] Improved `/help all` discoverability and made `edit_file`
  user-visible text ASCII-safe for transcript capture.
- [T09-done-medium] Fixed dev-mode natural root listing prompts such as
  `list the files here`.
- [T10-done-medium] Expanded the manual QA constitution with stable case IDs,
  coverage tags, severity taxonomy, and finding-to-ticket intake rules.

## [0.9.4] - 2026-04-26

### Changed
- [T01-done-high] Blocked no-tool answers that deny Talos can access local
  workspace files when read tools are available; such turns now finalize as an
  advisory capability correction, and streaming sessions visibly emit the
  correction after the raw model output.

## [0.9.3] - 2026-04-26

### Changed
- Added tool-backed retry for explicit mutation turns where the model first answers without calling file tools, including compatibility for `create_file` / `function_name` tool-call aliases.
- Improved natural conversational flow: identity small talk answers as Talos, natural read-only site diagnostics are grounded in static workspace facts, and follow-up change summaries reuse prior verified outcomes.
- Improved manual QA/debug ergonomics: `/last --verbose` maps to trace output, stale turn selection prefers latest timestamps, and slash `/grep` searches CSS-family files by default.

## [0.9.2] - 2026-04-26

### Changed
- Made saved workspace sessions explicit by default: Talos now reports saved history without injecting it into prompt context unless `session.auto_load=true` or `/session load` is used.
- Honored `session.persistence=false` in CLI bootstrap so ephemeral runs skip persistent session reads and writes.
- Preserved explicit session restore, including JSONL crash-recovery fallback, and improved cleanup of turn-log-only sessions.

## [0.9.1] - 2026-04-25

### Changed
- Added a narrow post-apply static task verifier for mutation targets and small HTML/CSS/JS selector coherence.
- Wired verifier status into central execution outcomes so Talos can distinguish applied, verified, failed, and incomplete static checks.
- Added deterministic verifier scenarios for failed selector repair, successful CTA repair, and partial mutation non-completion.

All notable Talos distribution changes should be recorded in this file.

The format is intentionally simple:
- one section per released public version
- public versions are numeric only: `major.minor.patch`
- patch increments (`0.9.1`, `0.9.2`, ...) mark intentional distribution builds

## [0.9.0] - 2026-04-22

Initial numeric-version baseline for the current public line.

### Changed
- moved the canonical Talos public version source of truth into Gradle properties
- removed hardcoded public version values from build and CLI fallback paths
- aligned CLI version output with runtime build metadata resolution
- added this root changelog and a patch bump script for future release discipline
