# Commands

Use this page for orientation. The installed `talos --help` and `/help all` output are the authority for the exact command list in the build you are running.

Top-level CLI commands start Talos, inspect runtime state and provide model setup:

```text
talos
talos --version
talos status
talos status --verbose
talos doctor
talos doctor --start
talos setup wizard
talos setup models
```

Common REPL slash commands:

```text
/help
/help all
/mode
/mode ask
/mode plan
/mode agent
/status
/status --verbose
/doctor
/prompt
/debug prompt on
/last trace
/session clear
/profiles list
/verify
```

Verification profiles are configured and trusted inside the REPL, not through top-level `talos profiles` or `talos verify` commands. Configuration syntax is `/profiles configure <id> --exec <executable>`, with an actual profile ID and executable substituted. The declaration is reviewed and pinned through `/profiles trust`, and trust is revoked through `/profiles revoke`. Bare `/verify` lists runnable profiles. A named trusted profile is run through `/verify ws:<id>`, with approval required per run.

`talos setup models` alone prints help. A configuration write requires `--profile`, `--server-path` and `--write`. See [Model setup](../getting-started/model-setup.md).

`talos doctor` checks configuration and local runtime state. `talos doctor --start` may start the managed model server, require an end-to-end model smoke reply, then release the managed server again.

Mutation and command execution are not implicit. Writes are permission-gated, with configured policy and remembered approval affecting whether a fresh prompt is needed. Workspace verification requires approval per run.

## Useful command order

For a clean workspace smoke, use:

```text
talos status --verbose
talos doctor --start
talos
/debug prompt on
/status --verbose
/mode ask
What is in this workspace?
/last trace
```

Use `/prompt` and prompt-debug commands for audits, not for normal first use.

## Document comparison

This capability is Unreleased. Its availability is not established in the existing 0.10.8 release.

Comparison is requested in a read-only turn, not through a new slash command. Ordered workspace-relative paths are bound by the current request. Supported examples are:

```text
/mode ask
Compare before.txt and after.txt.
What changed between first.pdf and second.pdf?
Explain how script.js differs from scripts.js.
```

The internal `talos.compare_documents` tool is exposed only for a supported request. The current path grammar accepts ASCII names with an extension, without spaces, optionally enclosed in backticks. Arbitrary paraphrases and ambiguous operands are not guaranteed to be understood. Recognized unsupported comparison requests remain incomplete, even after successful file reads.

Exact UTF-8 text and code are compared without executing code. Original whitespace, Unicode, BOM and line terminators are retained. Raw-byte equality, text equality and BOM or line-terminator sequence equality are reported separately. Both captured source identities are shown. The snapshots are not an atomic cross-file view.

PDF and Word DOCX comparison is extracted-text-only. `document_extraction.enabled` and the applicable `document_extraction.pdf.enabled` or `document_extraction.word.enabled` setting must be enabled. Visual layout, scanned images, tracked-change semantics and legacy DOC are not compared. Extraction failure, redaction, exceeded limits, protected paths and incomplete input prevent a complete result. Normal representation-limit notices do not invalidate an otherwise complete extracted-text comparison.

Each input is limited to 2 MiB and 64,000 text characters. Line, diff computation and output bounds also apply. These input and output bounds do not impose hard CPU, heap or deadline limits on the PDF and DOCX parser libraries, and hostile-document isolation is not provided. A completed computation is not a complete answer if required changes were omitted from display. A requested explanation is assessed separately from the text diff. Currently supported JavaScript explanations cover closed handler and calculation structures or literal-only constant declarations. General behavioral equivalence is not established.

Both operands retain their permissions. Private document-derived answers can be displayed locally while model context, debug artifacts, history and saved sessions remain withheld. Comparison does not grant permission to send protected content to a model. A denied or unsupported request must not be interpreted as an equal-files result.
