package dev.talos.spi;

import java.lang.reflect.InvocationTargetException;

public interface ModelEngineProvider {
    String id();                         // e.g., "ollama"

    default ModelEngine create(EngineConfig cfg) {
        return invokeLegacyConfigMethod("create", cfg, ModelEngine.class);
    }

    default ModelCatalog catalog(EngineConfig cfg) {
        return invokeLegacyConfigMethod("catalog", cfg, ModelCatalog.class);
    }

    private <T> T invokeLegacyConfigMethod(String methodName, EngineConfig cfg, Class<T> returnType) {
        if (cfg == null) {
            cfg = EngineConfig.empty();
        }
        try {
            var legacy = getClass().getMethod(methodName, cfg.getClass());
            Object result = legacy.invoke(this, cfg);
            return returnType.cast(result);
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException(
                    "ModelEngineProvider " + id() + " must implement " + methodName
                            + "(EngineConfig) or a legacy overload for "
                            + cfg.getClass().getName(),
                    e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "ModelEngineProvider " + id() + " has an inaccessible legacy "
                            + methodName + " method",
                    e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(
                    "ModelEngineProvider " + id() + " legacy " + methodName + " method failed",
                    cause);
        }
    }
}
