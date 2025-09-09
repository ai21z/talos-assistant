# LOQ-J CLI: Local RAG agent (Java 21 + JavaFX + Lucene + jpackage)
 
- Windows-first MSI via jpackage (bundled JRE)
- First-run wizard: install Ollama (winget), pick models, pull locally
- Hybrid RAG: Lucene BM25 + KNN (embeddings via Ollama), SQLite cache
- Sandbox, dry-run, audit (JSONL)
 
Dev:
  gradle wrapper
  .\gradlew run
 
Package (MSI):
  .\gradlew jpackageApp
