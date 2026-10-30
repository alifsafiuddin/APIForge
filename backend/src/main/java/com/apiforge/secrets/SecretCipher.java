package com.apiforge.secrets;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for secret values. Each value gets a random 96-bit IV; the stored form is
 * {@code IV || ciphertext || tag}. The caller's context (environment id and variable name) is
 * bound as additional authenticated data, so a ciphertext copied to another row fails to decrypt.
 */
@Component
public class SecretCipher {

    /** The dev profile's documented key; refused when the prod profile is active. */
    public static final String DEVELOPMENT_KEY = "ZGV2LW9ubHkta2V5LWRvLW5vdC11c2UtaW4tcHJvZCE=";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(SecretsProperties properties) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(properties.key().strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("apiforge.secrets.key is not valid base64", ex);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("apiforge.secrets.key must decode to 32 bytes (AES-256), got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
        this.keyId = properties.keyId();
    }

    public String keyId() {
        return keyId;
    }

    public byte[] encrypt(String plaintext, String context) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Encryption failed", ex);
        }
    }

    /** @throws SecretDecryptionException when the value was not sealed with this key and context */
    public String decrypt(byte[] stored, String storedKeyId, String context) {
        if (!keyId.equals(storedKeyId) || stored == null || stored.length <= IV_BYTES) {
            throw new SecretDecryptionException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new SecretDecryptionException();
        }
    }

    /** A stored secret cannot be decrypted (wrong key, key id or context, or tampered data). */
    public static class SecretDecryptionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SecretDecryptionException() {
            super("The secret cannot be decrypted with the configured key.");
        }
    }
}
