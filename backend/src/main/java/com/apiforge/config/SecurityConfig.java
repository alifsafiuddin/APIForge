package com.apiforge.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.apiforge.common.error.ErrorCode;
import com.apiforge.common.error.ProblemResponseWriter;
import com.apiforge.common.web.RequestCorrelationFilter;

/**
 * HTTP security: server-side sessions with CSRF protection for the SPA (docs/DECISIONS.md
 * ADR-008). Every endpoint requires authentication unless listed here as public; ownership of
 * individual resources is enforced in the services.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(ApiForgeProperties.class)
public class SecurityConfig {

    public static final String SESSION_COOKIE = "APIFORGE_SESSION";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository, ProblemResponseWriter problemWriter) {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(SESSION_COOKIE)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> problemWriter.write(request, response,
                                ErrorCode.UNAUTHENTICATED, "Authentication is required."))
                        .accessDeniedHandler((request, response, ex) -> {
                            if (ex instanceof CsrfException) {
                                problemWriter.write(request, response, ErrorCode.CSRF_INVALID,
                                        "Missing or invalid CSRF token. Reload the page and try again.");
                            } else {
                                problemWriter.write(request, response, ErrorCode.FORBIDDEN,
                                        "You do not have permission to perform this action.");
                            }
                        }))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/info", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    /** Readable by the SPA (not HttpOnly) by design: it echoes the value in the X-XSRF-TOKEN header. */
    @Bean
    CsrfTokenRepository csrfTokenRepository(ApiForgeProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE);
        repository.setHeaderName(CSRF_HEADER);
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .sameSite("Strict")
                .secure(properties.auth().secureCookies()));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** New session id (fixation protection) and new CSRF token on every sign-in. */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    /** CORS for browser clients on other origins; empty (no cross-origin access) unless configured. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(ApiForgeProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = properties.cors().allowedOrigins();
        if (!origins.isEmpty()) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(origins);
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Content-Type", "Accept", RequestCorrelationFilter.HEADER, CSRF_HEADER));
            configuration.setExposedHeaders(List.of(RequestCorrelationFilter.HEADER));
            configuration.setAllowCredentials(true);
            configuration.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", configuration);
        }
        return source;
    }
}
