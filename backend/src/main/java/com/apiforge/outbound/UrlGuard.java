package com.apiforge.outbound;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

import com.apiforge.outbound.OutboundBlockedException.Reason;

/**
 * Strict parsing of target URLs, which are untrusted input. Only absolute {@code http}/{@code https}
 * URLs with a plain host are accepted. Anything a parser could read in more than one way is
 * rejected rather than interpreted: user info, backslashes, percent-encoded or non-LDH host names,
 * IPv6 zone ids, and every non-canonical way of writing an IPv4 address ({@code 0x7f.1},
 * {@code 017700000001}, {@code 2130706433}, {@code 127.1}, ...). Internationalized host names
 * must be given in their ASCII (punycode) form, so only one parser ever interprets a host.
 */
public final class UrlGuard {

    /** A URL that passed syntax checks; {@code host} is lower-case ASCII or a bare IP literal. */
    public record ParsedUrl(URI uri, String scheme, String host, int port, InetAddress literal) {

        public boolean isLiteral() {
            return literal != null;
        }

        /** scheme://host:port, used to decide whether a redirect changes origin. */
        public String origin() {
            return scheme + "://" + host + ":" + port;
        }
    }

    static final int MAX_URL_LENGTH = 4096;
    private static final Pattern LABEL = Pattern.compile("^(?!-)[a-z0-9-]{1,63}(?<!-)$");
    private static final Pattern LAST_LABEL_NUMERIC = Pattern.compile("^(0x[0-9a-f]*|[0-9]+)$");
    private static final Pattern CANONICAL_IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private UrlGuard() {
    }

    public static ParsedUrl parse(String url) throws OutboundBlockedException {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH) {
            throw invalid("The URL is empty or longer than " + MAX_URL_LENGTH + " characters.");
        }
        for (int index = 0; index < url.length(); index++) {
            char character = url.charAt(index);
            if (character <= 0x20 || character == 0x7F || character == '\\') {
                throw invalid("The URL contains whitespace, control characters or backslashes.");
            }
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw invalid("The URL is not valid.");
        }
        if (uri.getScheme() == null) {
            throw invalid("The URL must be absolute, for example https://api.example.com.");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new OutboundBlockedException(Reason.UNSUPPORTED_PROTOCOL,
                    "Only http and https URLs are allowed (got '" + truncate(scheme) + "').");
        }
        if (uri.isOpaque()) {
            throw invalid("The URL must be absolute, for example https://api.example.com.");
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            throw invalid("The URL has no host.");
        }
        if (authority.contains("@") || uri.getRawUserInfo() != null) {
            throw new OutboundBlockedException(Reason.CREDENTIALS_IN_URL,
                    "Credentials in the URL are not allowed; configure authentication on the environment.");
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isEmpty()) {
            throw invalid("The URL's host is not a valid host name or IP address.");
        }
        int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        // The authority must be exactly host[:port]: no room for parser differentials.
        String expected = uri.getPort() == -1 ? rawHost : rawHost + ":" + uri.getPort();
        if (!authority.equalsIgnoreCase(expected)) {
            throw invalid("The URL's authority is malformed.");
        }
        if (port < 1 || port > 65535) {
            throw invalid("The port is out of range.");
        }

        if (rawHost.startsWith("[")) {
            return new ParsedUrl(uri, scheme, rawHost.substring(1, rawHost.length() - 1).toLowerCase(Locale.ROOT), port,
                    ipv6Literal(rawHost));
        }
        String host = normalizeHostName(rawHost);
        String[] labels = host.split("\\.", -1);
        if (LAST_LABEL_NUMERIC.matcher(labels[labels.length - 1]).matches()) {
            // A name whose last label is numeric is an attempt at an IPv4 literal: only the canonical
            // dotted-quad form is accepted, so every resolver reads it the same way.
            if (!CANONICAL_IPV4.matcher(host).matches()) {
                throw new OutboundBlockedException(Reason.NON_CANONICAL_ADDRESS,
                        "IP addresses must be written as four decimal numbers, e.g. 203.0.113.10.");
            }
            return new ParsedUrl(uri, scheme, host, port, ipv4Literal(host));
        }
        return new ParsedUrl(uri, scheme, host, port, null);
    }

    private static String normalizeHostName(String rawHost) throws OutboundBlockedException {
        if (rawHost.contains("%")) {
            throw invalid("Percent-encoded host names are not allowed.");
        }
        String ascii;
        try {
            ascii = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw invalid("The host name is not valid.");
        }
        if (ascii.endsWith(".")) {
            ascii = ascii.substring(0, ascii.length() - 1);
        }
        if (ascii.isEmpty() || ascii.length() > 253) {
            throw invalid("The host name is not valid.");
        }
        for (String label : ascii.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                throw invalid("The host name is not valid.");
            }
        }
        return ascii;
    }

    private static InetAddress ipv6Literal(String bracketed) throws OutboundBlockedException {
        String text = bracketed.substring(1, bracketed.length() - 1);
        if (text.contains("%")) {
            throw invalid("IPv6 zone identifiers are not allowed.");
        }
        if (!text.matches("^[0-9a-fA-F:.]+$")) {
            throw invalid("The IPv6 address is not valid.");
        }
        try {
            return InetAddress.getByName(text); // a literal: no name lookup
        } catch (UnknownHostException ex) {
            throw invalid("The IPv6 address is not valid.");
        }
    }

    private static InetAddress ipv4Literal(String dottedQuad) throws OutboundBlockedException {
        String[] parts = dottedQuad.split("\\.");
        byte[] bytes = new byte[4];
        for (int index = 0; index < 4; index++) {
            bytes[index] = (byte) Integer.parseInt(parts[index]);
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException ex) {
            throw invalid("The IP address is not valid.");
        }
    }

    private static OutboundBlockedException invalid(String message) {
        return new OutboundBlockedException(Reason.INVALID_URL, message);
    }

    private static String truncate(String text) {
        return text.length() > 20 ? text.substring(0, 20) + "…" : text;
    }
}
