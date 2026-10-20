package com.apiforge.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class ApiForgePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void emptyOriginListBindsToNoOrigins() {
        runner.withPropertyValues("apiforge.cors.allowed-origins=")
                .run(context -> assertThat(context.getBean(ApiForgeProperties.class).cors().allowedOrigins()).isEmpty());
    }

    @Test
    void explicitOriginsAreAccepted() {
        runner.withPropertyValues("apiforge.cors.allowed-origins=http://localhost:5173,https://app.example.com")
                .run(context -> assertThat(context.getBean(ApiForgeProperties.class).cors().allowedOrigins())
                        .containsExactly("http://localhost:5173", "https://app.example.com"));
    }

    @Test
    void wildcardOriginFailsStartup() {
        runner.withPropertyValues("apiforge.cors.allowed-origins=*")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void originWithPathFailsStartup() {
        runner.withPropertyValues("apiforge.cors.allowed-origins=http://localhost:5173/app")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApiForgeProperties.class)
    static class PropertiesConfig {
    }
}
