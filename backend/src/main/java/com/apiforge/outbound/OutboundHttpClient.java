package com.apiforge.outbound;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EndpointDetails;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.apiforge.outbound.DestinationPolicy.ValidatedDestination;
import com.apiforge.outbound.OutboundRequest.Header;
import com.apiforge.outbound.OutboundResult.Failure;
import com.apiforge.outbound.OutboundResult.Kind;
import com.apiforge.outbound.OutboundResult.Response;
import com.apiforge.outbound.UrlGuard.ParsedUrl;

/**
 * The only way the application contacts user-supplied destinations (enforced by ArchUnit).
 *
 * <p>Apache HttpClient 5 with a connection pool, a resolver that only returns validated addresses,
 * no proxy (system properties are not read), no automatic redirects, retries, cookies or
 * decompression, a cap on response headers and bodies, per-request read timeouts and a total
 * deadline. Redirects are followed manually: every hop is parsed and checked again, and
 * credentials are removed when the origin changes.
 */
public class OutboundHttpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger("com.apiforge.outbound");

    private final OutboundProperties properties;
    private final DestinationPolicy policy;
    private final GuardedDnsResolver resolver = new GuardedDnsResolver();
    private final PoolingHttpClientConnectionManager connections;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines;

    public OutboundHttpClient(OutboundProperties properties, DestinationPolicy policy) {
        this.properties = properties;
        this.policy = policy;
        this.connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                        .setSocketTimeout(Timeout.of(properties.maxReadTimeout()))
                        .setTimeToLive(TimeValue.ofSeconds(60))
                        .setValidateAfterInactivity(TimeValue.ofSeconds(2))
                        .build())
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                        .http1Config(Http1Config.custom()
                                .setMaxHeaderCount(properties.maxResponseHeaders())
                                .setMaxLineLength(16 * 1024)
                                .build())
                        .build())
                .setMaxConnTotal(properties.maxConnections())
                .setMaxConnPerRoute(Math.min(8, properties.maxConnections()))
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableContentCompression()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .evictExpiredConnections()
                .setUserAgent("APIForge-AI/0.1")
                .build();
        this.deadlines = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("outbound-deadlines").factory());
    }

    public OutboundResult execute(OutboundRequest request, Cancellation cancellation) {
        long started = System.nanoTime();
        long deadline = started + properties.totalDeadline().toNanos();
        List<String> redirects = new ArrayList<>();
        String url = request.url();
        String method = request.method().toUpperCase(Locale.ROOT);
        byte[] body = request.body();
        String contentType = request.contentType();
        List<Header> headers = request.headers();
        String previousOrigin = null;
        if (body != null && body.length > properties.maxRequestSize().toBytes()) {
            return new Failure(Kind.BLOCKED_BY_POLICY, "The request body exceeds the limit of "
                    + properties.maxRequestSize().toKilobytes() + " KB.", 0, redirects);
        }
        for (int hop = 0;; hop++) {
            ParsedUrl parsed;
            ValidatedDestination destination;
            try {
                parsed = UrlGuard.parse(url);
                destination = policy.check(parsed);
            } catch (OutboundBlockedException ex) {
                log.warn("Outbound request refused: {} (hop {})", ex.reason(), hop);
                Kind kind = ex.reason() == OutboundBlockedException.Reason.DNS_FAILURE ? Kind.DNS_FAILURE
                        : hop == 0 ? Kind.BLOCKED_BY_POLICY : Kind.REDIRECT_BLOCKED;
                String message = hop == 0 ? ex.getMessage() : "Redirect refused: " + ex.getMessage();
                return new Failure(kind, message, elapsed(started), redirects);
            }
            if (previousOrigin != null && !previousOrigin.equals(parsed.origin())) {
                headers = headers.stream()
                        .filter(header -> !request.sensitiveHeaders().contains(header.name().toLowerCase(Locale.ROOT)))
                        .toList();
            }
            OutboundResult result = send(method, parsed, destination, headers, body, contentType, request.readTimeout(),
                    deadline, cancellation, started, redirects);
            if (!(result instanceof Response response) || !request.followRedirects() || !isRedirect(response.status())) {
                return result;
            }
            String location = firstHeader(response.headers(), "location");
            if (location == null) {
                return result;
            }
            if (hop >= properties.maxRedirects()) {
                return new Failure(Kind.REDIRECT_BLOCKED, "More than " + properties.maxRedirects()
                        + " redirects; the chain was stopped.", elapsed(started), redirects);
            }
            String next;
            try {
                next = parsed.uri().resolve(new URI(location)).toString();
            } catch (URISyntaxException | IllegalArgumentException ex) {
                return new Failure(Kind.REDIRECT_BLOCKED, "Redirect refused: the Location header is not a valid URL.",
                        elapsed(started), redirects);
            }
            redirects.add(next);
            previousOrigin = parsed.origin();
            url = next;
            if (response.status() == 303 || ((response.status() == 301 || response.status() == 302)
                    && !method.equals("GET") && !method.equals("HEAD"))) {
                method = "GET";
                body = null;
                contentType = null;
            }
        }
    }

    private OutboundResult send(String method, ParsedUrl url, ValidatedDestination destination, List<Header> headers,
            byte[] body, String contentType, Duration readTimeout, long deadline, Cancellation cancellation,
            long started, List<String> redirects) {
        if (cancellation.isCancelled()) {
            return new Failure(Kind.CANCELLED, "The request was cancelled.", elapsed(started), redirects);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return new Failure(Kind.TIMEOUT, totalDeadlineMessage(), elapsed(started), redirects);
        }
        HttpUriRequestBase httpRequest = new HttpUriRequestBase(method, url.uri());
        headers.forEach(header -> httpRequest.addHeader(header.name(), header.value()));
        if (body != null) {
            httpRequest.setEntity(new ByteArrayEntity(body, contentType == null ? null : ContentType.parse(contentType)));
        }
        Duration read = readTimeout.compareTo(properties.maxReadTimeout()) > 0 ? properties.maxReadTimeout() : readTimeout;
        httpRequest.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.of(read))
                .setRedirectsEnabled(false)
                .setContentCompressionEnabled(false)
                .build());
        HttpClientContext context = HttpClientContext.create();
        AtomicBoolean deadlineReached = new AtomicBoolean();
        ScheduledFuture<?> timer = deadlines.schedule(() -> {
            deadlineReached.set(true);
            httpRequest.cancel();
        }, remaining, TimeUnit.NANOSECONDS);
        cancellation.onCancel(httpRequest::cancel);
        resolver.bind(destination);
        try (ClassicHttpResponse response = client.executeOpen(null, httpRequest, context)) {
            resolver.clear();
            policy.requireAllowedPeer(destination, peer(context));
            Body received = read(response.getEntity(), httpRequest);
            List<Header> responseHeaders = new ArrayList<>();
            for (org.apache.hc.core5.http.Header header : response.getHeaders()) {
                responseHeaders.add(new Header(header.getName(), header.getValue()));
            }
            long duration = elapsed(started);
            log.info("outbound {} {}:{}{} -> {} in {} ms{}", method, url.host(), url.port(), path(url), response.getCode(),
                    duration, received.truncated() ? " (body truncated)" : "");
            return new Response(response.getCode(), responseHeaders, received.bytes(), received.size(),
                    received.truncated(), firstHeader(responseHeaders, "content-type"), duration, redirects);
        } catch (OutboundBlockedException ex) {
            log.warn("Outbound connection rejected after connect: {}", ex.reason());
            return new Failure(Kind.BLOCKED_BY_POLICY, ex.getMessage(), elapsed(started), redirects);
        } catch (IOException | RuntimeException ex) {
            Failure failure = classify(ex, deadlineReached.get(), cancellation.isCancelled(), read, started, redirects);
            log.info("outbound {} {}:{}{} -> {} in {} ms", method, url.host(), url.port(), path(url), failure.kind(),
                    failure.durationMs());
            return failure;
        } finally {
            timer.cancel(false);
            cancellation.clear();
            resolver.clear();
        }
    }

    private record Body(byte[] bytes, long size, boolean truncated) {
    }

    private Body read(HttpEntity entity, HttpUriRequestBase request) throws IOException {
        if (entity == null) {
            return new Body(new byte[0], 0, false);
        }
        long limit = properties.maxResponseSize().toBytes();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long total = 0;
        boolean truncated = false;
        try (InputStream in = entity.getContent()) {
            if (in != null) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = in.read(chunk)) != -1) {
                    long room = limit - total;
                    if (count > room) {
                        buffer.write(chunk, 0, (int) room);
                        total += count;
                        truncated = true;
                        // Abort instead of draining the rest of an oversized body.
                        request.cancel();
                        break;
                    }
                    buffer.write(chunk, 0, count);
                    total += count;
                }
            }
        } catch (IOException ex) {
            if (!truncated) {
                throw ex;
            }
        }
        long declared = entity.getContentLength();
        return new Body(buffer.toByteArray(), Math.max(total, truncated ? declared : total), truncated);
    }

    private Failure classify(Exception ex, boolean deadlineReached, boolean cancelled, Duration read, long started,
            List<String> redirects) {
        long duration = elapsed(started);
        if (deadlineReached) {
            return new Failure(Kind.TIMEOUT, totalDeadlineMessage(), duration, redirects);
        }
        if (cancelled) {
            return new Failure(Kind.CANCELLED, "The request was cancelled.", duration, redirects);
        }
        Kind kind;
        String message;
        if (ex instanceof ConnectTimeoutException) {
            kind = Kind.TIMEOUT;
            message = "Connecting timed out after " + properties.connectTimeout().toMillis() + " ms.";
        } else if (ex instanceof SocketTimeoutException) {
            kind = Kind.TIMEOUT;
            message = "No response data within the read timeout of " + read.toMillis() + " ms.";
        } else if (ex instanceof HttpHostConnectException) {
            kind = Kind.CONNECT_FAILED;
            message = "The connection was refused by the target.";
        } else if (ex instanceof SSLException) {
            kind = Kind.TLS_ERROR;
            message = "The TLS handshake failed (certificate or protocol problem).";
        } else if (ex instanceof ProtocolException || ex instanceof org.apache.hc.core5.http.NoHttpResponseException
                || ex instanceof org.apache.hc.core5.http.MessageConstraintException
                || ex instanceof org.apache.hc.core5.http.ConnectionClosedException) {
            kind = Kind.INVALID_RESPONSE;
            message = "The target sent an invalid or incomplete HTTP response.";
        } else if (ex instanceof java.net.UnknownHostException) {
            kind = Kind.BLOCKED_BY_POLICY;
            message = "The destination was not validated by the outbound policy.";
        } else if (ex instanceof InterruptedIOException) {
            kind = Kind.CANCELLED;
            message = "The request was interrupted.";
        } else if (ex instanceof IOException) {
            kind = Kind.CONNECT_FAILED;
            message = "The connection to the target failed.";
        } else {
            log.error("Unexpected outbound failure", ex);
            kind = Kind.INTERNAL;
            message = "The request could not be executed because of an internal error.";
        }
        return new Failure(kind, message, duration, redirects);
    }

    private String totalDeadlineMessage() {
        return "The request did not complete within the total deadline of "
                + properties.totalDeadline().toMillis() + " ms.";
    }

    private static InetAddress peer(HttpClientContext context) {
        EndpointDetails details = context.getEndpointDetails();
        SocketAddress remote = details == null ? null : details.getRemoteAddress();
        return remote instanceof InetSocketAddress socket ? socket.getAddress() : null;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String firstHeader(List<Header> headers, String name) {
        return headers.stream().filter(header -> header.name().equalsIgnoreCase(name)).map(Header::value).findFirst()
                .orElse(null);
    }

    /** Path without the query string (queries can carry credentials and never go to logs). */
    private static String path(ParsedUrl url) {
        String path = url.uri().getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    @Override
    public void close() throws IOException {
        deadlines.shutdownNow();
        client.close();
        connections.close();
    }
}
