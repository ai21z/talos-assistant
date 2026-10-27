package dev.talos.core.diff;

import dev.talos.core.privacy.DocumentContentDecision;
import dev.talos.safety.ContentRestrictions;
import java.util.Objects;
import java.util.UUID;

/** Ordered captured identities are bound to one issuing turn, without captured text. */
public final class DocumentPairReceipt {
    private final UUID turn;
    private final DocumentPairAdmission.SourceIdentity left;
    private final DocumentPairAdmission.SourceIdentity right;
    private final DocumentContentDecision decision;

    private DocumentPairReceipt(UUID turn, DocumentPairAdmission.CapturedPair pair) {
        this.turn = Objects.requireNonNull(turn);
        Objects.requireNonNull(pair);
        left = pair.leftIdentity();
        right = pair.rightIdentity();
        decision = pair.contentDecision();
    }
    public static DocumentPairReceipt bind(UUID turn, DocumentPairAdmission.CapturedPair pair) {
        return new DocumentPairReceipt(turn, pair);
    }
    public UUID turn() { return turn; }
    public DocumentPairAdmission.SourceIdentity left() { return left; }
    public DocumentPairAdmission.SourceIdentity right() { return right; }
    public DocumentContentDecision decision() { return decision; }
    public ContentRestrictions restrictions() {
        return new ContentRestrictions(decision.modelHandoffAllowed(),
                decision.rawArtifactPersistenceAllowed(), !decision.privateDocumentContent(),
                decision.ragIndexAllowed());
    }
    @Override public String toString() { return "DocumentPairReceipt[ordered, content withheld]"; }
}
