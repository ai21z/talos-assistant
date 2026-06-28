package dev.talos.cli.doctor;

import dev.talos.core.Config;

/** Verifies the configuration loaded and the user config (if present) parsed. */
public final class ConfigProbe implements DoctorProbe {

    @Override
    public String id() {
        return "config";
    }

    @Override
    public ProbeResult run(DoctorContext ctx) {
        Config.Report report = ctx.cfg().getReport();
        if (report == null) {
            return ProbeResult.warn(id(), "no config load report available");
        }
        return decide(report.loadedFrom, report.userConfigPath, report.userConfigPresent,
                report.userConfigLoaded, report.userConfigError, report.strictMode,
                report.defaultedKeys.size());
    }

    // Discrete parameters (rather than Config.Report) because Report's
    // constructor is package-private to core - this keeps the decision
    // hermetically testable.
    static ProbeResult decide(String loadedFrom,
                              String userConfigPath,
                              boolean userConfigPresent,
                              boolean userConfigLoaded,
                              String userConfigError,
                              boolean strictMode,
                              int defaultedKeyCount) {
        if (userConfigPresent && !userConfigLoaded) {
            return ProbeResult.fail("config",
                    "user config failed to parse: " + userConfigError,
                    "fix the YAML in " + userConfigPath);
        }
        String user = userConfigPresent
                ? "user config loaded"
                : "no user config (built-in defaults)";
        String detail = "loaded from " + loadedFrom + "; " + user
                + "; " + defaultedKeyCount + " defaulted key(s)";
        if (strictMode && defaultedKeyCount > 0) {
            return ProbeResult.warn("config", detail + " under strict mode");
        }
        return ProbeResult.pass("config", detail);
    }
}
