package dev.loqj.core;

import java.util.*;

public final class CfgUtil {
    private CfgUtil() {}

    @SuppressWarnings("unchecked")
    public static Map<String,Object> map(Object o) {
        if (o == null) return Map.of();
        if (o instanceof Map<?,?> m) return (Map<String,Object>) m;
        return Map.of();
    }

    public static int intAt(Map<String,Object> m, String key, int def) {
        Object o = m.get(key);
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s.trim()); } catch (Exception ignore) {} }
        return def;
    }

    public static double doubleAt(Map<String,Object> m, String key, double def) {
        Object o = m.get(key);
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) { try { return Double.parseDouble(s.trim()); } catch (Exception ignore) {} }
        return def;
    }

    public static List<String> strList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object e : list) if (e != null) out.add(e.toString());
            return out;
        }
        return List.of();
    }
}
