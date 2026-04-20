package dev.talos.cli.launcher;

import dev.talos.core.Config;
import dev.talos.core.net.NetPolicy;
import picocli.CommandLine;

import java.util.stream.Collectors;

@CommandLine.Command(name="net", description="Show effective network policy")
public class NetCmd implements Runnable {
    @Override public void run() {
        var cfg = new Config();
        var np  = new NetPolicy(cfg);
        String allow = np.allowDomains.isEmpty()
                ? "(none)"
                : np.allowDomains.stream().collect(Collectors.joining(", "));
        System.out.println("Network policy:");
        System.out.println("  enabled     : " + np.enabled);
        System.out.println("  read_only   : " + np.readOnly);
        System.out.println("  max_bytes   : " + np.maxBytes);

        System.out.println("  allowDomains: " + allow);
    }
}
