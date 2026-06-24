package dev.talos.runtime.command;

import java.util.List;
import java.util.Locale;

/** Host platform facts used by command planning and bounded process launch. */
record CommandRuntimePlatform(boolean windowsHost) {
    private static final List<String> WINDOWS_ENV_ALLOWLIST = List.of(
            "SystemRoot", "WINDIR", "ComSpec", "PATHEXT", "TEMP", "TMP", "JAVA_HOME", "PATH");
    private static final List<String> POSIX_ENV_ALLOWLIST = List.of(
            "HOME", "LANG", "LC_ALL", "TMPDIR", "JAVA_HOME", "PATH");

    static CommandRuntimePlatform current() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).contains("win")
                ? windows()
                : posix();
    }

    static CommandRuntimePlatform windows() {
        return new CommandRuntimePlatform(true);
    }

    static CommandRuntimePlatform posix() {
        return new CommandRuntimePlatform(false);
    }

    String gradleWrapperExecutable() {
        return windowsHost ? ".\\gradlew.bat" : "./gradlew";
    }

    List<String> environmentAllowlist() {
        return windowsHost ? WINDOWS_ENV_ALLOWLIST : POSIX_ENV_ALLOWLIST;
    }
}
