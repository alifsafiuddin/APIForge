package com.apiforge.config;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Application-specific configuration under the {@code apiforge.*} prefix.
 * Values are validated at startup so misconfiguration fails fast.
 */
@Validated
@ConfigurationProperties(prefix = "apiforge")
public record ApiForgeProperties(@Valid @NotNull @DefaultValue Cors cors) {

    /**
     * CORS settings for browser clients served from a different origin than the API
     * (the Vite dev server when it is not proxying). Wildcards are rejected because
     * credentials (session cookies) will be allowed on cross-origin requests.
     */
    public record Cors(
            @NotNull @DefaultValue
            List<@Pattern(regexp = "^https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?$",
                    message = "must be an explicit http(s) origin without path or wildcard") String> allowedOrigins) {
    }
}
