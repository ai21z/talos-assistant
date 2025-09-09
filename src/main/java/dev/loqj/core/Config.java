package dev.loqj.core;
 
import org.yaml.snakeyaml.Yaml;
import java.io.*; import java.nio.file.*; import java.util.Map;
 
public class Config {
    public final Map<String,Object> data;
 
    public Config() {
        try {
            Path homeCfg = Paths.get(System.getProperty("user.home"), ".loqj", "config.yaml");
            if (Files.exists(homeCfg)) {
                try (BufferedReader r = Files.newBufferedReader(homeCfg)) {
                    data = new Yaml().load(r);
                    return;
                }
            }
            try (InputStream in = Config.class.getClassLoader().getResourceAsStream("config/default-config.yaml")) {
                if (in == null) throw new FileNotFoundException("classpath:config/default-config.yaml");
                data = new Yaml().load(in);
            }
        } catch (Exception e) { throw new RuntimeException("Failed to load config", e); }
    }
}
