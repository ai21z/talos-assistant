package dev.talos.qualification;

import dev.talos.core.util.RuntimeArtifactIdentity;

import java.util.Map;

/** Prints path-free executing-finalizer values needed by a frozen harness input specification. */
public final class QualificationExecutionInputIdentityMain {
    private QualificationExecutionInputIdentityMain() {}

    public static void main(String[] args) {
        if (args != null && args.length != 0) {
            throw new IllegalArgumentException("execution input identity command accepts no arguments");
        }
        System.out.print(QualificationJson.write(currentIdentity()));
    }

    static Map<String, Object> currentIdentity() {
        RuntimeArtifactIdentity.ClasspathSnapshot classpath =
                QualificationExecutionIdentity.executingFinalizerClasspathSnapshot();
        if (!classpath.exact()) {
            throw new IllegalStateException("executing finalizer classpath identity is unavailable");
        }
        return Map.of(
                "finalizerImplementationSha256",
                QualificationExecutionIdentity.currentFinalizerImplementationSha256(),
                "finalizerClasspathSha256", classpath.sha256(),
                "finalizerClasspathSizeBytes", classpath.sizeBytes(),
                "finalizerClasspathEntryCount", classpath.entryCount());
    }
}
