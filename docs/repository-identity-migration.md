# Repository Identity Migration

Talos is the current public identity for this repository.

- Product name: Talos
- Repository name: `talos-cli`
- GitHub repository: `ai21z/talos-cli`
- GitHub URL: `https://github.com/ai21z/talos-cli`
- SSH URL: `git@github.com:ai21z/talos-cli.git`
- Public description: "Local-first CLI workspace assistant with retrieval, approval-gated file operations, traces, context handling, and verification-oriented outcomes."

Historical context should stay brief and intentional: Talos started as LOQ-J,
a local RAG CLI, and evolved into a local-first workspace assistant.

## URL Migration

Replace hardcoded old repository URLs when they appear in public docs, scripts,
badges, examples, or install instructions.

| Old | New |
| --- | --- |
| `https://github.com/ai21z/loqj-cli` | `https://github.com/ai21z/talos-cli` |
| `git@github.com:ai21z/loqj-cli.git` | `git@github.com:ai21z/talos-cli.git` |

## Rename Checklist

- Rename GitHub repository to `ai21z/talos-cli` through the GitHub UI.
- Update local git remote:
  ```powershell
  git remote set-url origin https://github.com/ai21z/talos-cli.git
  ```
- Verify local remote:
  ```powershell
  git remote -v
  ```
- Update README links.
- Update install docs.
- Update scripts with hardcoded repo URLs.
- Update docs and examples.
- Update screenshots or captions if they mention old names.
- Verify old GitHub links redirect.
- Do not create a new repository using the old `loqj-cli` name, because it can interfere with GitHub redirects.

## Suggested GitHub Topics

- `local-ai`
- `cli`
- `java`
- `ollama`
- `workspace-assistant`
- `ai-agent`
- `local-first`
- `retrieval`
- `developer-tools`
- `verification`

## Package Note

The current Java package root is `dev.talos`. Do not rename packages as part of
repository identity cleanup unless a separate package migration plan explains
the compatibility impact.
