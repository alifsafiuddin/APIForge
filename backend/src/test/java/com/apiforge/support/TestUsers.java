package com.apiforge.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates uniquely named users so tests sharing one database never collide. */
public final class TestUsers {

    public static final String PASSWORD = "correct-horse-battery-7";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private TestUsers() {
    }

    public static String uniqueEmail() {
        return "user-" + SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
    }

    public static Map<String, Object> registration(String email) {
        return Map.of("email", email, "password", PASSWORD, "displayName", "Test User");
    }

    /** A new browser session, registered and signed in as a fresh user. */
    public static SignedInUser register(int port) {
        BrowserClient client = new BrowserClient(port);
        String email = uniqueEmail();
        BrowserClient.Response response = client.post("/api/v1/auth/register", registration(email));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return new SignedInUser(client, UUID.fromString(response.json().path("id").asString()), email);
    }

    public record SignedInUser(BrowserClient client, UUID id, String email) {
    }
}
