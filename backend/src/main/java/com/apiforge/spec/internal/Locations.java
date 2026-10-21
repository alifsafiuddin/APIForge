package com.apiforge.spec.internal;

import java.util.List;
import java.util.regex.Pattern;

/** Formats document paths for messages, e.g. {@code paths["/pets/{id}"].get.responses["200"]}. */
final class Locations {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_\\-]*");

    private Locations() {
    }

    static String format(List<String> segments) {
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if (IDENTIFIER.matcher(segment).matches()) {
                if (!builder.isEmpty()) {
                    builder.append('.');
                }
                builder.append(segment);
            } else {
                builder.append("[\"").append(segment.replace("\"", "\\\"")).append("\"]");
            }
        }
        return builder.isEmpty() ? "(document root)" : builder.toString();
    }

    static String operation(String path, String method) {
        return format(List.of("paths", path, method.toLowerCase(java.util.Locale.ROOT)));
    }
}
