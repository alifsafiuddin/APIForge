package com.apiforge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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

@IntegrationTest
class AuthIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditEventRepository auditEvents;

    // ---- registration -------------------------------------------------------------------

    @Test
    void registrationCreatesAccountStoresOnlyBcryptHashAndSignsIn() {
        BrowserClient browser = new BrowserClient(port);
        String email = TestUsers.uniqueEmail();

        Response response = browser.post("/api/v1/auth/register",
                Map.of("email", email.toUpperCase(), "password", TestUsers.PASSWORD, "displayName", " Ada "));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().path("email").asString()).isEqualTo(email);
        assertThat(response.json().path("displayName").asString()).isEqualTo("Ada");
        assertThat(response.body()).doesNotContain(TestUsers.PASSWORD).doesNotContainIgnoringCase("password");

        String hash = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(TestUsers.PASSWORD);

        Response me = browser.get("/api/v1/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().path("email").asString()).isEqualTo(email);

        UUID userId = UUID.fromString(response.json().path("id").asString());
        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(userId, AuditEventType.USER_REGISTERED))
                .hasSize(1);
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        String email = TestUsers.uniqueEmail();
        assertThat(new BrowserClient(port).post("/api/v1/auth/register", TestUsers.registration(email)).status())
                .isEqualTo(201);

        Response duplicate = new BrowserClient(port).post("/api/v1/auth/register",
                TestUsers.registration(email.toUpperCase()));

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void registrationValidationFailuresListEachField() {
        Response response = new BrowserClient(port).post("/api/v1/auth/register",
                Map.of("email", "not-an-email", "password", "short", "displayName", ""));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.json().path("errors").valueStream().map(error -> error.path("field").asString()))
                .contains("email", "displayName");
    }

    @Test
    void weakPasswordsAreRejectedByPolicy() {
        String email = TestUsers.uniqueEmail();
        for (String weak : new String[] { "short-pass", "password1234", email, "x".repeat(65) }) {
            Response response = new BrowserClient(port).post("/api/v1/auth/register",
                    Map.of("email", email, "password", weak, "displayName", "Weak"));

            assertThat(response.status()).as(weak).isEqualTo(400);
            assertThat(response.json().path("errors").get(0).path("field").asString()).isEqualTo("password");
        }
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
    }

    // ---- login --------------------------------------------------------------------------

    @Test
    void loginWithValidCredentialsStartsSessionWithSecureCookieAttributes() {
        String email = TestUsers.register(port).email();
        BrowserClient browser = new BrowserClient(port);

        Response login = browser.post("/api/v1/auth/login", Map.of("email", email, "password", TestUsers.PASSWORD));

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.json().path("email").asString()).isEqualTo(email);
        assertThat(login.setCookies()).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("APIFORGE_SESSION=")
                .contains("HttpOnly")
                .containsIgnoringCase("SameSite=Lax"));
        assertThat(login.setCookies()).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("XSRF-TOKEN=")
                .doesNotContain("HttpOnly")
                .containsIgnoringCase("SameSite=Strict"));
        assertThat(browser.get("/api/v1/auth/me").status()).isEqualTo(200);
    }

    @Test
    void invalidCredentialsGiveIdenticalResponsesForUnknownEmailAndWrongPassword() {
        String email = TestUsers.register(port).email();

        Response wrongPassword = new BrowserClient(port).post("/api/v1/auth/login",
                Map.of("email", email, "password", "wrong-password-123"));
        Response unknownEmail = new BrowserClient(port).post("/api/v1/auth/login",
                Map.of("email", TestUsers.uniqueEmail(), "password", "wrong-password-123"));

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.code()).isEqualTo("BAD_CREDENTIALS");
        assertThat(unknownEmail.code()).isEqualTo("BAD_CREDENTIALS");
        assertThat(wrongPassword.json().path("detail")).isEqualTo(unknownEmail.json().path("detail"));
        assertThat(wrongPassword.setCookies()).noneMatch(cookie -> cookie.startsWith("APIFORGE_SESSION="));
    }

    @Test
    void loginValidationFailure() {
        Response response = new BrowserClient(port).post("/api/v1/auth/login", Map.of("email", "", "password", ""));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void accountLocksAfterRepeatedFailuresEvenForTheCorrectPasswordUntilLockExpires() {
        SignedInUser user = TestUsers.register(port);
        BrowserClient attacker = new BrowserClient(port);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(attacker.post("/api/v1/auth/login",
                    Map.of("email", user.email(), "password", "guess-number-" + attempt)).status()).isEqualTo(401);
        }

        Response locked = new BrowserClient(port).post("/api/v1/auth/login",
                Map.of("email", user.email(), "password", TestUsers.PASSWORD));

        assertThat(locked.status()).isEqualTo(401);
        assertThat(locked.code()).isEqualTo("BAD_CREDENTIALS");
        Timestamp lockedUntil = jdbc.queryForObject("select locked_until from users where id = ?", Timestamp.class,
                user.id());
        assertThat(lockedUntil.toInstant()).isAfter(Instant.now());
        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(user.id(), AuditEventType.ACCOUNT_LOCKED))
                .hasSize(1);
        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(user.id(), AuditEventType.LOGIN_FAILED))
                .hasSize(6);

        // Once the lock has expired, the correct password works again and resets the counter.
        jdbc.update("update users set locked_until = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)),
                user.id());
        Response afterExpiry = new BrowserClient(port).post("/api/v1/auth/login",
                Map.of("email", user.email(), "password", TestUsers.PASSWORD));
        assertThat(afterExpiry.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select failed_login_count from users where id = ?", Integer.class, user.id()))
                .isZero();
    }

    @Test
    void loginRotatesSessionIdSoAPreviousSessionCannotBeReused() {
        SignedInUser user = TestUsers.register(port);
        BrowserClient browser = user.client();
        String sessionBefore = browser.cookie("APIFORGE_SESSION").orElseThrow();

        assertThat(browser.post("/api/v1/auth/login", Map.of("email", user.email(), "password", TestUsers.PASSWORD))
                .status()).isEqualTo(200);
        String sessionAfter = browser.cookie("APIFORGE_SESSION").orElseThrow();

        assertThat(sessionAfter).isNotEqualTo(sessionBefore);
        BrowserClient replay = new BrowserClient(port);
        replay.setCookie("APIFORGE_SESSION", sessionBefore);
        assertThat(replay.get("/api/v1/auth/me").status()).isEqualTo(401);
    }

    // ---- logout and unauthenticated access ------------------------------------------------

    @Test
    void logoutInvalidatesTheSessionServerSide() {
        SignedInUser user = TestUsers.register(port);
        String session = user.client().cookie("APIFORGE_SESSION").orElseThrow();

        Response logout = user.client().post("/api/v1/auth/logout", Map.of());

        assertThat(logout.status()).isEqualTo(204);
        assertThat(user.client().get("/api/v1/auth/me").status()).isEqualTo(401);
        BrowserClient replay = new BrowserClient(port);
        replay.setCookie("APIFORGE_SESSION", session);
        assertThat(replay.get("/api/v1/projects").status()).isEqualTo(401);
        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(user.id(), AuditEventType.LOGOUT))
                .hasSize(1);
    }

    @Test
    void protectedEndpointsRequireAuthenticationWithProblemResponse() {
        BrowserClient anonymous = new BrowserClient(port);

        for (String path : new String[] { "/api/v1/auth/me", "/api/v1/projects",
                "/api/v1/projects/" + UUID.randomUUID() + "/runs", "/api/v1/unknown-endpoint" }) {
            Response response = anonymous.get(path);
            assertThat(response.status()).as(path).isEqualTo(401);
            assertThat(response.code()).as(path).isEqualTo("UNAUTHENTICATED");
            assertThat(response.header("Content-Type")).hasValueSatisfying(
                    value -> assertThat(value).startsWith("application/problem+json"));
            assertThat(response.json().path("requestId").asString()).isNotBlank();
        }
        assertThat(anonymous.post("/api/v1/projects", Map.of("name", "x")).status()).isEqualTo(401);
    }

    @Test
    void publicEndpointsRemainAccessibleWithoutSession() {
        BrowserClient anonymous = new BrowserClient(port);

        assertThat(anonymous.get("/actuator/health").status()).isEqualTo(200);
        assertThat(anonymous.get("/api/v1/system/info").status()).isEqualTo(200);
        assertThat(anonymous.get("/api/v1/auth/csrf").status()).isEqualTo(204);
        assertThat(anonymous.cookie("XSRF-TOKEN")).isPresent();
    }

    // ---- CSRF ---------------------------------------------------------------------------

    @Test
    void stateChangingRequestsWithoutCsrfTokenAreRejected() {
        SignedInUser user = TestUsers.register(port);
        BrowserClient noCsrf = user.client().withoutCsrfHeader();

        Response createProject = noCsrf.post("/api/v1/projects", Map.of("name", "Blocked"));
        Response logout = noCsrf.post("/api/v1/auth/logout", Map.of());
        Response login = new BrowserClient(port).withoutCsrfHeader().post("/api/v1/auth/login",
                Map.of("email", user.email(), "password", TestUsers.PASSWORD));

        assertThat(createProject.status()).isEqualTo(403);
        assertThat(createProject.code()).isEqualTo("CSRF_INVALID");
        assertThat(logout.status()).isEqualTo(403);
        assertThat(login.status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from api_projects where owner_id = ?", Integer.class,
                user.id())).isZero();
    }

    @Test
    void csrfTokenIsRotatedOnLogin() {
        SignedInUser user = TestUsers.register(port);
        BrowserClient browser = user.client();
        String tokenBefore = browser.cookie("XSRF-TOKEN").orElseThrow();

        browser.post("/api/v1/auth/login", Map.of("email", user.email(), "password", TestUsers.PASSWORD));

        assertThat(browser.cookie("XSRF-TOKEN")).isPresent().get().isNotEqualTo(tokenBefore);
        // The new token works immediately for the next state-changing request.
        assertThat(browser.post("/api/v1/projects", Map.of("name", "After rotation")).status()).isEqualTo(201);
    }
}
