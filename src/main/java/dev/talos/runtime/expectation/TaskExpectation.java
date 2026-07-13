package dev.talos.runtime.expectation;

/** Narrow deterministic expectation derived from an explicit user request. */
public sealed interface TaskExpectation
        permits AppendLineExpectation, AppendSectionExpectation, BulletListExpectation,
        LiteralContentExpectation, ReplacementExpectation {
    String kind();

    String targetPath();

    String sourcePattern();
}
