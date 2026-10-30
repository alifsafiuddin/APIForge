package com.apiforge.common.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for JSON values held as plain Java objects ({@code Map}, {@code List}, {@code String},
 * {@code Number}, {@code Boolean}, {@code null}), as produced by Jackson and stored in jsonb.
 */
public final class JsonValues {

    private JsonValues() {
    }

    /**
     * JSON equality: object member order is irrelevant and numbers compare by value, so
     * {@code 1}, {@code 1L} and {@code 1.0} are equal. A value read back from the database
     * therefore equals the value that was written.
     */
    public static boolean jsonEquals(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : a.entrySet()) {
                if (!b.containsKey(entry.getKey()) || !jsonEquals(entry.getValue(), b.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int index = 0; index < a.size(); index++) {
                if (!jsonEquals(a.get(index), b.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left == null ? right == null : left.equals(right);
    }

    /** Exact decimal value of any JSON number. */
    public static BigDecimal decimal(Number number) {
        return switch (number) {
            case BigDecimal value -> value;
            case BigInteger value -> new BigDecimal(value);
            case Long value -> BigDecimal.valueOf(value);
            case Integer value -> BigDecimal.valueOf(value);
            case Short value -> BigDecimal.valueOf(value);
            case Byte value -> BigDecimal.valueOf(value);
            default -> new BigDecimal(number.toString());
        };
    }

    /** Deep, mutable copy of a JSON value; object keys become strings, member order is kept. */
    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, member) -> copy.put(String.valueOf(key), deepCopy(member)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(element -> copy.add(deepCopy(element)));
            return copy;
        }
        return value;
    }

    /** As {@link #deepCopy} for an object; {@code null} or a non-object gives an empty map. */
    public static Map<String, Object> copyObject(Object value) {
        return deepCopy(value) instanceof Map<?, ?> map ? stringKeyed(map) : new LinkedHashMap<>();
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /** The JSON type name of a value: object, array, string, number, boolean or null. */
    public static String typeName(Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> ignored -> "object";
            case List<?> ignored -> "array";
            case String ignored -> "string";
            case Boolean ignored -> "boolean";
            case Number ignored -> "number";
            default -> value.getClass().getSimpleName();
        };
    }
}
