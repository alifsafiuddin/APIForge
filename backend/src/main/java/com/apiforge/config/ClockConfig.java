package com.apiforge.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** Injected wherever the current time is needed, so tests can control it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
