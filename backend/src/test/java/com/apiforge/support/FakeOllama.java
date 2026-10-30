package com.apiforge.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A scripted stand-in for Ollama on 127.0.0.1:18092 (application-test.yml): lists models and
 * answers chat requests from a queue, so tests can produce every failure mode on purpose. It keeps
 * the request bodies it received, so tests can check what a prompt contained.
 */
public final class FakeOllama {

    public static final int PORT = 18092;
    public static final String MODEL = "test-model:1b";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One scripted answer to /api/chat. */
    public record Reply(int status, String body, long delayMillis) {
    }

    private static HttpServer server;
    private static final Deque<Reply> replies = new ArrayDeque<>();
    private static final List<JsonNode> requests = new ArrayList<>();
    private static List<String> models = List.of(MODEL);

    private FakeOllama() {
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 20);
        } catch (IOException ex) {
            throw new UncheckedIOException("Port " + PORT + " is needed for AI tests", ex);
        }
        server.createContext("/api/tags", FakeOllama::tags);
        server.createContext("/api/chat", FakeOllama::chat);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** Simulates Ollama not running. */
    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public static synchronized void reset() {
        replies.clear();
        requests.clear();
        models = List.of(MODEL);
    }

    public static synchronized void installed(List<String> names) {
        models = List.copyOf(names);
    }

    public static synchronized void answer(Reply... scripted) {
        replies.addAll(List.of(scripted));
    }

    /** A successful chat answer whose message content is {@code content}. */
    public static Reply content(String content) {
        return new Reply(200, JSON.writeValueAsString(Map.of("model", MODEL, "created_at", "2026-10-22T10:00:00Z",
                "message", Map.of("role", "assistant", "content", content), "done", true, "done_reason", "stop",
                "total_duration", 1_000_000L, "prompt_eval_count", 100, "eval_count", 50)), 0);
    }

    public static Reply error(int status, String message) {
        return new Reply(status, JSON.writeValueAsString(Map.of("error", message)), 0);
    }

    public static synchronized List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    private static void tags(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        synchronized (FakeOllama.class) {
            models.forEach(name -> list.add(Map.of("name", name, "model", name, "modified_at", "2026-10-22T10:00:00Z",
                    "size", 1, "digest", "abc")));
        }
        send(exchange, 200, JSON.writeValueAsString(Map.of("models", list)));
    }

    private static void chat(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Reply reply;
        synchronized (FakeOllama.class) {
            requests.add(JSON.readTree(new String(body, StandardCharsets.UTF_8)));
            reply = replies.isEmpty() ? error(500, "no scripted reply") : replies.poll();
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        send(exchange, reply.status(), reply.body());
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ex) {
            // The client gave up (timeout tests); nothing to do.
        } finally {
            exchange.close();
        }
    }
}
