package com.apiforge.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authorized target API for execution tests: a small "items" API on 127.0.0.1:18090 (a
 * trusted target in application-test.yml), plus endpoints that misbehave on purpose. It records
 * what it received, so tests can check what was actually sent.
 */
public final class TargetApi {

    public static final int PORT = 18090;
    public static final String BASE_URL = "http://127.0.0.1:" + PORT;
    public static final String API_KEY = "key-123-SUPER-SECRET";

    /** The contract of the items API, in sync with the handlers below (one documented deviation). */
    public static final String SPEC = """
            openapi: 3.0.3
            info: {title: Items API, version: 1.0.0}
            security:
              - apiKey: []
            paths:
              /items:
                get:
                  responses:
                    '200':
                      description: Items
                      content:
                        application/json:
                          schema: {type: array, items: {$ref: '#/components/schemas/Item'}}
                    '401': {description: Missing or wrong key}
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema: {$ref: '#/components/schemas/NewItem'}
                  responses:
                    '201':
                      description: Created
                      content:
                        application/json:
                          schema: {$ref: '#/components/schemas/Item'}
                    '400': {description: Invalid}
                    '401': {description: Missing or wrong key}
                    '415': {description: Not JSON}
              /items/{id}:
                parameters:
                  - {name: id, in: path, required: true, schema: {type: integer, minimum: 1, example: 1}}
                get:
                  responses:
                    '200':
                      description: One item
                      content:
                        application/json:
                          schema: {$ref: '#/components/schemas/Item'}
                    '400': {description: Invalid id}
                    '401': {description: Missing or wrong key}
                    '404': {description: Not found}
                delete:
                  responses:
                    '204': {description: Deleted}
                    '401': {description: Missing or wrong key}
            components:
              securitySchemes:
                apiKey: {type: apiKey, in: header, name: X-API-Key}
              schemas:
                NewItem:
                  type: object
                  required: [name]
                  properties:
                    name: {type: string, minLength: 1, maxLength: 20}
                Item:
                  allOf:
                    - $ref: '#/components/schemas/NewItem'
                    - type: object
                      properties:
                        id: {type: integer}
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static HttpServer server;
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();
    private static final Map<String, String> LAST_CORRELATION = new ConcurrentHashMap<>();

    private TargetApi() {
    }

    /** Starts the server once per JVM (all test classes share it). */
    public static synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 50);
        } catch (IOException ex) {
            throw new UncheckedIOException("Port " + PORT + " is needed for execution tests", ex);
        }
        server.createContext("/", TargetApi::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public static int hits(String path) {
        return HITS.computeIfAbsent(path, key -> new AtomicInteger()).get();
    }

    public static String lastCorrelationId(String path) {
        return LAST_CORRELATION.get(path);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        HITS.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
        String correlation = exchange.getRequestHeaders().getFirst("X-APIForge-Request-Id");
        if (correlation != null) {
            LAST_CORRELATION.put(path, correlation);
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        try {
            switch (path) {
                case "/echo" -> {
                    StringBuilder headers = new StringBuilder();
                    exchange.getRequestHeaders().forEach((name, values) -> headers.append(name).append(": ")
                            .append(String.join(",", values)).append("\n"));
                    send(exchange, 200, "application/json", JSON.writeValueAsString(Map.of(
                            "headers", headers.toString(), "query", String.valueOf(exchange.getRequestURI().getRawQuery()))));
                }
                case "/slow" -> {
                    sleep(3000);
                    send(exchange, 200, "application/json", "{\"late\":true}");
                }
                case "/malformed" -> send(exchange, 200, "application/json", "{\"items\": [1, 2,");
                case "/status/500" -> send(exchange, 500, "application/json", "{\"code\":\"INTERNAL\"}");
                case "/redirect-metadata" -> redirect(exchange, "http://169.254.169.254/latest/meta-data/iam/");
                case "/redirect-local" -> redirect(exchange, "/echo");
                case "/large" -> send(exchange, 200, "text/plain", "x".repeat(200 * 1024));
                case "/binary" -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, 4);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(new byte[] {0, 1, 2, (byte) 0xFF});
                    }
                }
                default -> items(exchange, method, path, body);
            }
        } finally {
            exchange.close();
        }
    }

    private static void items(HttpExchange exchange, String method, String path, byte[] body) throws IOException {
        if (!path.equals("/items") && !path.startsWith("/items/")) {
            send(exchange, 404, "application/json", "{\"code\":\"NO_ROUTE\"}");
            return;
        }
        if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-API-Key"))) {
            send(exchange, 401, "application/json", "{\"code\":\"UNAUTHORIZED\"}");
            return;
        }
        if (path.equals("/items")) {
            if (method.equals("GET")) {
                send(exchange, 200, "application/json", "[{\"id\":1,\"name\":\"drill\"}]");
            } else if (method.equals("POST")) {
                String contentType = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
                if (!contentType.startsWith("application/json")) {
                    send(exchange, 415, "application/json", "{\"code\":\"UNSUPPORTED_MEDIA_TYPE\"}");
                    return;
                }
                JsonNode node;
                try {
                    node = JSON.readTree(body);
                } catch (RuntimeException ex) {
                    send(exchange, 400, "application/json", "{\"code\":\"MALFORMED\"}");
                    return;
                }
                JsonNode name = node == null ? null : node.get("name");
                // Deliberate deviation from the contract: names up to 100 characters are accepted (spec: 20).
                if (node == null || !node.isObject() || name == null || !name.isString() || name.asString().isEmpty()
                        || name.asString().length() > 100) {
                    send(exchange, 400, "application/json", "{\"code\":\"INVALID\"}");
                    return;
                }
                send(exchange, 201, "application/json", JSON.writeValueAsString(Map.of("id", 2, "name", name.asString())));
            } else {
                send(exchange, 405, "application/json", "{\"code\":\"METHOD\"}");
            }
            return;
        }
        String id = path.substring("/items/".length());
        if (!id.matches("\\d{1,9}") || Integer.parseInt(id) < 1) {
            send(exchange, 400, "application/json", "{\"code\":\"INVALID_ID\"}");
            return;
        }
        if (method.equals("DELETE")) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (Integer.parseInt(id) >= 5000) {
            sleep(3000); // a slow lookup, for cancellation and concurrency tests
        }
        if (Integer.parseInt(id) > 100) {
            send(exchange, 404, "application/json", "{\"code\":\"NOT_FOUND\"}");
            return;
        }
        send(exchange, 200, "application/json", JSON.writeValueAsString(Map.of("id", Integer.parseInt(id), "name", "drill")));
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("Set-Cookie", "session=target-session-cookie; HttpOnly");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
