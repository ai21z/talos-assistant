package dev.talos.tools.impl;

import dev.talos.core.io.AtomicFileWriter;
import dev.talos.tools.TalosTool;
import java.io.IOException;

/** Test-only real producer fault; no production injection switch is exposed. */
public final class MutationEffectTestFixtures {
    private MutationEffectTestFixtures() {}

    public static TalosTool postPromotionWriteFailure() {
        return new FileWriteTool(new AtomicFileWriter((stage, target, temporary) -> {
            if (stage == AtomicFileWriter.FaultStage.AFTER_PROMOTION) {
                throw new IOException("injected post-promotion failure");
            }
        }));
    }
}
