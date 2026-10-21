package com.apiforge.schema;

import java.util.regex.Pattern;

/** JSON paths for reports: {@code /items/2/price} → {@code $.items[2].price}, {@code ""} → {@code $}. */
public final class JsonPaths {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    private JsonPaths() {
    }

    public static String fromPointer(String pointer) {
        StringBuilder path = new StringBuilder("$");
        for (String token : Pointers.tokens(pointer)) {
            if (!token.isEmpty() && token.length() < 10 && token.chars().allMatch(Character::isDigit)) {
                path.append('[').append(token).append(']');
            } else if (IDENTIFIER.matcher(token).matches()) {
                path.append('.').append(token);
            } else {
                path.append("['").append(token.replace("\\", "\\\\").replace("'", "\\'")).append("']");
            }
        }
        return path.toString();
    }
}
