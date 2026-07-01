package dev.talos.core;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Enforces Talos's local-first boundary for model and embedding transports. */
public final class HostLocalityPolicy {
    private HostLocalityPolicy() {}

    public static void enforceLocalOrAllowed(
            String endpointLabel,
            String host,
            boolean allowRemote,
            String allowRemoteSetting) {
        if (isLoopback(host) || allowRemote) {
            return;
        }
        throw new SecurityException("Remote " + endpointLabel + " '" + host
                + "' is not allowed. Set " + allowRemoteSetting
                + "=true to enable remote hosts, or use localhost (127.0.0.1, ::1, or localhost).");
    }

    public static boolean isLoopback(String host) {
        String endpoint = Objects.toString(host, "").trim();
        if (endpoint.isBlank()) {
            return true;
        }
        String normalizedHost = normalizeHost(endpoint);
        if (normalizedHost.isBlank()) {
            return false;
        }
        return isLoopbackHost(normalizedHost);
    }

    private static String normalizeHost(String endpoint) {
        String raw = stripTrailingSlash(endpoint.trim());
        if ("::1".equals(raw) || "0:0:0:0:0:0:0:1".equals(raw)) {
            return raw;
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return raw.substring(1, raw.length() - 1);
        }
        try {
            URI uri = raw.contains("://") ? URI.create(raw) : URI.create("http://" + raw);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            return stripBrackets(host);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = stripBrackets(host).toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || isIpv4Loopback(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    private static boolean isIpv4Loopback(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4 || !"127".equals(parts[0])) {
            return false;
        }
        for (String part : parts) {
            if (part.isBlank()) {
                return false;
            }
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private static String stripBrackets(String value) {
        String out = Objects.toString(value, "").trim();
        if (out.startsWith("[") && out.endsWith("]") && out.length() > 2) {
            return out.substring(1, out.length() - 1);
        }
        return out;
    }

    private static String stripTrailingSlash(String value) {
        String out = Objects.toString(value, "").trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
