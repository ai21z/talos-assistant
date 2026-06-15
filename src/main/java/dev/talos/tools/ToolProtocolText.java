package dev.talos.tools;

import java.util.regex.Pattern;

/** Compatibility facade for non-executing Talos tool-protocol text cleanup. */
public final class ToolProtocolText {
    private ToolProtocolText() {}

    public static Pattern bareToolJsonPattern() {
        return dev.talos.core.tool.ToolProtocolText.bareToolJsonPattern();
    }

    public static String stripToolCalls(String text) {
        return dev.talos.core.tool.ToolProtocolText.stripToolCalls(text);
    }

    public static boolean looksLikeStandaloneToolJson(String text) {
        return dev.talos.core.tool.ToolProtocolText.looksLikeStandaloneToolJson(text);
    }

    public static boolean looksLikeMalformedProtocolArrayDebris(String text) {
        return dev.talos.core.tool.ToolProtocolText.looksLikeMalformedProtocolArrayDebris(text);
    }

    public static boolean looksLikeMalformedToolProtocol(String text) {
        return dev.talos.core.tool.ToolProtocolText.looksLikeMalformedToolProtocol(text);
    }
}
