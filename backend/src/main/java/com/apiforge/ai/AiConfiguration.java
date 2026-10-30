package com.apiforge.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the AI settings and logs where the model is expected (never anything secret). */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger("com.apiforge.ai");

    public AiConfiguration(AiProperties properties) {
        if (properties.enabled()) {
            log.info("AI: model {} at {} (optional; checked on demand)", properties.model(), properties.origin());
        } else {
            log.info("AI: disabled");
        }
    }
}
