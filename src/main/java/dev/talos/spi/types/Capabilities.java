package dev.talos.spi.types;

/**
 * Engine capability flags reported by a {@link dev.talos.spi.ModelEngine}.
 *
 * @param chat          supports multi-turn chat
 * @param stream        supports streaming token delivery
 * @param embed         supports embedding generation
 * @param contextWindow maximum context window in tokens
 * @param nativeTools   supports native structured tool calling
 * @param requiredToolChoice supports requiring a tool call for one request
 * @param namedToolChoice supports requiring a specific named tool for one request
 * @param jsonObjectResponse supports JSON object response formatting
 * @param jsonSchemaResponse supports JSON Schema response formatting
 * @param serverModelCatalog supports listing models from the provider/server
 * @param managedProcess supports Talos-managed provider process lifecycle
 */
public record Capabilities(
        boolean chat,
        boolean stream,
        boolean embed,
        int contextWindow,
        boolean nativeTools,
        boolean requiredToolChoice,
        boolean namedToolChoice,
        boolean jsonObjectResponse,
        boolean jsonSchemaResponse,
        boolean serverModelCatalog,
        boolean managedProcess
) {

    /** Full factory. */
    public static Capabilities of(
            boolean chat,
            boolean stream,
            boolean embed,
            int ctx,
            boolean nativeTools,
            boolean requiredToolChoice,
            boolean namedToolChoice,
            boolean jsonObjectResponse,
            boolean jsonSchemaResponse,
            boolean serverModelCatalog,
            boolean managedProcess
    ) {
        return new Capabilities(
                chat,
                stream,
                embed,
                ctx,
                nativeTools,
                requiredToolChoice,
                namedToolChoice,
                jsonObjectResponse,
                jsonSchemaResponse,
                serverModelCatalog,
                managedProcess);
    }

    /** Backward-compatible factory (provider-control flags default to false). */
    public static Capabilities of(boolean chat, boolean stream, boolean embed, int ctx, boolean nativeTools) {
        return of(chat, stream, embed, ctx, nativeTools,
                false, false, false, false, false, false);
    }

    /** Backward-compatible factory (nativeTools and provider-control flags default to false). */
    public static Capabilities of(boolean chat, boolean stream, boolean embed, int ctx) {
        return of(chat, stream, embed, ctx, false);
    }
}
