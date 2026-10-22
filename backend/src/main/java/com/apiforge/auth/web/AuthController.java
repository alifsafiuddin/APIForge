package com.apiforge.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AccountService;
import com.apiforge.auth.service.AuthService;
import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.auth.web.AuthDtos.DeleteAccountRequest;
import com.apiforge.auth.web.AuthDtos.LoginRequest;
import com.apiforge.auth.web.AuthDtos.PasswordChangeRequest;
import com.apiforge.auth.web.AuthDtos.ProfileRequest;
import com.apiforge.auth.web.AuthDtos.RegisterRequest;
import com.apiforge.auth.web.AuthDtos.UserResponse;

/**
 * Session-based authentication for the SPA. Logout is handled by Spring Security's logout
 * filter at {@code POST /api/v1/auth/logout} (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AccountService accounts;
    private final SessionAuthenticator sessionAuthenticator;

    public AuthController(AuthService authService, AccountService accounts, SessionAuthenticator sessionAuthenticator) {
        this.authService = authService;
        this.accounts = accounts;
        this.sessionAuthenticator = sessionAuthenticator;
    }

    /**
     * Issues the {@code XSRF-TOKEN} cookie. The SPA calls this before its first state-changing
     * request; the token is written by Spring Security's CSRF handling on every response.
     */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf() {
        // Intentionally empty: the CSRF filter attaches the cookie.
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication = authService.register(body.email(), body.password(), body.displayName());
        sessionAuthenticator.establish(authentication, request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(authentication));
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication = authService.authenticate(body.email(), body.password());
        sessionAuthenticator.establish(authentication, request, response);
        return toResponse(authentication);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return UserResponse.from(user);
    }

    @PatchMapping("/me")
    public UserResponse updateProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ProfileRequest body, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = accounts.rename(user.id(), body.displayName());
        sessionAuthenticator.establish(authentication, request, response);
        return toResponse(authentication);
    }

    /** Changes the password; this session continues with a new id, every other session ends. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PasswordChangeRequest body, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = accounts.changePassword(user.id(), body.currentPassword(), body.newPassword());
        sessionAuthenticator.establish(authentication, request, response);
    }

    /** Deletes the account and all its projects, then ends the session. */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody DeleteAccountRequest body, HttpServletRequest request) {
        accounts.delete(user.id(), body.password(), body.confirmEmail());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private static UserResponse toResponse(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("An authenticated session always has an AuthenticatedUser principal");
        }
        return UserResponse.from(user);
    }
}
