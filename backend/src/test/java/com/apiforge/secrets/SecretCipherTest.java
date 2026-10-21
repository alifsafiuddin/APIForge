package com.apiforge.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
    private final SecretCipher cipher = new SecretCipher(new SecretsProperties(KEY, "k1"));

    @Test
    void roundTripWithAFreshIvEachTime() {
        byte[] first = cipher.encrypt("s3cr3t-value", "env:1:TOKEN");
        byte[] second = cipher.encrypt("s3cr3t-value", "env:1:TOKEN");

        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain("s3cr3t");
        assertThat(cipher.decrypt(first, "k1", "env:1:TOKEN")).isEqualTo("s3cr3t-value");
        assertThat(cipher.decrypt(second, "k1", "env:1:TOKEN")).isEqualTo("s3cr3t-value");
    }

    @Test
    void ciphertextIsBoundToItsContextKeyAndContent() {
        byte[] sealed = cipher.encrypt("s3cr3t-value", "env:1:TOKEN");
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        SecretCipher otherKey = new SecretCipher(new SecretsProperties(Base64.getEncoder()
                .encodeToString("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.US_ASCII)), "k1"));

        assertThatThrownBy(() -> cipher.decrypt(sealed, "k1", "env:2:TOKEN"))
                .isInstanceOf(SecretCipher.SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(sealed, "k2", "env:1:TOKEN"))
                .isInstanceOf(SecretCipher.SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(tampered, "k1", "env:1:TOKEN"))
                .isInstanceOf(SecretCipher.SecretDecryptionException.class);
        assertThatThrownBy(() -> otherKey.decrypt(sealed, "k1", "env:1:TOKEN"))
                .isInstanceOf(SecretCipher.SecretDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[5], "k1", "env:1:TOKEN"))
                .isInstanceOf(SecretCipher.SecretDecryptionException.class);
    }

    @Test
    void invalidKeysStopStartup() {
        assertThatThrownBy(() -> new SecretCipher(new SecretsProperties("not base64!", "k1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> new SecretCipher(new SecretsProperties(Base64.getEncoder().encodeToString(new byte[16]), "k1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }

    @Test
    void theDevelopmentKeyIsRefusedInProduction() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        SecretsProperties developmentKey = new SecretsProperties(SecretCipher.DEVELOPMENT_KEY, "k1");

        assertThatThrownBy(() -> new SecretsConfiguration(developmentKey, prod))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APIFORGE_SECRETS_KEY");
        new SecretsConfiguration(developmentKey, dev);
        new SecretsConfiguration(new SecretsProperties(KEY, "k1"), prod);
        assertThat(Base64.getDecoder().decode(SecretCipher.DEVELOPMENT_KEY)).hasSize(32);
    }
}
