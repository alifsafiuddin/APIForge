package com.apiforge.auth.web;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.apiforge.auth.service.AuthenticatedUser;

/** Request and response bodies of the auth API. Passwords are never echoed or logged. */
public final class AuthDtos {

    /** Rejects ASCII control characters (including CR/LF) in display names. */
    static final String NO_CONTROL_CHARACTERS = "^[^\\p{Cntrl}]*$";
    static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 254) @Email(regexp = EMAIL_PATTERN) String email,
            // Detailed rules (length, bytes, common passwords) are applied by PasswordPolicy.
            @NotNull @Size(max = 256) String password,
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS,
                    message = "must not contain control characters") String displayName) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", password=<redacted>, displayName=" + displayName + "]";
        }
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 256) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=<redacted>]";
        }
    }

    public record UserResponse(UUID id, String email, String displayName) {

        static UserResponse from(AuthenticatedUser user) {
            return new UserResponse(user.id(), user.email(), user.displayName());
        }
    }

    public record ProfileRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS,
                    message = "must not contain control characters") String displayName) {
    }

    public record PasswordChangeRequest(@NotNull @Size(max = 256) String currentPassword,
            @NotNull @Size(max = 256) String newPassword) {

        @Override
        public String toString() {
            return "PasswordChangeRequest[currentPassword=***, newPassword=***]";
        }
    }

    public record DeleteAccountRequest(@NotNull @Size(max = 256) String password, @NotBlank @Size(max = 254) String confirmEmail) {

        @Override
        public String toString() {
            return "DeleteAccountRequest[password=***, confirmEmail=" + confirmEmail + "]";
        }
    }
}
