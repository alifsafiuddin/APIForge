package com.apiforge.generation.engine;

import java.util.Collection;
import java.util.Locale;

/** Media type matching as needed for request bodies (parameters such as charset are ignored). */
final class MediaTypes {

    private MediaTypes() {
    }

    /** The type/subtype in lower case, without parameters. */
    static String essence(String mediaType) {
        int semicolon = mediaType.indexOf(';');
        return (semicolon < 0 ? mediaType : mediaType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

    /** application/json and the +json structured syntax suffix. */
    static boolean isJson(String mediaType) {
        String essence = essence(mediaType);
        return essence.equals("application/json") || essence.endsWith("+json");
    }

    /** Whether the documented range (e.g. "text/*", "*&#47;*") covers {@code mediaType}. */
    static boolean covers(String range, String mediaType) {
        String documented = essence(range);
        String actual = essence(mediaType);
        if (documented.equals("*/*") || documented.equals(actual)) {
            return true;
        }
        return documented.endsWith("/*")
                && actual.startsWith(documented.substring(0, documented.length() - 1));
    }

    static boolean anyCovers(Collection<String> ranges, String mediaType) {
        return ranges.stream().anyMatch(range -> covers(range, mediaType));
    }
}
