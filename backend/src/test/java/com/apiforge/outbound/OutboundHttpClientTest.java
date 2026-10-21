package com.apiforge.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.util.unit.DataSize;

import com.apiforge.outbound.OutboundProperties.TrustedTarget;
import com.apiforge.outbound.OutboundRequest.Header;
import com.apiforge.outbound.OutboundResult.Failure;
import com.apiforge.outbound.OutboundResult.Kind;
import com.apiforge.outbound.OutboundResult.Response;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The real client against local servers. Server A and B are trusted targets (exact 127.0.0.1
 * ports); server C is not, so it stands for any internal service that must stay unreachable.
 */
class OutboundHttpClientTest {

    private static HttpServer serverA;
    private static HttpServer serverB;
    private static HttpServer serverC;
    private static ServerSocket garbage;
    private static int closedPort;
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();
    private static final Map<String, String> LAST_AUTHORIZATION = new ConcurrentHashMap<>();
    private static OutboundHttpClient client;
    private static OutboundHttpClient shortDeadlineClient;

    @BeforeAll
    static void startServers() throws IOException {
        serverA = server("A");
        serverB = server("B");
        serverC = server("C");
        garbage = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!garbage.isClosed()) {
                try (Socket socket = garbage.accept()) {
                    socket.getInputStream().read(new byte[1024]);
                    socket.getOutputStream().write("THIS IS NOT HTTP\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // closed at the end of the test class
                }
            }
        });
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        List<TrustedTarget> trusted = List.of(new TrustedTarget("127.0.0.1", port(serverA)),
                new TrustedTarget("127.0.0.1", port(serverB)), new TrustedTarget("127.0.0.1", garbage.getLocalPort()),
                new TrustedTarget("127.0.0.1", closedPort));
        OutboundProperties properties = OutboundTestSupport.properties(trusted);
        client = new OutboundHttpClient(properties, new DestinationPolicy(properties, java.net.InetAddress::getAllByName));
        OutboundProperties shortDeadline = OutboundTestSupport.properties(trusted, Duration.ofMillis(800),
                DataSize.ofKilobytes(64));
        shortDeadlineClient = new OutboundHttpClient(shortDeadline,
                new DestinationPolicy(shortDeadline, java.net.InetAddress::getAllByName));
    }

    @AfterAll
    static void stopServers() throws IOException {
        client.close();
        shortDeadlineClient.close();
        serverA.stop(0);
        serverB.stop(0);
        serverC.stop(0);
        garbage.close();
    }

    private static HttpServer server(String name) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        HITS.put(name, new AtomicInteger());
        server.createContext("/", exchange -> handle(name, exchange));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static void handle(String name, HttpExchange exchange) throws IOException {
        HITS.get(name).incrementAndGet();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        LAST_AUTHORIZATION.put(name, authorization == null ? "<none>" : authorization);
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        try {
            switch (path) {
                case "/ok" -> send(exchange, 200, "application/json", "{\"ok\":true,\"server\":\"" + name + "\"}");
                case "/echo" -> send(exchange, 200, "text/plain", exchange.getRequestMethod() + " "
                        + new String(requestBody, StandardCharsets.UTF_8));
                case "/status/404" -> send(exchange, 404, "application/json", "{\"code\":\"NOT_FOUND\"}");
                case "/status/500" -> send(exchange, 500, "text/plain", "boom");
                case "/slow" -> {
                    sleep(3000);
                    send(exchange, 200, "text/plain", "late");
                }
                case "/large" -> send(exchange, 200, "text/plain", "x".repeat(100 * 1024));
                case "/loop" -> redirect(exchange, 302, "/loop");
                case "/redirect" -> {
                    String target = URLDecoder.decode(query.substring(query.indexOf("to=") + 3), StandardCharsets.UTF_8);
                    redirect(exchange, query.contains("code=303") ? 303 : 302, target);
                }
                default -> send(exchange, 404, "text/plain", "no route");
            }
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("X-Method", exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(status, -1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static int port(HttpServer server) {
        return server.getAddress().getPort();
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + port(server) + path;
    }

    private static OutboundRequest get(String url) {
        return request("GET", url, null, false);
    }

    private static OutboundRequest request(String method, String url, String body, boolean followRedirects) {
        return new OutboundRequest(method, url, List.of(new Header("Authorization", "Bearer s3cr3t-token")),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), body == null ? null : "application/json",
                Duration.ofSeconds(1), followRedirects, Set.of("authorization"));
    }

    private static Response response(OutboundResult result) {
        assertThat(result).as("expected a response, got %s", result).isInstanceOf(Response.class);
        return (Response) result;
    }

    private static Failure failure(OutboundResult result) {
        assertThat(result).as("expected a failure, got %s", result).isInstanceOf(Failure.class);
        return (Failure) result;
    }

    // ---- requests and responses ----------------------------------------------------------------

    @Test
    void successfulRequestRecordsStatusHeadersBodyAndDuration() {
        Response response = response(client.execute(get(url(serverA, "/ok")), new Cancellation()));

        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true,\"server\":\"A\"}");
        assertThat(response.contentType()).isEqualTo("application/json");
        assertThat(response.headers()).anySatisfy(header -> assertThat(header.name()).isEqualToIgnoringCase("x-method"));
        assertThat(response.sizeBytes()).isEqualTo(response.body().length);
        assertThat(response.truncated()).isFalse();
        assertThat(response.durationMs()).isNotNegative();
        assertThat(response.timeToHeadersMs()).isBetween(0L, response.durationMs());
    }

    @Test
    void everyMethodIsSentWithItsBody() {
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            String body = method.equals("GET") || method.equals("DELETE") ? null : "{\"n\":1}";
            Response response = response(client.execute(request(method, url(serverA, "/echo"), body, false),
                    new Cancellation()));
            assertThat(new String(response.body(), StandardCharsets.UTF_8))
                    .isEqualTo(method + " " + (body == null ? "" : body));
        }
    }

    @Test
    void httpErrorsAreResponsesNotFailures() {
        assertThat(response(client.execute(get(url(serverA, "/status/404")), new Cancellation())).status()).isEqualTo(404);
        assertThat(response(client.execute(get(url(serverA, "/status/500")), new Cancellation())).status()).isEqualTo(500);
    }

    @Test
    void oversizedBodiesAreTruncatedAtTheLimit() {
        Response response = response(client.execute(get(url(serverA, "/large")), new Cancellation()));

        assertThat(response.truncated()).isTrue();
        assertThat(response.body()).hasSize(64 * 1024);
        assertThat(response.sizeBytes()).isEqualTo(100 * 1024);
    }

    @Test
    void oversizedRequestBodiesAreRefused() {
        Failure failure = failure(client.execute(request("POST", url(serverA, "/echo"), "x".repeat(300 * 1024), false),
                new Cancellation()));

        assertThat(failure.kind()).isEqualTo(Kind.BLOCKED_BY_POLICY);
        assertThat(failure.message()).contains("256 KB");
    }

    // ---- network errors and timeouts -----------------------------------------------------------

    @Test
    void readTimeoutIsReported() {
        Failure failure = failure(client.execute(get(url(serverA, "/slow")), new Cancellation()));

        assertThat(failure.kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(failure.message()).contains("read timeout of 1000 ms");
    }

    @Test
    void totalDeadlineStopsTheRequest() {
        OutboundRequest patient = new OutboundRequest("GET", url(serverA, "/slow"), List.of(), null, null,
                Duration.ofSeconds(10), false, Set.of());

        Failure failure = failure(shortDeadlineClient.execute(patient, new Cancellation()));

        assertThat(failure.kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(failure.message()).contains("total deadline of 800 ms");
        assertThat(failure.durationMs()).isLessThan(2500);
    }

    @Test
    void malformedResponsesAreReported() {
        Failure failure = failure(client.execute(get("http://127.0.0.1:" + garbage.getLocalPort() + "/"), new Cancellation()));

        assertThat(failure.kind()).isEqualTo(Kind.INVALID_RESPONSE);
    }

    @Test
    void refusedConnectionsAreReported() {
        Failure failure = failure(client.execute(get("http://127.0.0.1:" + closedPort + "/"), new Cancellation()));

        assertThat(failure.kind()).isEqualTo(Kind.CONNECT_FAILED);
        assertThat(failure.message()).doesNotContain("127.0.0.1");
    }

    @Test
    void tlsFailuresAreReported() {
        // https to a server that answers the TLS handshake with plain text.
        Failure failure = failure(client.execute(get("https://127.0.0.1:" + garbage.getLocalPort() + "/ok"),
                new Cancellation()));

        assertThat(failure.kind()).isEqualTo(Kind.TLS_ERROR);
        assertThat(failure.message()).doesNotContain("127.0.0.1");
    }

    @Test
    void unresolvableNamesAreDnsFailuresWithoutAConnection() {
        Failure failure = failure(client.execute(get("http://no-such-host.invalid/ok"), new Cancellation()));

        assertThat(failure.kind()).isEqualTo(Kind.DNS_FAILURE);
        assertThat(failure.message()).contains("no-such-host.invalid");
    }

    @Test
    @Timeout(10)
    void cancellationAbortsTheRequestInFlight() throws Exception {
        Cancellation cancellation = new Cancellation();
        OutboundRequest patient = new OutboundRequest("GET", url(serverA, "/slow"), List.of(), null, null,
                Duration.ofSeconds(10), false, Set.of());
        CompletableFuture<OutboundResult> running = CompletableFuture.supplyAsync(() -> client.execute(patient, cancellation));

        Thread.sleep(300);
        cancellation.cancel();

        Failure failure = failure(running.get(5, TimeUnit.SECONDS));
        assertThat(failure.kind()).isEqualTo(Kind.CANCELLED);
        assertThat(failure.durationMs()).isLessThan(2500);
        assertThat(failure(client.execute(get(url(serverA, "/ok")), cancellation)).kind()).isEqualTo(Kind.CANCELLED);
    }

    @Test
    void systemProxySettingsAreIgnored() {
        System.setProperty("http.proxyHost", "203.0.113.1");
        System.setProperty("http.proxyPort", "3128");
        try {
            assertThat(response(client.execute(get(url(serverA, "/ok")), new Cancellation())).status()).isEqualTo(200);
        } finally {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
        }
    }

    // ---- blocked destinations ------------------------------------------------------------------

    @Test
    void untrustedLocalServicesAreNeverContacted() {
        int before = HITS.get("C").get();
        for (String url : List.of(url(serverC, "/ok"), "http://localhost:" + port(serverC) + "/ok",
                "http://[::ffff:127.0.0.1]:" + port(serverC) + "/ok", "http://0x7f.0.0.1:" + port(serverC) + "/ok",
                "http://2130706433:" + port(serverC) + "/ok", "http://169.254.169.254/latest/meta-data/",
                "http://[::1]:" + port(serverC) + "/ok", "file:///etc/passwd", "ftp://127.0.0.1/")) {
            Failure failure = failure(client.execute(get(url), new Cancellation()));
            assertThat(failure.kind()).as(url).isEqualTo(Kind.BLOCKED_BY_POLICY);
            assertThat(failure.message()).as(url).doesNotContain("Bearer");
        }
        assertThat(HITS.get("C").get()).isEqualTo(before);
    }

    // ---- redirects -----------------------------------------------------------------------------

    @Test
    void redirectsAreNotFollowedUnlessEnabled() {
        Response response = response(client.execute(get(url(serverA, "/redirect?to=/ok")), new Cancellation()));

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.redirects()).isEmpty();
    }

    @Test
    void sameOriginRedirectsAreFollowedAndKeepCredentials() {
        Response response = response(client.execute(request("GET", url(serverA, "/redirect?to=/ok"), null, true),
                new Cancellation()));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.redirects()).containsExactly(url(serverA, "/ok"));
        assertThat(LAST_AUTHORIZATION.get("A")).isEqualTo("Bearer s3cr3t-token");
    }

    @Test
    void crossOriginRedirectsDropCredentials() {
        String target = java.net.URLEncoder.encode(url(serverB, "/ok"), StandardCharsets.UTF_8);

        Response response = response(client.execute(request("GET", url(serverA, "/redirect?to=" + target), null, true),
                new Cancellation()));

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("\"server\":\"B\"");
        assertThat(LAST_AUTHORIZATION.get("B")).isEqualTo("<none>");
    }

    @Test
    void redirectsToBlockedDestinationsAreRefusedBeforeConnecting() {
        int before = HITS.get("C").get();
        for (String target : List.of("http://169.254.169.254/latest/meta-data/iam/", url(serverC, "/ok"),
                "http://localhost:" + port(serverC) + "/", "file:///etc/passwd", "http://[::1]:" + port(serverC) + "/")) {
            String encoded = java.net.URLEncoder.encode(target, StandardCharsets.UTF_8);
            Failure failure = failure(client.execute(request("GET", url(serverA, "/redirect?to=" + encoded), null, true),
                    new Cancellation()));
            assertThat(failure.kind()).as(target).isEqualTo(Kind.REDIRECT_BLOCKED);
            assertThat(failure.message()).as(target).startsWith("Redirect refused:");
            assertThat(failure.redirects()).as(target).hasSize(1);
        }
        assertThat(HITS.get("C").get()).isEqualTo(before);
    }

    @Test
    void redirectChainsAreLimited() {
        Failure failure = failure(client.execute(request("GET", url(serverA, "/loop"), null, true), new Cancellation()));

        assertThat(failure.kind()).isEqualTo(Kind.REDIRECT_BLOCKED);
        assertThat(failure.message()).contains("More than 3 redirects");
    }

    @Test
    void seeOtherTurnsAPostIntoAGetWithoutBody() {
        Response response = response(client.execute(
                request("POST", url(serverA, "/redirect?code=303&to=/echo"), "{\"a\":1}", true), new Cancellation()));

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("GET ");
    }

    @Test
    void invalidLocationHeadersAreRefused() {
        String encoded = java.net.URLEncoder.encode("http://exa mple.com/", StandardCharsets.UTF_8);

        Failure failure = failure(client.execute(request("GET", url(serverA, "/redirect?to=" + encoded), null, true),
                new Cancellation()));

        assertThat(failure.kind()).isEqualTo(Kind.REDIRECT_BLOCKED);
    }
}
