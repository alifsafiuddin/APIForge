package com.apiforge.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.apiforge.outbound.OutboundBlockedException.Reason;
import com.apiforge.outbound.UrlGuard.ParsedUrl;

class UrlGuardTest {

    private static Reason rejection(String url) {
        OutboundBlockedException ex = catchThrowableOfType(OutboundBlockedException.class, () -> UrlGuard.parse(url));
        assertThat(ex).as("expected %s to be rejected", url).isNotNull();
        return ex.reason();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            https://api.example.com/v1          | https | api.example.com           | 443
            http://example.com:8080/x?y=1       | http  | example.com               | 8080
            HTTP://API.Example.COM/             | http  | api.example.com           | 80
            https://api.example.com./v1         | https | api.example.com           | 443
            https://xn--bcher-kva.example/      | https | xn--bcher-kva.example     | 443
            http://93.184.216.34/               | http  | 93.184.216.34             | 80
            http://[2606:4700:4700::1111]:8443/ | http  | 2606:4700:4700::1111      | 8443
            """)
    void acceptsPlainUrls(String url, String scheme, String host, int port) throws OutboundBlockedException {
        ParsedUrl parsed = UrlGuard.parse(url);

        assertThat(parsed.scheme()).isEqualTo(scheme);
        assertThat(parsed.host()).isEqualTo(host);
        assertThat(parsed.port()).isEqualTo(port);
    }

    @Test
    void literalsAreParsedWithoutLookups() throws OutboundBlockedException {
        assertThat(UrlGuard.parse("http://127.0.0.1:8090/").literal().getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(UrlGuard.parse("http://[::1]/").isLiteral()).isTrue();
        assertThat(UrlGuard.parse("https://example.com/").isLiteral()).isFalse();
        assertThat(UrlGuard.parse("https://example.com/").origin()).isEqualTo("https://example.com:443");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/", "file:///etc/passwd", "gopher://example.com/", "ws://example.com/",
            "jar:http://example.com!/", "dict://127.0.0.1:11211/"})
    void otherProtocolsAreRefused(String url) {
        assertThat(rejection(url)).isEqualTo(Reason.UNSUPPORTED_PROTOCOL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://user:pass@example.com/", "http://example.com@127.0.0.1/", "http://@example.com/"})
    void credentialsInUrlsAreRefused(String url) {
        assertThat(rejection(url)).isIn(Reason.CREDENTIALS_IN_URL, Reason.INVALID_URL);
    }

    /** Alternative IPv4 notations that some parsers read as 127.0.0.1 or 169.254.169.254. */
    @ParameterizedTest
    @ValueSource(strings = {"http://0x7f.0.0.1/", "http://0x7f000001/", "http://0177.0.0.1/", "http://017700000001/",
            "http://2130706433/", "http://127.1/", "http://127.0.1/", "http://0xa9.0xfe.0xa9.0xfe/",
            "http://2852039166/", "http://169.254.169.254.1/", "http://1.2.3.4.5/", "http://00127.0.0.1/",
            "http://127.000.000.001/"})
    void nonCanonicalAddressesAreRefused(String url) {
        assertThat(rejection(url)).isIn(Reason.NON_CANONICAL_ADDRESS, Reason.INVALID_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.com\\@good.com/", "http://exa mple.com/", "http://example.com/\u0000",
            "http://%31%32%37.0.0.1/", "http://[::1%25eth0]/", "http://[fe80::1%eth0]/", "http://under_score.example/",
            "http://-bad.example/", "http://bad-.example/", "/relative/path", "http:///nohost", "http://",
            "http://127.0.0.1:99999/", "http://example.com:0/", "mailto:a@example.com", "http://ex。ample.com/",
            "http://exam．ple.com/", "https://bücher.example/"})
    void ambiguousOrMalformedUrlsAreRefused(String url) {
        assertThat(rejection(url)).isIn(Reason.INVALID_URL, Reason.CREDENTIALS_IN_URL, Reason.UNSUPPORTED_PROTOCOL,
                Reason.NON_CANONICAL_ADDRESS);
    }

    @Test
    void overlongUrlsAreRefused() {
        assertThat(rejection("https://example.com/" + "a".repeat(UrlGuard.MAX_URL_LENGTH))).isEqualTo(Reason.INVALID_URL);
    }
}
