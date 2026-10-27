package dev.talos.qualification;

/** Prints the exact compiled Java finalizer identity used by protocol v6. */
public final class QualificationExecutionIdentityMain {
    private QualificationExecutionIdentityMain() {}

    public static void main(String[] args) {
        if (args != null && args.length != 0) {
            throw new IllegalArgumentException("finalizer identity command accepts no arguments");
        }
        System.out.println(QualificationExecutionIdentity.currentFinalizerImplementationSha256());
    }
}
