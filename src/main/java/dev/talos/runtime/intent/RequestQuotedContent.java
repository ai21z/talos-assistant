package dev.talos.runtime.intent;

import java.util.Optional;

/** Complete quoted-content spans in the original request. Returned bytes are never normalized. */
public final class RequestQuotedContent {
    private RequestQuotedContent() {}

    public static Optional<ContentSaveRequest.SourceSpan> startingAt(
            String request, int envelopeStart, int endLimit) {
        if (request == null || envelopeStart < 0 || endLimit < envelopeStart
                || endLimit > request.length()) return Optional.empty();
        RequestLexicalView view = RequestLexicalView.of(request);
        RequestLexicalView.Span span = view.exactQuotedContentStartingAt(envelopeStart, endLimit);
        if (span == null) return Optional.empty();
        RequestLexicalView.Span envelope = view.exactQuotedEnvelope(span.start(), span.end());
        return envelope == null || envelope.end() > endLimit ? Optional.empty()
                : Optional.of(new ContentSaveRequest.SourceSpan(span.start(), span.end()));
    }

    public static boolean startsWithDelimiter(String raw) {
        if (raw == null) return false;
        String value = raw.stripLeading();
        if (value.isEmpty()) return false;
        return "\"'`\u201c\u2018\u00ab\u2039\u300c\u300e\u3010\u301d\u275d\uff02"
                .indexOf(value.charAt(0)) >= 0 || value.startsWith("~~~");
    }

    /** Instruction syntax must lie outside quote-owned data. */
    public static boolean isUnquotedRange(String request, int start, int end) {
        return request != null && start >= 0 && end >= start && end <= request.length()
                && RequestLexicalView.of(request).isUnquotedRange(start, end);
    }
}
