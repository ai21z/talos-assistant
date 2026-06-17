package dev.talos.runtime.toolcall;

import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;

final class ToolResultFormatter {
    private static final int MAX_TOOL_RESULT_CHARS = 32_000;

    private ToolResultFormatter() {}

    static String formatToolResult(ToolCall call, ToolResult result) {
        return formatToolResult(call, result, false);
    }

    static String formatToolResult(ToolCall call, ToolResult result, boolean preserveSuccessOutput) {
        var sb = new StringBuilder();
        sb.append("[tool_result: ").append(call.toolName()).append("]\n");
        if (result.success()) {
            String output = preserveSuccessOutput
                    ? result.output()
                    : ProtectedContentPolicy.sanitizeText(result.output());
            if (output == null || output.isBlank()) {
                sb.append("(empty result)");
            } else if (output.length() > MAX_TOOL_RESULT_CHARS) {
                sb.append(output, 0, MAX_TOOL_RESULT_CHARS);
                sb.append("\n... (output truncated at 32K chars)");
            } else {
                sb.append(output);
            }
            if (result.verification() != null) {
                sb.append("\n[verification_status: ").append(result.verification().name()).append("]");
            }
        } else {
            sb.append("[error] ").append(ProtectedContentPolicy.sanitizeText(result.errorMessage()));
        }
        sb.append("\n[/tool_result]");
        return sb.toString();
    }

    static String extractVerificationSummary(String output) {
        if (output == null) return null;
        int warnIdx = output.indexOf("Warning: ");
        if (warnIdx >= 0) {
            String after = output.substring(warnIdx + 9);
            int tagIdx = after.indexOf(". [verification:");
            return tagIdx >= 0 ? after.substring(0, tagIdx) : after;
        }
        return null;
    }

    static String firstSentenceSummary(String output) {
        if (output == null) return "";
        String s = output.strip();
        if (s.isEmpty()) return "";
        if (s.startsWith("[tool_result:")) {
            int close = s.indexOf(']');
            if (close > 0 && close < s.length() - 1) {
                s = s.substring(close + 1).stripLeading();
            }
        }
        int cut = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                if (i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1))) {
                    cut = i + 1;
                    break;
                }
            } else if (c == '\n') {
                cut = i;
                break;
            }
        }
        String head = cut > 0 ? s.substring(0, cut).strip() : s;
        int bracket = head.indexOf(" [");
        if (bracket > 0) head = head.substring(0, bracket).strip();
        while (!head.isEmpty()) {
            char last = head.charAt(head.length() - 1);
            if (last == '.' || last == '!' || last == '?') {
                head = head.substring(0, head.length() - 1).stripTrailing();
            } else break;
        }
        if (head.length() > 160) head = head.substring(0, 157) + "…";
        return head;
    }
}
