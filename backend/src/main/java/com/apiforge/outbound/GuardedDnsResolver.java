package com.apiforge.outbound;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.hc.client5.http.DnsResolver;

import com.apiforge.outbound.DestinationPolicy.ValidatedDestination;

/**
 * The HTTP client's only name resolver. It never performs a lookup itself: it answers with the
 * addresses that {@link DestinationPolicy} validated for the request being sent on this thread,
 * and refuses every other name. Connections therefore go exactly where the policy allowed, and a
 * DNS answer that changes between check and connect (rebinding) has no effect.
 */
final class GuardedDnsResolver implements DnsResolver {

    private final ThreadLocal<ValidatedDestination> current = new ThreadLocal<>();

    void bind(ValidatedDestination destination) {
        current.set(destination);
    }

    void clear() {
        current.remove();
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        ValidatedDestination destination = current.get();
        if (destination == null || !destination.host().equalsIgnoreCase(stripBrackets(host))) {
            throw new UnknownHostException("Destination was not validated by the outbound policy");
        }
        return destination.addresses().toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
