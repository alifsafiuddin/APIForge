package com.apiforge.secrets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Encryption key for secret environment variables (docs/SECURITY.md section 7).
 *
 * @param key   base64-encoded 256-bit AES key; required in every profile (startup fails without it)
 * @param keyId stored next to each ciphertext so keys can be rotated later
 */
@Validated
@ConfigurationProperties(prefix = "apiforge.secrets")
public record SecretsProperties(
        @NotBlank(message = "must be set (APIFORGE_SECRETS_KEY: base64 of 32 random bytes)") String key,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$") @DefaultValue("k1") String keyId) {
}
