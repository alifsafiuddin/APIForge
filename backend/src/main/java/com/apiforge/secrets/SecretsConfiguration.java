package com.apiforge.secrets;

import java.util.Arrays;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@EnableConfigurationProperties(SecretsProperties.class)
public class SecretsConfiguration {

    /** The documented development key must never protect production data. */
    public SecretsConfiguration(SecretsProperties properties, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))
                && SecretCipher.DEVELOPMENT_KEY.equals(properties.key().strip())) {
            throw new IllegalStateException("The development secrets key cannot be used with the prod profile; "
                    + "set APIFORGE_SECRETS_KEY to base64 of 32 random bytes. Active profiles: "
                    + Arrays.toString(environment.getActiveProfiles()));
        }
    }
}
