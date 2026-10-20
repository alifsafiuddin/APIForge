package com.apiforge.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @ParameterizedTest
    @ValueSource(strings = { "correct-horse-battery-7", "twelve chars", "ünïcödé-pässwörd" })
    void acceptsReasonablePasswords(String password) {
        assertThat(policy.violations(password, "someone@example.test")).isEmpty();
    }

    @Test
    void acceptsMaximumLength() {
        assertThat(policy.violations("a1b2".repeat(16), "someone@example.test")).isEmpty();
    }

    @Test
    void enforcesLengthRange() {
        assertThat(policy.violations("elevenchars", "a@example.test")).containsExactly(
                "must be between 12 and 64 characters");
        assertThat(policy.violations("a".repeat(65), "a@example.test")).contains(
                "must be between 12 and 64 characters");
    }

    @Test
    void countsCharactersNotUtf16Units() {
        // 12 emoji are 24 UTF-16 units and 48 bytes: valid by length and within the bcrypt byte limit.
        assertThat(policy.violations("😀".repeat(12), "a@example.test")).isEmpty();
    }

    @Test
    void rejectsInputBeyondBcrypt72ByteLimitInsteadOfTruncating() {
        // 30 characters, 90 bytes.
        assertThat(policy.violations("€".repeat(30), "a@example.test")).containsExactly(
                "must not exceed 72 bytes when UTF-8 encoded");
    }

    @Test
    void rejectsCommonPasswordsCaseInsensitively() {
        assertThat(policy.violations("Password1234", "a@example.test")).containsExactly(
                "is too common; choose a less predictable password");
    }

    @Test
    void rejectsPasswordsContainingTheEmailAddress() {
        assertThat(policy.violations("alice@example.test", "alice@example.test")).contains(
                "must not contain your e-mail address");
        assertThat(policy.violations("my-alice-secret-pass", "Alice@example.test")).contains(
                "must not contain your e-mail address");
        // Very short local parts are not treated as identifying.
        assertThat(policy.violations("jo-has-a-strong-pass", "jo@example.test")).isEmpty();
    }

    @Test
    void rejectsWhitespaceOnlyAndEmpty() {
        assertThat(policy.violations(" ".repeat(12), "a@example.test")).contains("must not consist only of whitespace");
        assertThat(policy.violations("", "a@example.test")).containsExactly("must not be empty");
    }
}
