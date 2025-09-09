package dev.loqj.core.ingest;

public record ParsedChunk(String id, String path, String text, String fileHash, int chunkId) {}
