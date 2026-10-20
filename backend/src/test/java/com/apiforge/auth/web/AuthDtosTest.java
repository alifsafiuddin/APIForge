package com.apiforge.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.apiforge.auth.web.AuthDtos.LoginRequest;
import com.apiforge.auth.web.AuthDtos.RegisterRequest;

/** Request bodies may end up in logs or exception messages; the password must never appear. */
class AuthDtosTest {

    private static final String SECRET = "s3cret-Value-Never-Logged";

    @Test
    void loginRequestRedactsPassword() {
        assertThat(new LoginRequest("a@example.test", SECRET).toString())
                .doesNotContain(SECRET)
                .contains("<redacted>");
    }

    @Test
    void registerRequestRedactsPassword() {
        assertThat(new RegisterRequest("a@example.test", SECRET, "Ada").toString())
                .doesNotContain(SECRET)
                .contains("<redacted>")
                .contains("Ada");
    }
}
