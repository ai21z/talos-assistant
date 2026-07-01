package dev.talos.cli.doctor;

/** One environment preflight check. Probes must be fast and side-effect-light. */
public interface DoctorProbe {

    /** Stable identifier shown in the report (e.g. {@code "engine-files"}). */
    String id();

    /** Run the check. Must not throw - return FAIL instead (the engine also guards). */
    ProbeResult run(DoctorContext ctx);
}
