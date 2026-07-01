package dev.talos.cli.doctor;

import java.util.Objects;

/**
 * Outcome of one doctor preflight probe.
 *
 * @param id     stable probe identifier (e.g. {@code "server"})
 * @param status PASS / WARN / FAIL / SKIP
 * @param detail one-line human-readable finding
 * @param hint   how to fix it (rendered only for FAIL; may be blank)
 */
public record ProbeResult(String id, Status status, String detail, String hint) {

    public enum Status { PASS, WARN, FAIL, SKIP }

    public ProbeResult {
        id = Objects.toString(id, "");
        status = status == null ? Status.FAIL : status;
        detail = Objects.toString(detail, "");
        hint = Objects.toString(hint, "");
    }

    public static ProbeResult pass(String id, String detail) {
        return new ProbeResult(id, Status.PASS, detail, "");
    }

    public static ProbeResult warn(String id, String detail) {
        return new ProbeResult(id, Status.WARN, detail, "");
    }

    public static ProbeResult fail(String id, String detail, String hint) {
        return new ProbeResult(id, Status.FAIL, detail, hint);
    }

    public static ProbeResult skip(String id, String detail) {
        return new ProbeResult(id, Status.SKIP, detail, "");
    }
}
