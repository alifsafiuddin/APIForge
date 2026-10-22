package com.apiforge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.repository.AuditEventRepository;
import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TestUsers;
import com.apiforge.support.TestUsers.SignedInUser;

/** The signed-in user's own account: profile, password change and deletion. */
@IntegrationTest
class AccountIntegrationTest {

    private static final String NEW_PASSWORD = "violet-harbour-lantern-42";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditEventRepository auditEvents;

    @Test
    void renameUpdatesProfileAndSession() {
        SignedInUser user = TestUsers.register(port);

        Response renamed = user.client().patch("/api/v1/auth/me", Map.of("displayName", "  Grace Hopper "));

        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.json().path("displayName").asString()).isEqualTo("Grace Hopper");
        assertThat(user.client().get("/api/v1/auth/me").json().path("displayName").asString()).isEqualTo("Grace Hopper");
        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(user.id(), AuditEventType.PROFILE_UPDATED))
                .hasSize(1);
    }

    @Test
    void renameValidatesDisplayName() {
        SignedInUser user = TestUsers.register(port);

        for (String invalid : new String[] { " ", "x".repeat(101), "tab\tname" }) {
            Response response = user.client().patch("/api/v1/auth/me", Map.of("displayName", invalid));

            assertThat(response.status()).as(invalid).isEqualTo(400);
            assertThat(response.json().path("errors").get(0).path("field").asString()).isEqualTo("displayName");
        }
    }

    @Test
    void passwordChangeKeepsThisSessionAndEndsOthers() {
        SignedInUser user = TestUsers.register(port);
        BrowserClient otherBrowser = signIn(user.email(), TestUsers.PASSWORD);
        String sessionBefore = user.client().cookie("APIFORGE_SESSION").orElseThrow();

        Response changed = user.client().post("/api/v1/auth/password",
                Map.of("currentPassword", TestUsers.PASSWORD, "newPassword", NEW_PASSWORD));

        assertThat(changed.status()).isEqualTo(204);
        assertThat(user.client().cookie("APIFORGE_SESSION").orElseThrow()).isNotEqualTo(sessionBefore);
        assertThat(user.client().get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(otherBrowser.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(new BrowserClient(port).post("/api/v1/auth/login",
                Map.of("email", user.email(), "password", TestUsers.PASSWORD)).status()).isEqualTo(401);
        BrowserClient signedInAfter = signIn(user.email(), NEW_PASSWORD);
        assertThat(signedInAfter.get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select credentials_changed_at is not null from users where id = ?", Boolean.class,
                user.id())).isTrue();
        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(user.id(), AuditEventType.PASSWORD_CHANGED))
                .hasSize(1);
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndAPolicyCompliantNewOne() {
        SignedInUser user = TestUsers.register(port);

        Response wrongCurrent = user.client().post("/api/v1/auth/password",
                Map.of("currentPassword", "not-my-password-99", "newPassword", NEW_PASSWORD));
        Response weak = user.client().post("/api/v1/auth/password",
                Map.of("currentPassword", TestUsers.PASSWORD, "newPassword", "password1234"));
        Response same = user.client().post("/api/v1/auth/password",
                Map.of("currentPassword", TestUsers.PASSWORD, "newPassword", TestUsers.PASSWORD));

        assertThat(wrongCurrent.status()).isEqualTo(400);
        assertThat(wrongCurrent.json().path("errors").get(0).path("field").asString()).isEqualTo("currentPassword");
        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.json().path("errors").get(0).path("field").asString()).isEqualTo("newPassword");
        assertThat(same.status()).isEqualTo(400);
        assertThat(same.json().path("errors").get(0).path("message").asString()).contains("differ");
        assertThat(user.client().get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(signIn(user.email(), TestUsers.PASSWORD).get("/api/v1/auth/me").status()).isEqualTo(200);
    }

    @Test
    void passwordChecksAreRateLimitedPerUser() {
        SignedInUser user = TestUsers.register(port);
        Map<String, Object> wrong = Map.of("currentPassword", "not-my-password-99", "newPassword", NEW_PASSWORD);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(user.client().post("/api/v1/auth/password", wrong).status()).isEqualTo(400);
        }
        Response limited = user.client().post("/api/v1/auth/password",
                Map.of("currentPassword", TestUsers.PASSWORD, "newPassword", NEW_PASSWORD));

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.json().path("retryAfterSeconds").asLong()).isPositive();
    }

    @Test
    void deletingTheAccountRemovesItsDataAndEndsEverySession() {
        SignedInUser user = TestUsers.register(port);
        BrowserClient otherBrowser = signIn(user.email(), TestUsers.PASSWORD);
        Response project = user.client().post("/api/v1/projects", Map.of("name", "Doomed project"));
        assertThat(project.status()).isEqualTo(201);

        Response wrongEmail = user.client().delete("/api/v1/auth/me",
                Map.of("password", TestUsers.PASSWORD, "confirmEmail", "someone-else@example.com"));
        Response wrongPassword = user.client().delete("/api/v1/auth/me",
                Map.of("password", "not-my-password-99", "confirmEmail", user.email()));
        assertThat(wrongEmail.status()).isEqualTo(400);
        assertThat(wrongEmail.json().path("errors").get(0).path("field").asString()).isEqualTo("confirmEmail");
        assertThat(wrongPassword.status()).isEqualTo(400);
        assertThat(wrongPassword.json().path("errors").get(0).path("field").asString()).isEqualTo("password");

        Response deleted = user.client().delete("/api/v1/auth/me",
                Map.of("password", TestUsers.PASSWORD, "confirmEmail", user.email().toUpperCase()));

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(user.client().get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(otherBrowser.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, user.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from api_projects where owner_id = ?", Integer.class, user.id()))
                .isZero();
        assertThat(auditEvents.findBySubjectAndEventTypeOrderByOccurredAtDesc(user.id().toString(),
                AuditEventType.ACCOUNT_DELETED)).singleElement().satisfies(event -> assertThat(event.getUserId()).isNull());
        assertThat(new BrowserClient(port).post("/api/v1/auth/login",
                Map.of("email", user.email(), "password", TestUsers.PASSWORD)).status()).isEqualTo(401);
    }

    @Test
    void accountEndpointsRequireSignInAndCsrf() {
        SignedInUser user = TestUsers.register(port);

        assertThat(new BrowserClient(port).patch("/api/v1/auth/me", Map.of("displayName", "X")).status()).isEqualTo(401);
        assertThat(user.client().withoutCsrfHeader()
                .post("/api/v1/auth/password", Map.of("currentPassword", TestUsers.PASSWORD, "newPassword", NEW_PASSWORD))
                .status()).isEqualTo(403);
    }

    private BrowserClient signIn(String email, String password) {
        BrowserClient browser = new BrowserClient(port);
        assertThat(browser.post("/api/v1/auth/login", Map.of("email", email, "password", password)).status())
                .isEqualTo(200);
        return browser;
    }
}
