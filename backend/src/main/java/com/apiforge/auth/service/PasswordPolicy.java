package com.apiforge.auth.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Password rules following NIST SP 800-63B: a length range, no composition rules, and a
 * block-list of commonly used passwords. The 72-byte cap exists because bcrypt ignores
 * everything after 72 bytes; longer input is rejected rather than silently truncated.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 64;
    static final int MAX_BYTES = 72;

    private final Set<String> commonPasswords;

    public PasswordPolicy() {
        this(loadCommonPasswords());
    }

    PasswordPolicy(Set<String> commonPasswords) {
        this.commonPasswords = commonPasswords;
    }

    /** Returns human-readable violations; empty when the password is acceptable. */
    public List<String> violations(String password, String email) {
        List<String> violations = new ArrayList<>();
        if (password == null || password.isEmpty()) {
            violations.add("must not be empty");
            return violations;
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            violations.add("must be between %d and %d characters".formatted(MIN_LENGTH, MAX_LENGTH));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            violations.add("must not exceed %d bytes when UTF-8 encoded".formatted(MAX_BYTES));
        }
        if (password.isBlank()) {
            violations.add("must not consist only of whitespace");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (commonPasswords.contains(lower)) {
            violations.add("is too common; choose a less predictable password");
        }
        if (containsEmail(lower, EmailAddresses.normalize(email))) {
            violations.add("must not contain your e-mail address");
        }
        return violations;
    }

    /** True if the password contains the address or its local part (when that part is 4+ characters). */
    private static boolean containsEmail(String lowerPassword, String normalizedEmail) {
        if (normalizedEmail.isEmpty()) {
            return false;
        }
        int at = normalizedEmail.indexOf('@');
        String localPart = at > 0 ? normalizedEmail.substring(0, at) : normalizedEmail;
        return lowerPassword.contains(normalizedEmail) || (localPart.length() >= 4 && lowerPassword.contains(localPart));
    }

    private static Set<String> loadCommonPasswords() {
        ClassPathResource resource = new ClassPathResource("auth/common-passwords.txt");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot load common password list", ex);
        }
    }
}
