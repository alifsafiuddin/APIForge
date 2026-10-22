package com.apiforge.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.apiforge.auth.service.CredentialEpochs;

/**
 * For {@code @WebMvcTest} slices: they include the session filters but no repositories, so the
 * filter's {@link CredentialEpochs} gets a lookup in which no password has ever changed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SliceCredentialEpochs {

    @Bean
    CredentialEpochs credentialEpochs() {
        return new CredentialEpochs(userId -> 0L);
    }
}
