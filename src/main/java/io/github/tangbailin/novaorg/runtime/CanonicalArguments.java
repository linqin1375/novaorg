package io.github.tangbailin.novaorg.runtime;

import java.util.*;

/** Canonical recursive argument order prevents duplicate-call guards depending on JSON key order. */
public final class CanonicalArguments {
    private CanonicalArguments() {}
    public static String key(String tool,Map<String,Object> arguments) {
        return tool+"|"+io.github.tangbailin.novaorg.persistence.JsonCodec.write(sort(arguments));
    }
    private static Object sort(Object value) {
        if (value instanceof Map<?,?> map) {
            var sorted=new TreeMap<String,Object>();
            map.forEach((k,v)->sorted.put(String.valueOf(k),sort(v)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(CanonicalArguments::sort).toList();
        if (value instanceof Number n) return new java.math.BigDecimal(n.toString()).stripTrailingZeros();
        return value;
    }
}
