package dev.talos.core.context;

/** Privacy classification for context ledger items. */
public enum ContextPrivacyClass {
    NORMAL,
    PROTECTED_PATH,
    EXTRACTED_DOCUMENT_TEXT,
    PRIVATE_DOCUMENT_EXTRACTED_TEXT,
    PRIVATE_RAG_SNIPPET,
    COMMAND_OUTPUT,
    GENERATED_TEXT
}
