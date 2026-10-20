package com.apiforge.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An HTTP client that behaves like the SPA in a browser: it keeps cookies between requests and,
 * for state-changing requests, echoes the XSRF-TOKEN cookie in the X-XSRF-TOKEN header.
 * Each instance is an independent "browser" (cookie jar).
 */
public final class BrowserClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private boolean sendCsrfHeader = true;

    public BrowserClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    /** Disables the automatic CSRF header, to test CSRF protection. */
    public BrowserClient withoutCsrfHeader() {
        sendCsrfHeader = false;
        return this;
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), false);
    }

    public Response post(String path, Object body) {
        return send(withJson(HttpRequest.newBuilder(uri(path)), "POST", body), true);
    }

    public Response patch(String path, Object body) {
        return send(withJson(HttpRequest.newBuilder(uri(path)), "PATCH", body), true);
    }

    public Response delete(String path) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE(), true);
    }

    public Optional<String> cookie(String name) {
        return cookies.getCookieStore().get(URI.create(baseUrl)).stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    /** Replaces a cookie value, e.g. to replay an old session id. */
    public void setCookie(String name, String value) {
        HttpCookie cookie = new HttpCookie(name, value);
        cookie.setPath("/");
        cookie.setVersion(0);
        cookies.getCookieStore().get(URI.create(baseUrl)).stream()
                .filter(existing -> existing.getName().equals(name))
                .forEach(existing -> cookies.getCookieStore().remove(URI.create(baseUrl), existing));
        cookies.getCookieStore().add(URI.create(baseUrl), cookie);
    }

    private Response send(HttpRequest.Builder builder, boolean mutating) {
        builder.header("Accept", "application/json, application/problem+json");
        if (mutating && sendCsrfHeader) {
            String token = cookie("XSRF-TOKEN").orElseGet(() -> {
                get("/api/v1/auth/csrf");
                return cookie("XSRF-TOKEN").orElseThrow();
            });
            builder.header("X-XSRF-TOKEN", token);
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static HttpRequest.Builder withJson(HttpRequest.Builder builder, String method, Object body) {
        String json = body instanceof String raw ? raw : JSON.writeValueAsString(body);
        return builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    public record Response(HttpResponse<String> raw) {

        public int status() {
            return raw.statusCode();
        }

        public JsonNode json() {
            return raw.body().isEmpty() ? JSON.nullNode() : JSON.readTree(raw.body());
        }

        public String body() {
            return raw.body();
        }

        public Optional<String> header(String name) {
            return raw.headers().firstValue(name);
        }

        public List<String> setCookies() {
            return raw.headers().allValues("Set-Cookie");
        }

        public String code() {
            return json().path("code").asString();
        }
    }
}
