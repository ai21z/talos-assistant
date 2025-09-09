package dev.loqj.core.net;

import dev.loqj.core.Config;
import java.util.List;
import java.util.Map;

public class NetPolicy {
    public final boolean enabled;
    public final boolean readOnly;
    public final List<String> allowDomains;
    public final int maxBytes;

    public NetPolicy(Config cfg) {
        Object netObj = cfg.data.getOrDefault("net", Map.of());
        if (!(netObj instanceof Map)) {
            throw new IllegalArgumentException("'net' config must be a Map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> net = (Map<String, Object>) netObj;

        this.enabled = (Boolean) net.getOrDefault("enabled", false);
        this.readOnly = (Boolean) net.getOrDefault("read_only", true);

        Object allowDomainsObj = net.getOrDefault("allow_domains", List.of());
        if (!(allowDomainsObj instanceof List)) {
            throw new IllegalArgumentException("'allow_domains' must be a List");
        }
        @SuppressWarnings("unchecked")
        List<String> allowDomains = (List<String>) allowDomainsObj;
        this.allowDomains = allowDomains;

        this.maxBytes = ((Number) net.getOrDefault("max_bytes", 5_000_000)).intValue();
    }
}
