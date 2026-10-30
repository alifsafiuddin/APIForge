package com.apiforge.outbound;

import java.net.InetAddress;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.apiforge.outbound.DestinationPolicy.HostLookup;
import com.apiforge.outbound.OutboundProperties.TrustedTarget;

/** Wires the outbound policy and client, and refuses unsafe trusted-target configurations at startup. */
@Configuration
@EnableConfigurationProperties(OutboundProperties.class)
public class OutboundConfiguration {

    @Bean
    DestinationPolicy destinationPolicy(OutboundProperties properties, Environment environment) {
        validate(properties, environment);
        HostLookup lookup = InetAddress::getAllByName;
        return new DestinationPolicy(properties, lookup);
    }

    @Bean(destroyMethod = "close")
    OutboundHttpClient outboundHttpClient(OutboundProperties properties, DestinationPolicy policy) {
        return new OutboundHttpClient(properties, policy);
    }

    /**
     * Trusted targets exist for local development. They need an explicit acknowledgement in the
     * prod profile, and may never name an address that trust cannot unlock (link-local, metadata).
     */
    static void validate(OutboundProperties properties, Environment environment) {
        if (!properties.trustedTargetsEnabled()) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("prod")) && !properties.allowTrustedInProduction()) {
            throw new IllegalStateException("apiforge.outbound.trusted-targets-enabled is true with the prod profile. "
                    + "Trusted targets bypass private-network protection; set "
                    + "APIFORGE_OUTBOUND_ALLOW_TRUSTED_IN_PROD=true only for a self-hosted single-user installation.");
        }
        for (TrustedTarget target : properties.trustedTargets()) {
            try {
                UrlGuard.ParsedUrl parsed = UrlGuard.parse("http://" + target.host() + ":" + target.port() + "/");
                if (parsed.isLiteral()) {
                    AddressClassifier.Classification classification = AddressClassifier.classify(parsed.literal());
                    if (classification.blocked() && !classification.trustable()) {
                        throw new IllegalStateException("Trusted target " + target.host() + " is a "
                                + classification.reason() + "; such addresses can never be trusted.");
                    }
                }
            } catch (OutboundBlockedException ex) {
                throw new IllegalStateException("Trusted target " + target.host() + ":" + target.port()
                        + " is not a valid host: " + ex.getMessage(), ex);
            }
        }
    }
}
