package com.apiforge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.apiforge.common.web.RequestCorrelationFilter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiForgeProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final ApiForgeProperties properties;

    public WebConfig(ApiForgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.cors().allowedOrigins().toArray(String[]::new);
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept", RequestCorrelationFilter.HEADER, "X-XSRF-TOKEN")
                .exposedHeaders(RequestCorrelationFilter.HEADER)
                .allowCredentials(true)
                .maxAge(3600);
    }
}
