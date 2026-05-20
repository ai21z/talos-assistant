# T304 - Extraction Cache and Invalidation

Status: deferred-beyond-beta - add extraction cache only if performance evidence proves direct extraction too slow
Severity: medium / high if extraction is slow in live audit
Release gate: conditional for document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

PDF parsing, DOCX extraction, XLSX walking, and image OCR can be expensive. If direct read, grep, and RAG each re-extract the same file independently, Talos will be slow and inconsistent. If extraction is cached incorrectly, Talos can serve stale or policy-incompatible text.

## Evidence from current code

- `Indexer` hashes files for freshness and writes policy metadata.
- `CacheDb` exists for embeddings and answer/cache behavior.
- There is no extraction cache or extraction metadata file today.

## Evidence from source crosscheck

OCR and large Office/PDF extraction are dependency-sensitive and slower than plain UTF-8 reads. Durable extraction artifacts become privacy-sensitive if cached.

## User impact

Repeated document questions may feel slow, and stale extracted text can mislead users after files change.

## Product risk

Medium initially, high if image OCR or large spreadsheets are enabled by default.

## Runtime boundary affected

Extraction service, RAG indexing, grep/search, file hash tracking, privacy policy versioning, artifact scanning, and performance.

## Non-goals

- No raw extraction cache by default.
- No encrypted cache in this ticket.

## Required behavior

- Cache only sanitized extracted text and metadata, or do not cache.
- Cache keys include file path, file hash, extraction policy version, adapter version, privacy policy version, and relevant config hash.
- Private mode either disables cache writes or writes sanitized-only cache entries according to policy.
- Stale cache entries are refused or rebuilt.

## Proposed implementation

Start without a cache unless performance tests prove repeated extraction is too slow. If needed, add an `ExtractionCache` abstraction with sanitized-only storage and metadata. RAG index can act as the durable search cache; direct reads can re-extract until benchmarks show this is too slow.

If a cache is added, it must be extraction-aware rather than a generic text cache. Cache entries need:

- source path relative to workspace
- file hash
- file size and modified time as diagnostics only, not sole freshness proof
- format capability policy version
- extraction policy version
- adapter name and version
- privacy policy version
- config hash for limits and enabled/disabled formats
- sanitized text hash
- partial/truncation status
- provenance summary

Private mode should default to no extraction-cache writes unless the cache is sanitized-only and covered by targeted artifact scans.

## Tests

- `extraction_cache_key_changes_when_file_hash_changes`
- `extraction_cache_key_changes_when_policy_version_changes`
- `extraction_cache_key_changes_when_adapter_version_changes`
- `extraction_cache_key_changes_when_extraction_limits_change`
- `private_mode_does_not_cache_raw_extraction_text`
- `stale_extraction_cache_is_rebuilt_or_refused`
- `artifact_scan_covers_extraction_cache_when_enabled`

## Acceptance criteria

- No raw extracted text is cached by default.
- Any cache includes enough metadata to avoid stale policy reuse.
- Performance decision is evidence-based, not speculative.

## Rollback / migration notes

Cache can remain unimplemented for initial beta if direct extraction and RAG indexing are fast enough in tests.

## Open questions

- Should extraction cache reuse `CacheDb` or use a separate store under Talos index metadata?

## Related files

- `src/main/java/dev/talos/core/cache/CacheDb.java`
- `src/main/java/dev/talos/core/index/Indexer.java`
- `src/main/java/dev/talos/core/util/Hash.java`
