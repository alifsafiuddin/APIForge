package com.apiforge.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AddressClassifierTest {

    private static AddressClassifier.Classification classify(String literal) throws UnknownHostException {
        return AddressClassifier.classify(InetAddress.getByName(literal));
    }

    @ParameterizedTest(name = "{0} is blocked ({2}), trustable={1}")
    @CsvSource(delimiter = '|', textBlock = """
            0.0.0.0              | false | "this host" address
            0.1.2.3              | false | "this host" address
            127.0.0.1            | true  | loopback address
            127.255.255.254      | true  | loopback address
            10.0.0.1             | true  | private network address
            172.16.0.1           | true  | private network address
            172.31.255.255       | true  | private network address
            192.168.1.1          | true  | private network address
            100.64.0.1           | true  | shared (carrier-grade NAT) address
            100.100.100.200      | false | cloud metadata address
            169.254.169.254      | false | link-local or cloud metadata address
            169.254.0.1          | false | link-local or cloud metadata address
            192.0.0.170          | false | IETF protocol assignment
            192.0.2.10           | false | documentation address
            198.18.0.1           | false | benchmarking address
            198.51.100.1         | false | documentation address
            203.0.113.5          | false | documentation address
            224.0.0.1            | false | multicast address
            239.255.255.250      | false | multicast address
            240.0.0.1            | false | reserved or broadcast address
            255.255.255.255      | false | reserved or broadcast address
            ::                   | false | unspecified address
            ::1                  | true  | loopback address
            fe80::1              | false | link-local address
            fec0::1              | false | site-local address
            fc00::1              | true  | unique-local (private) address
            fd12:3456::1         | true  | unique-local (private) address
            fd00:ec2::254        | false | cloud metadata address
            ff02::1              | false | multicast address
            2001:db8::1          | false | documentation address
            2001::1              | false | Teredo tunnel address
            100::1               | false | discard-only address
            """)
    void blockedRanges(String address, boolean trustable, String reason) throws UnknownHostException {
        AddressClassifier.Classification classification = classify(address);

        assertThat(classification.blocked()).isTrue();
        assertThat(classification.trustable()).isEqualTo(trustable);
        assertThat(classification.reason()).isEqualTo(reason);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ::ffff:169.254.169.254   | link-local or cloud metadata address
            ::ffff:127.0.0.1         | loopback address
            ::ffff:a9fe:a9fe         | link-local or cloud metadata address
            ::169.254.169.254        | link-local or cloud metadata address
            ::10.0.0.1               | private network address
            64:ff9b::a9fe:a9fe       | link-local or cloud metadata address
            64:ff9b::7f00:1          | loopback address
            64:ff9b:1::a00:1         | private network address
            2002:a9fe:a9fe::1        | link-local or cloud metadata address
            2002:7f00:1::            | loopback address
            """)
    void ipv4EmbeddedInIpv6IsClassifiedAsIpv4(String address, String reason) throws UnknownHostException {
        assertThat(classify(address).blocked()).isTrue();
        assertThat(classify(address).reason()).isEqualTo(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "1.1.1.1", "172.32.0.1", "172.15.255.255", "100.63.255.255",
            "100.128.0.1", "169.253.255.255", "2606:4700:4700::1111", "2a00:1450:4001::1", "2002:0808:0808::1",
            "64:ff9b::808:808"})
    void publicAddressesAreAllowed(String address) throws UnknownHostException {
        assertThat(classify(address).blocked()).as(address).isFalse();
    }
}
