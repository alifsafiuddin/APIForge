package com.apiforge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import com.apiforge.auth.domain.User;
import com.apiforge.auth.repository.UserRepository;
import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TestUsers;

/** Behaviour with registration disabled and a low per-IP sign-in limit (separate context). */
@IntegrationTest
@TestPropertySource(properties = {
        "apiforge.auth.registration-enabled=false",
        "apiforge.auth.login-requests-per-minute=3"
})
class RestrictedAuthConfigurationIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void registrationCanBeDisabled() {
        String email = TestUsers.uniqueEmail();

        Response response = new BrowserClient(port).post("/api/v1/auth/register", TestUsers.registration(email));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("REGISTRATION_DISABLED");
        assertThat(users.existsByEmail(email)).isFalse();
    }

    @Test
    void signInAttemptsAreRateLimitedPerClientBeforeCredentialsAreChecked() {
        String email = TestUsers.uniqueEmail();
        users.saveAndFlush(new User(email, passwordEncoder.encode(TestUsers.PASSWORD), "Limited"));
        Map<String, Object> credentials = Map.of("email", email, "password", TestUsers.PASSWORD);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(new BrowserClient(port).post("/api/v1/auth/login", credentials).status()).isEqualTo(200);
        }
        Response limited = new BrowserClient(port).post("/api/v1/auth/login", credentials);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.header("Retry-After")).hasValueSatisfying(
                value -> assertThat(Integer.parseInt(value)).isBetween(1, 60));
    }
}
