package dev.talos.runtime.toolcall;

import dev.talos.core.context.ContextItem;
import dev.talos.core.context.ContextItemSource;
import dev.talos.core.context.ContextPrivacyClass;
import dev.talos.core.context.ExecutionBoundary;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolResult;

final class ToolResultContextItemAdapter {
    private ToolResultContextItemAdapter() {
    }

    static ContextItem fromToolResult(String toolName, String path, ToolResult result) {
        ToolContentMetadata metadata = result == null ? ToolContentMetadata.normal() : result.contentMetadata();
        ContextPrivacyClass privacy = privacyClass(metadata);
        String output = result == null ? "" : result.output();
        // T752: explicit null-flow - preserve the old fallback semantics while
        // keeping tool-specific conversion outside core.context.
        String metadataSourcePath = metadata == null ? "" : metadata.sourcePath();
        return ContextItem.fromText(
                sourceForTool(toolName, metadata),
                boundaryForTool(toolName, metadata),
                privacy,
                blank(metadataSourcePath) ? path : metadataSourcePath,
                output,
                0);
    }

    private static ContextPrivacyClass privacyClass(ToolContentMetadata metadata) {
        if (metadata == null || metadata.privacyClass() == null) {
            return ContextPrivacyClass.NORMAL;
        }
        return ContextPrivacyClass.valueOf(metadata.privacyClass().name());
    }

    private static ContextItemSource sourceForTool(String toolName, ToolContentMetadata metadata) {
        if (metadata != null) {
            if (metadata.source() == ToolContentMetadata.ContentSource.RAG_RETRIEVE
                    || metadata.source() == ToolContentMetadata.ContentSource.RAG_INDEX) {
                return ContextItemSource.RAG_SNIPPET;
            }
            if (metadata.source() == ToolContentMetadata.ContentSource.COMMAND) {
                return ContextItemSource.COMMAND_OUTPUT;
            }
        }
        return "talos.run_command".equals(toolName) ? ContextItemSource.COMMAND_OUTPUT : ContextItemSource.TOOL_RESULT;
    }

    private static ExecutionBoundary boundaryForTool(String toolName, ToolContentMetadata metadata) {
        if (metadata != null) {
            if (metadata.source() == ToolContentMetadata.ContentSource.RAG_RETRIEVE
                    || metadata.source() == ToolContentMetadata.ContentSource.RAG_INDEX) {
                return ExecutionBoundary.RAG_INDEX;
            }
            if (metadata.source() == ToolContentMetadata.ContentSource.COMMAND) {
                return ExecutionBoundary.COMMAND_PROFILE_OUTPUT;
            }
        }
        return "talos.run_command".equals(toolName)
                ? ExecutionBoundary.COMMAND_PROFILE_OUTPUT
                : ExecutionBoundary.LOCAL_WORKSPACE;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
