# Third-Party Notices

Talos declares its major Java dependencies in `build.gradle.kts` and
`gradle.properties`. Each component is licensed by its respective owner. This
file tracks the major direct dependencies used by the current repository; the
resolved artifacts remain the source of truth for complete license text and
transitive dependency notices.

| Component | License identifier | Talos use |
|---|---|---|
| Apache Lucene | Apache-2.0 | Workspace indexing and search |
| Picocli | Apache-2.0 | CLI command parsing |
| JLine | BSD-3-Clause | Interactive terminal and REPL support |
| SQLite JDBC | Apache-2.0 | Local cache database driver |
| Jackson | Apache-2.0 | JSON/YAML serialization |
| SLF4J | MIT | Logging API |
| Logback | EPL-1.0 or LGPL-2.1 | Logging backend |
| Log4j to SLF4J | Apache-2.0 | Logging bridge |
| Apache PDFBox | Apache-2.0 | PDF text extraction |
| Apache POI | Apache-2.0 | DOCX/XLSX text extraction |
| HtmlUnit | Apache-2.0 | HTML parsing/testing support |
| java-diff-utils | Apache-2.0 | Text diff support |
| JUnit | EPL-2.0 | Test framework |
| ArchUnit | Apache-2.0 | Architecture-boundary tests |

When adding or removing a major direct dependency, update this table in the
same change as the dependency declaration.
