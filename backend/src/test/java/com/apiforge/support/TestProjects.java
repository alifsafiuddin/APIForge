package com.apiforge.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

/** Creates projects through the API for tests in any package. */
public final class TestProjects {

    private TestProjects() {
    }

    /** Returns the new project's id. */
    public static String create(BrowserClient browser, String name) {
        BrowserClient.Response response = browser.post("/api/v1/projects", Map.of("name", name));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json().path("id").asString();
    }
}
