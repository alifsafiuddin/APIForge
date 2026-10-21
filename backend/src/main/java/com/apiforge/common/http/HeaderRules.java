package com.apiforge.common.http;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which request headers users may configure (in tests, environments and ad hoc requests).
 * Transport headers belong to the HTTP client, Content-Type to the body, and credentials to the
 * environment's authentication settings, so none of them can be set by hand.
 */
public final class HeaderRules {

    public static final int MAX_VALUE_LENGTH = 4000;
    private static final Pattern TOKEN = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}$");
    private static final Set<String> FORBIDDEN = Set.of("host", "content-length", "transfer-encoding", "connection",
            "keep-alive", "upgrade", "te", "trailer", "expect", "content-type", "authorization", "cookie",
            "proxy-authorization", "proxy-connection");

    private HeaderRules() {
    }

    public static boolean isValidName(String name) {
        return name != null && TOKEN.matcher(name).matches();
    }

    public static boolean isForbidden(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return FORBIDDEN.contains(lower) || lower.startsWith("proxy-");
    }

    /** No CR, LF or NUL (header injection) and a bounded length. */
    public static boolean isValidValue(String value) {
        return value != null && value.length() <= MAX_VALUE_LENGTH
                && value.chars().noneMatch(c -> c == '\r' || c == '\n' || c == 0);
    }
}
