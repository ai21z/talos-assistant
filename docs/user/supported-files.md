# Supported files

Talos beta is strongest for developer and text-oriented workspaces:

- code projects
- Markdown and plain text
- JSON, YAML, XML, TOML, INI, properties, and config files
- CSV and TSV
- HTML, CSS, JavaScript, TypeScript, Java, Kotlin, Python, Go, Rust, C, C++, shell, PowerShell, Gradle, and similar project text files
- static websites and source assets

Talos has narrow local extraction paths for text-bearing PDFs, `.docx` Word documents, and `.xls` or `.xlsx` workbooks. These are extraction paths, not layout-perfect document understanding.

Limits to report honestly:

- scanned or image-only PDFs require OCR
- PDF visual order may be imperfect
- DOCX layout, comments, tracked changes, and embedded objects may be incomplete
- hidden sheets, charts, macros, and formula recalculation are limited for workbooks
- large extracted output may be truncated
- corrupt or encrypted documents are unreadable evidence
- images and PowerPoint remain outside beta product claims

## How to phrase requests

Give Talos concrete targets when possible:

```text
Summarize docs/product-plan.md.
Append one bullet to README.md about local setup.
Review index.html and styles.css for obvious static-site issues.
```

Avoid broad private-folder prompts or unsupported media requests. If Talos cannot inspect a file type well enough, the correct answer is an honest limitation, not a guessed summary.
