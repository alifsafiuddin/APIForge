package com.apiforge.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Pointer (RFC 6901) navigation over JSON values, plus readable field names. Updates are
 * copy-on-write: the containers along the pointer are copied, the input is never changed.
 */
public final class Pointers {

    private Pointers() {
    }

    public static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    public static String unescape(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    public static String child(String pointer, String token) {
        return pointer + "/" + escape(token);
    }

    public static List<String> tokens(String pointer) {
        List<String> tokens = new ArrayList<>();
        if (pointer.isEmpty()) {
            return tokens;
        }
        for (String token : pointer.substring(1).split("/", -1)) {
            tokens.add(unescape(token));
        }
        return tokens;
    }

    /** A copy of {@code root} with the value at {@code pointer} replaced (the empty pointer replaces the root). */
    public static Object with(Object root, String pointer, Object value) {
        return update(root, tokens(pointer), 0, value, false);
    }

    /** A copy of {@code root} without the object member at {@code pointer}. */
    public static Object without(Object root, String pointer) {
        return update(root, tokens(pointer), 0, null, true);
    }

    public static Object get(Object root, String pointer) {
        Object current = root;
        for (String token : tokens(pointer)) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(token);
            } else if (current instanceof List<?> list && isIndex(token, list.size())) {
                current = list.get(Integer.parseInt(token));
            } else {
                return null;
            }
        }
        return current;
    }

    private static Object update(Object node, List<String> tokens, int index, Object value, boolean remove) {
        if (index == tokens.size()) {
            return value;
        }
        String token = tokens.get(index);
        boolean last = index == tokens.size() - 1;
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, member) -> copy.put(String.valueOf(key), member));
            if (last && remove) {
                copy.remove(token);
            } else {
                copy.put(token, update(copy.get(token), tokens, index + 1, value, remove));
            }
            return copy;
        }
        if (node instanceof List<?> list && isIndex(token, list.size())) {
            List<Object> copy = new ArrayList<>(list);
            int position = Integer.parseInt(token);
            copy.set(position, update(copy.get(position), tokens, index + 1, value, remove));
            return copy;
        }
        throw new IllegalArgumentException("No container for '" + token + "'");
    }

    private static boolean isIndex(String token, int size) {
        return !token.isEmpty() && token.length() < 10 && token.chars().allMatch(Character::isDigit)
                && Integer.parseInt(token) < size;
    }

    private static boolean isNumeric(String token) {
        return !token.isEmpty() && token.chars().allMatch(Character::isDigit);
    }

    /** "/category/id" → "category.id", "/tags/0/name" → "tags[0].name", "/0/id" → "body[0].id", "" → "request body". */
    public static String fieldName(String pointer) {
        if (pointer.isEmpty()) {
            return "request body";
        }
        StringBuilder name = new StringBuilder();
        List<String> tokens = tokens(pointer);
        if (!tokens.isEmpty() && isNumeric(tokens.getFirst())) {
            name.append("body"); // items of an array-valued request body
        }
        for (String token : tokens) {
            if (isNumeric(token)) {
                name.append('[').append(token).append(']');
            } else {
                if (!name.isEmpty()) {
                    name.append('.');
                }
                name.append(token);
            }
        }
        return name.toString();
    }
}
