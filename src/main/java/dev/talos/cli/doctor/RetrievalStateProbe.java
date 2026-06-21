package dev.talos.cli.doctor;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.HostLocalityPolicy;
import dev.talos.core.IndexPathResolver;
import dev.talos.safety.ProtectedContentSanitizer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Reports local RAG, vector, and embedding state without probing model inputs. */
public final class RetrievalStateProbe implements DoctorProbe {

    @Override
    public String id() {
        return "retrieval";
    }

    @Override
    public ProbeResult run(DoctorContext ctx) {
        Config cfg = ctx.cfg();
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);
        Map<String, Object> rag = CfgUtil.map(cfg.data.get("rag"));
        Map<String, Object> vectorsCfg = CfgUtil.map(rag.get("vectors"));
        boolean vectorsEnabled = !(vectorsCfg.get("enabled") instanceof Boolean b) || b;

        Map<String, Object> embed = CfgUtil.map(cfg.data.get("embed"));
        String provider = stringAt(embed, "provider", runtime.embeddingProvider());
        String model = stringAt(embed, "model", runtime.embeddingModel());
        String host = embeddingHost(cfg, runtime, provider, embed);
        boolean allowRemote = CfgUtil.boolAt(embed, "allow_remote", false);
        String locality = locality(host, allowRemote);
        String mode = mode(vectorsEnabled, provider, locality);

        Path indexDir = IndexPathResolver.getIndexDirectory(ctx.talosHome(), ctx.workspace());
        boolean indexExists = Files.exists(indexDir);
        String chunks = indexExists ? chunkCount(indexDir) : "0";

        String detail = "index=" + indexDir
                + " exists=" + (indexExists ? "yes" : "no")
                + " chunks=" + chunks
                + " vectors=" + (vectorsEnabled ? "ON" : "OFF")
                + " embedding=" + provider + "/" + model
                + " provider=" + provider
                + " model=" + model
                + " host=" + sanitize(host)
                + " locality=" + locality
                + " mode=" + mode
                + " embedding dimension not probed by doctor"
                + " GPU/VRAM not probed by Talos";

        return "remote-rejected".equals(locality)
                ? ProbeResult.warn(id(), detail)
                : ProbeResult.pass(id(), detail);
    }

    private static String embeddingHost(Config cfg, EngineRuntimeConfig runtime, String provider, Map<String, Object> embed) {
        String configured = stringAt(embed, "host", "");
        if (!configured.isBlank()) return configured;
        if ("ollama".equals(provider)) {
            return stringAt(CfgUtil.map(cfg.data.get("ollama")), "host", "http://127.0.0.1:11434");
        }
        return runtime.hostLabel();
    }

    private static String locality(String host, boolean allowRemote) {
        if (HostLocalityPolicy.isLoopback(host)) return "local";
        return allowRemote ? "remote-allowed" : "remote-rejected";
    }

    private static String mode(boolean vectorsEnabled, String provider, String locality) {
        if (!vectorsEnabled) return "BM25-only (vectors disabled)";
        if ("disabled".equals(provider)) return "BM25-only (embedding provider disabled)";
        if ("remote-rejected".equals(locality)) return "BM25-only fallback likely (embedding host rejected)";
        return "hybrid if embedding probe succeeds";
    }

    private static String chunkCount(Path indexDir) {
        try (var dir = FSDirectory.open(indexDir);
             var reader = DirectoryReader.open(dir)) {
            return String.valueOf(reader.numDocs());
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String stringAt(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        String text = Objects.toString(value, "").trim();
        return text.isBlank() ? Objects.toString(fallback, "") : text;
    }

    private static String sanitize(String value) {
        return ProtectedContentSanitizer.sanitizeText(Objects.toString(value, ""));
    }
}
