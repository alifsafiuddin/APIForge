package com.apiforge.outbound;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Classifies destination addresses (docs/SECURITY.md section 6.2). IPv4 addresses embedded in
 * IPv6 (mapped, compatible, NAT64, 6to4) are extracted and classified as IPv4, so the blocked
 * ranges cannot be reached through an alternative notation.
 */
public final class AddressClassifier {

    /**
     * @param blocked    not allowed by default
     * @param trustable  may be allowed for an explicitly trusted development target (loopback,
     *                   private and CGNAT ranges); link-local, metadata and special ranges never are
     * @param reason     human-readable category, without the address itself
     */
    public record Classification(boolean blocked, boolean trustable, String reason) {

        static Classification allowed() {
            return new Classification(false, false, "public address");
        }
    }

    private record Range(byte[] network, int prefix, boolean trustable, String reason) {

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            int remainingBits = prefix % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    /** Individual metadata addresses inside otherwise trustable ranges. */
    private static final List<byte[]> METADATA = List.of(
            literal("100.100.100.200"), // Alibaba Cloud
            literal("fd00:ec2::254")); // AWS IPv6

    private static final List<Range> IPV4 = List.of(
            range("0.0.0.0", 8, false, "\"this host\" address"),
            range("127.0.0.0", 8, true, "loopback address"),
            range("10.0.0.0", 8, true, "private network address"),
            range("172.16.0.0", 12, true, "private network address"),
            range("192.168.0.0", 16, true, "private network address"),
            range("100.64.0.0", 10, true, "shared (carrier-grade NAT) address"),
            range("169.254.0.0", 16, false, "link-local or cloud metadata address"),
            range("192.0.0.0", 24, false, "IETF protocol assignment"),
            range("192.0.2.0", 24, false, "documentation address"),
            range("198.18.0.0", 15, false, "benchmarking address"),
            range("198.51.100.0", 24, false, "documentation address"),
            range("203.0.113.0", 24, false, "documentation address"),
            range("224.0.0.0", 4, false, "multicast address"),
            range("240.0.0.0", 4, false, "reserved or broadcast address"));

    private static final List<Range> IPV6 = List.of(
            range("::", 128, false, "unspecified address"),
            range("::1", 128, true, "loopback address"),
            range("fe80::", 10, false, "link-local address"),
            range("fec0::", 10, false, "site-local address"),
            range("fc00::", 7, true, "unique-local (private) address"),
            range("ff00::", 8, false, "multicast address"),
            range("2001:db8::", 32, false, "documentation address"),
            range("2001::", 32, false, "Teredo tunnel address"),
            range("100::", 64, false, "discard-only address"));

    private AddressClassifier() {
    }

    public static Classification classify(InetAddress address) {
        byte[] bytes = address.getAddress();
        for (byte[] metadata : METADATA) {
            if (Arrays.equals(bytes, metadata)) {
                return new Classification(true, false, "cloud metadata address");
            }
        }
        if (address instanceof Inet6Address) {
            byte[] embedded = embeddedIpv4(bytes);
            if (embedded != null) {
                return classify(toAddress(embedded));
            }
            return match(bytes, IPV6);
        }
        return match(bytes, IPV4);
    }

    private static Classification match(byte[] bytes, List<Range> ranges) {
        for (Range range : ranges) {
            if (range.contains(bytes)) {
                return new Classification(true, range.trustable(), range.reason());
            }
        }
        return Classification.allowed();
    }

    /**
     * The IPv4 address carried inside an IPv6 address: IPv4-mapped ({@code ::ffff:a.b.c.d}),
     * IPv4-compatible ({@code ::a.b.c.d}), NAT64 ({@code 64:ff9b::/96}, {@code 64:ff9b:1::/48})
     * or 6to4 ({@code 2002:wwxx:yyzz::/16}); otherwise {@code null}.
     */
    static byte[] embeddedIpv4(byte[] v6) {
        boolean firstTenZero = true;
        for (int index = 0; index < 10; index++) {
            firstTenZero &= v6[index] == 0;
        }
        if (firstTenZero && ((v6[10] == (byte) 0xFF && v6[11] == (byte) 0xFF) || (v6[10] == 0 && v6[11] == 0
                && !(v6[12] == 0 && v6[13] == 0 && v6[14] == 0 && (v6[15] == 0 || v6[15] == 1))))) {
            return Arrays.copyOfRange(v6, 12, 16);
        }
        if (v6[0] == 0 && v6[1] == 0x64 && v6[2] == (byte) 0xFF && v6[3] == (byte) 0x9B) {
            boolean wellKnown = true;
            for (int index = 4; index < 12; index++) {
                wellKnown &= v6[index] == 0;
            }
            if (wellKnown) {
                return Arrays.copyOfRange(v6, 12, 16);
            }
            if (v6[4] == 0 && v6[5] == 1) {
                return Arrays.copyOfRange(v6, 12, 16); // 64:ff9b:1::/48 local-use NAT64
            }
        }
        if (v6[0] == 0x20 && v6[1] == 0x02) {
            return Arrays.copyOfRange(v6, 2, 6);
        }
        return null;
    }

    private static InetAddress toAddress(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private static Range range(String network, int prefix, boolean trustable, String reason) {
        return new Range(literal(network), prefix, trustable, reason);
    }

    /** Parses an IP literal without any name lookup. */
    static byte[] literal(String text) {
        try {
            InetAddress address = InetAddress.getByName(text);
            if (address instanceof Inet4Address || address instanceof Inet6Address) {
                return address.getAddress();
            }
            throw new IllegalArgumentException(text);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException(text, ex);
        }
    }
}
