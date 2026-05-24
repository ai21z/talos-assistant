package dev.talos.runtime.verification;

import dev.talos.runtime.expectation.AppendLineExpectation;
import dev.talos.runtime.expectation.BulletListExpectation;
import dev.talos.runtime.expectation.ExpectationVerificationStatus;
import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.trace.LocalTurnTraceCapture;

/** Formats redaction-safe task expectation trace events. */
final class TaskExpectationTraceRecorder {

    private TaskExpectationTraceRecorder() {}

    static void recordLiteralExpectation(
            LiteralContentExpectation expectation,
            ExpectationVerificationStatus status,
            String observedContent
    ) {
        LocalTurnTraceCapture.recordExpectationVerified(
                expectation.kind(),
                status == null ? "" : status.name(),
                expectation.targetPath(),
                expectation.sourcePattern(),
                expectation.expectedHash(),
                expectation.expectedBytes(),
                expectation.expectedChars(),
                expectation.expectedLines(),
                LiteralContentExpectation.hash(observedContent),
                LiteralContentExpectation.byteCount(observedContent),
                LiteralContentExpectation.charCount(observedContent),
                LiteralContentExpectation.lineCount(observedContent));
    }

    static void recordReplacementExpectation(
            ReplacementExpectation expectation,
            ExpectationVerificationStatus status,
            boolean oldPresent,
            boolean newPresent
    ) {
        String observedState = "oldPresent:" + oldPresent + ";newPresent:" + newPresent;
        LocalTurnTraceCapture.recordExpectationVerified(
                expectation == null ? "TEXT_REPLACEMENT" : expectation.kind(),
                status == null ? "" : status.name(),
                expectation == null ? "" : expectation.targetPath(),
                expectation == null ? "" : expectation.sourcePattern(),
                expectation == null ? "" : "old:" + expectation.oldHash() + ";new:" + expectation.newHash(),
                expectation == null ? 0 : expectation.newBytes(),
                expectation == null ? 0 : expectation.newChars(),
                0,
                LiteralContentExpectation.hash(observedState),
                0,
                0,
                0);
    }

    static void recordAppendLineExpectation(
            AppendLineExpectation expectation,
            ExpectationVerificationStatus status,
            String observedFinalLine
    ) {
        String observed = observedFinalLine == null ? "" : observedFinalLine;
        LocalTurnTraceCapture.recordExpectationVerified(
                expectation == null ? "APPEND_LINE" : expectation.kind(),
                status == null ? "" : status.name(),
                expectation == null ? "" : expectation.targetPath(),
                expectation == null ? "" : expectation.sourcePattern(),
                expectation == null ? "" : expectation.expectedHash(),
                expectation == null ? 0 : expectation.expectedBytes(),
                expectation == null ? 0 : expectation.expectedChars(),
                1,
                LiteralContentExpectation.hash(observed),
                LiteralContentExpectation.byteCount(observed),
                LiteralContentExpectation.charCount(observed),
                observed.isBlank() ? 0 : 1);
    }

    static void recordBulletListExpectation(
            BulletListExpectation expectation,
            ExpectationVerificationStatus status,
            int observedCount
    ) {
        int expectedCount = expectation == null ? 0 : expectation.expectedBulletCount();
        LocalTurnTraceCapture.recordExpectationVerified(
                expectation == null ? "BULLET_LIST_COUNT" : expectation.kind(),
                status == null ? "" : status.name(),
                expectation == null ? "" : expectation.targetPath(),
                expectation == null ? "" : expectation.sourcePattern(),
                "count:" + expectedCount,
                0,
                0,
                expectedCount,
                "count:" + Math.max(0, observedCount),
                0,
                0,
                Math.max(0, observedCount));
    }
}
