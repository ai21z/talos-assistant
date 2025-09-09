package dev.loqj.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class CfgUtilTest {

    @Test
    void parsesNumbersAndLists() {
        Map<String,Object> m = Map.of(
                "i", 42,
                "d", "3.14",
                "list", List.of("a", 2, true)
        );
        assertEquals(42, CfgUtil.intAt(m, "i", 0));
        assertEquals(3.14, CfgUtil.doubleAt(m, "d", 0.0), 1e-6);
        assertEquals(List.of("a","2","true"), CfgUtil.strList(m.get("list")));
        assertEquals(7, CfgUtil.intAt(m, "missing", 7));
    }
}
