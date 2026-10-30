package com.apiforge.environment.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.apiforge.common.persistence.AuditableEntity;

/**
 * A named value available to test requests as {@code {{name}}}. Secret values exist only as
 * ciphertext; the database rejects a secret stored in {@code plain_value}.
 */
@Entity
@Table(name = "environment_variables")
public class EnvironmentVariable extends AuditableEntity {

    @Column(name = "environment_id", nullable = false, updatable = false)
    private UUID environmentId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "secret", nullable = false)
    private boolean secret;

    @Column(name = "plain_value", columnDefinition = "text")
    private String plainValue;

    @Column(name = "encrypted_value")
    private byte[] encryptedValue;

    @Column(name = "key_id", length = 32)
    private String keyId;

    protected EnvironmentVariable() {
    }

    private EnvironmentVariable(UUID environmentId, String name, boolean secret, String plainValue,
            byte[] encryptedValue, String keyId) {
        this.environmentId = environmentId;
        this.name = name;
        this.secret = secret;
        this.plainValue = plainValue;
        this.encryptedValue = encryptedValue == null ? null : encryptedValue.clone();
        this.keyId = keyId;
    }

    public static EnvironmentVariable plain(UUID environmentId, String name, String value) {
        return new EnvironmentVariable(environmentId, name, false, value, null, null);
    }

    /** @param ciphertext output of the secrets cipher; never the plaintext value */
    public static EnvironmentVariable secret(UUID environmentId, String name, byte[] ciphertext, String keyId) {
        return new EnvironmentVariable(environmentId, name, true, null, ciphertext, keyId);
    }

    public void setPlain(String value) {
        this.secret = false;
        this.plainValue = value;
        this.encryptedValue = null;
        this.keyId = null;
    }

    /** @param ciphertext output of the secrets cipher; never the plaintext value */
    public void setSecret(byte[] ciphertext, String newKeyId) {
        this.secret = true;
        this.plainValue = null;
        this.encryptedValue = ciphertext.clone();
        this.keyId = newKeyId;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public String getName() {
        return name;
    }

    public boolean isSecret() {
        return secret;
    }

    public String getPlainValue() {
        return plainValue;
    }

    public byte[] getEncryptedValue() {
        return encryptedValue == null ? null : encryptedValue.clone();
    }

    public String getKeyId() {
        return keyId;
    }
}
