package com.apiforge.auth.service;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.apiforge.config.ApiForgeProperties;

@Configuration(proxyBeanMethods = false)
public class AuthenticationConfig {

    private static final String BCRYPT = "bcrypt";

    /**
     * Hashes are stored with an algorithm prefix ("{bcrypt}..."), so the algorithm or its cost
     * can change later without invalidating existing hashes.
     */
    @Bean
    PasswordEncoder passwordEncoder(ApiForgeProperties properties) {
        return new DelegatingPasswordEncoder(BCRYPT,
                Map.of(BCRYPT, new BCryptPasswordEncoder(properties.auth().bcryptStrength())));
    }

    /**
     * DaoAuthenticationProvider hashes a dummy password when the e-mail is unknown, so response
     * time does not reveal whether an account exists.
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
