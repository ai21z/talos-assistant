# LOQ-J — Local-Only Java CLI for RAG

Fast, private, citation-backed answers grounded in your current directory.
- **Java 21**, Lucene 10.x, JLine REPL, Jackson
- Local LLMs via **Ollama** (e.g., `qwen3:8b`)
- Embeddings via `bge-m3` (vectors default **off** in config)
- Modes: `ask | rag | rag+memory | dev | web | auto`

---

## Quickstart

```bash
# Build & install
./gradlew clean installDist

# (Optional) clear local indices
# Windows PowerShell:
Remove-Item -Recurse -Force "$env:USERPROFILE\.loqj\indices\*"

# Index current repo
./build/install/loqj/bin/loqj rag-index --root .

# Run REPL
./build/install/loqj/bin/loqj run --root .
