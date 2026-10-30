package com.apiforge.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import com.apiforge.outbound.DestinationPolicy.ValidatedDestination;
import com.apiforge.outbound.OutboundBlockedException.Reason;
import com.apiforge.outbound.OutboundProperties.TrustedTarget;

class DestinationPolicyTest {

    private final Map<String, List<String>> dns = new HashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();

    private DestinationPolicy policy(TrustedTarget... trusted) {
        return new DestinationPolicy(OutboundTestSupport.properties(List.of(trusted)), host -> {
            lookups.incrementAndGet();
            List<String> answers = dns.get(host);
            if (answers == null) {
                throw new UnknownHostException(host);
            }
            InetAddress[] addresses = new InetAddress[answers.size()];
            for (int index = 0; index < answers.size(); index++) {
                addresses[index] = InetAddress.getByAddress(host, AddressClassifier.literal(answers.get(index)));
            }
            return addresses;
        });
    }

    private static Reason blocked(DestinationPolicy policy, String url) {
        OutboundBlockedException ex = catchThrowableOfType(OutboundBlockedException.class,
                () -> policy.check(UrlGuard.parse(url)));
        assertThat(ex).as("expected %s to be blocked", url).isNotNull();
        return ex.reason();
    }

    @Test
    void publicNameIsAllowedWithAllItsAddresses() throws Exception {
        dns.put("api.example.com", List.of("93.184.216.34", "2606:2800:220:1::1"));

        ValidatedDestination destination = policy().check(UrlGuard.parse("https://api.example.com/v1"));

        assertThat(destination.addresses()).extracting(InetAddress::getHostAddress)
                .containsExactly("93.184.216.34", "2606:2800:220:1:0:0:0:1");
        assertThat(destination.trusted()).isFalse();
        assertThat(lookups.get()).as("one lookup per check").isEqualTo(1);
    }

    @Test
    void oneBlockedAnswerBlocksTheWholeName() {
        dns.put("mixed.example.com", List.of("93.184.216.34", "10.0.0.5"));

        assertThat(blocked(policy(), "https://mixed.example.com/")).isEqualTo(Reason.BLOCKED_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"169.254.169.254", "127.0.0.1", "10.1.2.3", "192.168.0.10", "::1", "fd00:ec2::254",
            "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "::ffff:0:a9fe:a9fe", "0.0.0.0"})
    void namesResolvingToBlockedAddressesAreRefused(String address) {
        dns.put("rebind.example.com", List.of(address));

        assertThat(blocked(policy(), "http://rebind.example.com/")).isEqualTo(Reason.BLOCKED_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/", "http://169.254.169.254/latest/meta-data/", "http://[::1]/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:a9fe:a9fe]/", "http://10.0.0.1/", "http://0.0.0.0/",
            "http://[fe80::1]/", "http://224.0.0.1/", "http://[::ffff:0:7f00:1]/", "http://[::ffff:0:169.254.169.254]/"})
    void blockedLiteralsAreRefusedWithoutDns(String url) {
        assertThat(blocked(policy(), url)).isEqualTo(Reason.BLOCKED_ADDRESS);
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/", "http://LOCALHOST./", "http://api.localhost/", "http://metadata/",
            "http://metadata.google.internal/", "http://db.internal/", "http://printer.local/",
            "http://kubernetes.default.svc/", "http://instance-data/"})
    void localAndInternalNamesAreRefusedBeforeDns(String url) {
        assertThat(blocked(policy(), url)).isEqualTo(Reason.BLOCKED_HOSTNAME);
        assertThat(lookups.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {21, 22, 25, 110, 389, 445, 873})
    void privilegedPortsOtherThanWebPortsAreRefused(int port) {
        dns.put("api.example.com", List.of("93.184.216.34"));

        assertThat(blocked(policy(), "http://api.example.com:" + port + "/")).isEqualTo(Reason.BLOCKED_PORT);
    }

    @Test
    void unknownNamesAreReportedAsDnsFailures() {
        assertThat(blocked(policy(), "https://does-not-exist.example/")).isEqualTo(Reason.DNS_FAILURE);
    }

    @Test
    void trustedTargetIsMatchedOnExactHostAndPort() throws Exception {
        dns.put("localhost", List.of("127.0.0.1"));
        DestinationPolicy policy = policy(new TrustedTarget("localhost", 8090), new TrustedTarget("127.0.0.1", 8090));

        assertThat(policy.check(UrlGuard.parse("http://localhost:8090/api")).trusted()).isTrue();
        assertThat(policy.check(UrlGuard.parse("http://127.0.0.1:8090/")).trusted()).isTrue();
        assertThat(blocked(policy, "http://localhost:8091/")).isEqualTo(Reason.BLOCKED_HOSTNAME);
        assertThat(blocked(policy, "http://127.0.0.1:5432/")).isEqualTo(Reason.BLOCKED_ADDRESS);
        assertThat(blocked(policy, "http://[::1]:8090/")).isEqualTo(Reason.BLOCKED_ADDRESS);
    }

    @Test
    void trustCannotUnlockLinkLocalOrMetadataAddresses() {
        dns.put("demo.example", List.of("169.254.169.254"));
        dns.put("ali.example", List.of("100.100.100.200"));
        DestinationPolicy policy = policy(new TrustedTarget("demo.example", 8080), new TrustedTarget("ali.example", 8080));

        assertThat(blocked(policy, "http://demo.example:8080/")).isEqualTo(Reason.BLOCKED_ADDRESS);
        assertThat(blocked(policy, "http://ali.example:8080/")).isEqualTo(Reason.BLOCKED_ADDRESS);
    }

    @Test
    void trustedTargetsAreIgnoredWhenDisabled() {
        OutboundProperties disabled = new OutboundProperties(false, List.of(new TrustedTarget("127.0.0.1", 8090)), false,
                java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(5),
                org.springframework.util.unit.DataSize.ofKilobytes(64), org.springframework.util.unit.DataSize.ofKilobytes(16),
                org.springframework.util.unit.DataSize.ofKilobytes(256), 3, 100, 16, 2, 8, 6000, 500, "X-APIForge-Request-Id");

        assertThat(blocked(new DestinationPolicy(disabled, host -> new InetAddress[0]), "http://127.0.0.1:8090/"))
                .isEqualTo(Reason.BLOCKED_ADDRESS);
    }

    @Test
    void rebindingCannotChangeTheConnectedAddress() throws Exception {
        dns.put("rebind.example.com", List.of("93.184.216.34"));
        ValidatedDestination destination = policy().check(UrlGuard.parse("http://rebind.example.com/"));
        // The attacker's DNS now answers with the metadata address...
        dns.put("rebind.example.com", List.of("169.254.169.254"));
        GuardedDnsResolver resolver = new GuardedDnsResolver();
        resolver.bind(destination);

        // ...but the client's resolver only ever returns the validated answer, without a new lookup.
        int lookupsBefore = lookups.get();
        assertThat(resolver.resolve("rebind.example.com")).extracting(InetAddress::getHostAddress)
                .containsExactly("93.184.216.34");
        assertThat(lookups.get()).isEqualTo(lookupsBefore);
        assertThatThrownBy(() -> resolver.resolve("other.example.com")).isInstanceOf(UnknownHostException.class);
        resolver.clear();
        assertThatThrownBy(() -> resolver.resolve("rebind.example.com")).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void connectedPeerMustBeAllowed() throws Exception {
        dns.put("api.example.com", List.of("93.184.216.34"));
        DestinationPolicy policy = policy();
        ValidatedDestination destination = policy.check(UrlGuard.parse("https://api.example.com/"));

        policy.requireAllowedPeer(destination, InetAddress.getByName("93.184.216.34"));
        assertThatThrownBy(() -> policy.requireAllowedPeer(destination, InetAddress.getByName("10.0.0.1")))
                .isInstanceOf(OutboundBlockedException.class).hasMessageContaining("private network address");
        assertThatThrownBy(() -> policy.requireAllowedPeer(destination, null)).isInstanceOf(OutboundBlockedException.class);
    }

    @Test
    void savedUrlsAreCheckedWithoutDns() throws Exception {
        DestinationPolicy policy = policy();

        policy.checkWithoutLookup(UrlGuard.parse("https://unresolvable.example/"));
        assertThatThrownBy(() -> policy.checkWithoutLookup(UrlGuard.parse("http://169.254.169.254/")))
                .isInstanceOf(OutboundBlockedException.class);
        assertThatThrownBy(() -> policy.checkWithoutLookup(UrlGuard.parse("http://localhost/")))
                .isInstanceOf(OutboundBlockedException.class);
        assertThat(lookups.get()).isZero();
    }

    @Test
    void unsafeTrustedTargetConfigurationsStopStartup() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        OutboundProperties trustedLocalhost = OutboundTestSupport.properties(List.of(new TrustedTarget("localhost", 8090)));
        OutboundProperties trustedMetadata = OutboundTestSupport.properties(
                List.of(new TrustedTarget("169.254.169.254", 80)));

        assertThatThrownBy(() -> OutboundConfiguration.validate(trustedLocalhost, prod))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod profile");
        OutboundConfiguration.validate(trustedLocalhost, dev);
        assertThatThrownBy(() -> OutboundConfiguration.validate(trustedMetadata, dev))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("can never be trusted");
    }
}
