package com.apiforge.outbound;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

import com.apiforge.outbound.OutboundBlockedException.Reason;
import com.apiforge.outbound.UrlGuard.ParsedUrl;

/**
 * Decides whether a parsed URL may be contacted, and with which addresses.
 *
 * <p>Host names are resolved once; if <em>any</em> answer is in a blocked range the request is
 * refused (so a name returning one public and one private address cannot be used). The validated
 * addresses are the only ones the client may connect to ({@link GuardedDnsResolver}), so a second,
 * different DNS answer (rebinding) is never used. A trusted target (exact host and port, enabled
 * by configuration) may resolve to loopback, private or CGNAT addresses; link-local, metadata and
 * other special ranges stay blocked for everyone.
 */
public class DestinationPolicy {

    /** Resolves a host name; replaceable in tests. */
    @FunctionalInterface
    public interface HostLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    /** A destination that passed every check; connect only to {@code addresses}. */
    public record ValidatedDestination(String host, int port, List<InetAddress> addresses, boolean trusted) {
    }

    private static final Set<String> BLOCKED_NAMES = Set.of("localhost", "metadata", "metadata.google.internal",
            "instance-data", "kubernetes.default", "kubernetes.default.svc");
    private static final List<String> BLOCKED_SUFFIXES = List.of(".localhost", ".internal", ".local", ".svc",
            ".cluster.local", ".home.arpa");

    private final OutboundProperties properties;
    private final HostLookup lookup;

    public DestinationPolicy(OutboundProperties properties, HostLookup lookup) {
        this.properties = properties;
        this.lookup = lookup;
    }

    /** Checks syntax, port, names and every resolved address. */
    public ValidatedDestination check(ParsedUrl url) throws OutboundBlockedException {
        boolean trusted = isTrusted(url);
        checkWithoutLookup(url, trusted);
        List<InetAddress> addresses;
        if (url.isLiteral()) {
            addresses = List.of(url.literal());
        } else {
            try {
                addresses = List.of(lookup.lookup(url.host()));
            } catch (UnknownHostException ex) {
                throw new OutboundBlockedException(Reason.DNS_FAILURE, "The host name '" + url.host() + "' could not be resolved.");
            }
            if (addresses.isEmpty()) {
                throw new OutboundBlockedException(Reason.DNS_FAILURE, "The host name '" + url.host() + "' has no addresses.");
            }
        }
        for (InetAddress address : addresses) {
            requireAllowed(address, trusted, url.isLiteral() ? "The address" : "'" + url.host() + "' resolves to an address that");
        }
        return new ValidatedDestination(url.host(), url.port(), addresses, trusted);
    }

    /**
     * Checks that need no DNS (used when an environment is saved): port, blocked names and IP
     * literals. DNS is checked again at execution time.
     */
    public void checkWithoutLookup(ParsedUrl url) throws OutboundBlockedException {
        boolean trusted = isTrusted(url);
        checkWithoutLookup(url, trusted);
        if (url.isLiteral()) {
            requireAllowed(url.literal(), trusted, "The address");
        }
    }

    /**
     * Defense in depth after connecting: the address the socket actually reached must be allowed.
     * New connections can only use validated addresses (the client's resolver returns nothing else);
     * a reused pooled connection was validated when it was opened, so its peer may legitimately
     * differ from today's DNS answer, but it must still be an allowed address.
     */
    public void requireAllowedPeer(ValidatedDestination destination, InetAddress peer) throws OutboundBlockedException {
        if (peer == null) {
            throw new OutboundBlockedException(Reason.UNEXPECTED_PEER, "The connected address could not be verified.");
        }
        AddressClassifier.Classification classification = AddressClassifier.classify(peer);
        if (classification.blocked() && !(destination.trusted() && classification.trustable())) {
            throw new OutboundBlockedException(Reason.UNEXPECTED_PEER,
                    "The connection reached a " + classification.reason() + ", which is not allowed.");
        }
    }

    private void checkWithoutLookup(ParsedUrl url, boolean trusted) throws OutboundBlockedException {
        if (url.port() != 80 && url.port() != 443 && url.port() < 1024 && !trusted) {
            throw new OutboundBlockedException(Reason.BLOCKED_PORT,
                    "Ports below 1024 other than 80 and 443 are not allowed.");
        }
        if (!trusted && !url.isLiteral() && isBlockedName(url.host())) {
            throw new OutboundBlockedException(Reason.BLOCKED_HOSTNAME,
                    "The host name '" + url.host() + "' refers to a local or internal service and is not allowed.");
        }
    }

    private static void requireAllowed(InetAddress address, boolean trusted, String subject)
            throws OutboundBlockedException {
        AddressClassifier.Classification classification = AddressClassifier.classify(address);
        if (classification.blocked() && !(trusted && classification.trustable())) {
            throw new OutboundBlockedException(Reason.BLOCKED_ADDRESS,
                    subject + " is a " + classification.reason() + " and is not allowed.");
        }
    }

    private boolean isTrusted(ParsedUrl url) {
        if (!properties.trustedTargetsEnabled()) {
            return false;
        }
        return properties.trustedTargets().stream()
                .anyMatch(target -> target.port() == url.port() && normalize(target.host()).equals(url.host()));
    }

    static boolean isBlockedName(String host) {
        return BLOCKED_NAMES.contains(host) || BLOCKED_SUFFIXES.stream().anyMatch(host::endsWith);
    }

    private static String normalize(String host) {
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("[") && lower.endsWith("]") ? lower.substring(1, lower.length() - 1) : lower;
    }
}
