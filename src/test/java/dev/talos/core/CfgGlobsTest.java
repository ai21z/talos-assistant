package dev.talos.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CfgGlobsTest {

    @Test
    public void defaultConfig_hasGlobs() {
        Config cfg = new Config();
        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
        assertNotNull(rag, "rag section");
        List<?> inc = (List<?>) rag.get("includes");
        List<?> exc = (List<?>) rag.get("excludes");
        assertNotNull(inc, "includes present");
        assertNotNull(exc, "excludes present");
        assertFalse(inc.isEmpty(), "includes non-empty");
        assertFalse(exc.isEmpty(), "excludes non-empty");
    }
}
