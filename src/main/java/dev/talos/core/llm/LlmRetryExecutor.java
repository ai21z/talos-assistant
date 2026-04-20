package dev.talos.core.llm;

import dev.talos.spi.EngineException;

final class LlmRetryExecutor {

    @FunctionalInterface
    interface Attempt<T> {
        T run() throws Exception;
    }

    private LlmRetryExecutor() {}

    static <T> T execute(int maxRetries, Attempt<T> attempt) {
        EngineException.Transient lastTransient = null;
        for (int tryNumber = 0; tryNumber <= maxRetries; tryNumber++) {
            if (tryNumber > 0) backoff(tryNumber);
            try {
                return attempt.run();
            } catch (EngineException.Transient transientFailure) {
                lastTransient = transientFailure;
            } catch (EngineException engineFailure) {
                throw engineFailure;
            } catch (Exception e) {
                throw new EngineException.ResponseError(0, e.getMessage(), e);
            }
        }
        throw lastTransient;
    }

    private static void backoff(int tryNumber) {
        try {
            Thread.sleep(tryNumber * 400L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
