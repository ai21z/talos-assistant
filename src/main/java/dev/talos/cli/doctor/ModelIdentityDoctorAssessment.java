package dev.talos.cli.doctor;

import dev.talos.spi.types.ModelIdentitySnapshot;

import java.util.Locale;
import java.util.Objects;

/** Converts bounded model identity evidence into one honest doctor verdict. */
record ModelIdentityDoctorAssessment(ProbeResult.Status status, String detail) {

    static ModelIdentityDoctorAssessment assess(ModelIdentitySnapshot identity) {
        ModelIdentitySnapshot safe = identity == null ? ModelIdentitySnapshot.empty() : identity;
        String reasons = safe.reasonCodes().stream()
                .map(ModelIdentityDoctorAssessment::label)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(8)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String profile = firstNonBlank(safe.resolvedProfile(), safe.declaredProfile(), safe.configuredModel());
        String suffix = profile.isBlank() ? "" : ", profile " + profile;
        suffix += fact("artifact", safe.artifactState());
        suffix += fact("serving", safe.servedIdentityState());
        suffix += fact("tools", safe.effectiveToolMode());
        suffix += fact("props", safe.propsState());
        if (!reasons.isBlank()) suffix += ", reasons " + reasons;

        if (safe.qualified()) {
            return new ModelIdentityDoctorAssessment(
                    ProbeResult.Status.PASS,
                    bounded("identity qualified" + suffix));
        }
        if ("REFUSED".equals(safe.resolution())
                || "MISMATCHED".equals(safe.servedIdentityState())
                || "REFUSED".equals(safe.propsState())) {
            return new ModelIdentityDoctorAssessment(
                    ProbeResult.Status.FAIL,
                    bounded("identity refused" + suffix));
        }
        return new ModelIdentityDoctorAssessment(
                ProbeResult.Status.WARN,
                bounded("identity unverified" + suffix));
    }

    private static String fact(String name, String value) {
        String clean = label(value);
        return clean.isBlank() ? "" : ", " + name + " " + clean;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String clean = Objects.toString(value, "").strip();
            if (!clean.isBlank()) return clean;
        }
        return "";
    }

    private static String label(String value) {
        return Objects.toString(value, "")
                .strip()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private static String bounded(String value) {
        String clean = Objects.toString(value, "").replaceAll("[\\r\\n\\t]+", " ").strip();
        return clean.length() <= 320 ? clean : clean.substring(0, 320);
    }
}
