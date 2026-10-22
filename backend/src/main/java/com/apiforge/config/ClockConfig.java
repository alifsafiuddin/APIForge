package com.apiforge.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Injected wherever the current time is needed, so tests can control it. Instants are truncated
     * to whole microseconds, the precision of PostgreSQL {@code timestamptz}: a timestamp returned
     * right after a write is then exactly the one a later read returns (the system clock can be finer,
     * e.g. 100 ns on Windows, and PostgreSQL rounds the rest away).
     */
    @Bean
    Clock clock() {
        return new MicrosecondClock(Clock.systemUTC());
    }

    /**
     * Not {@code Clock.tick(base, 1 µs)}: the JDK's tick clock divides by the tick in whole
     * milliseconds in {@code millis()}, which fails for ticks below one millisecond.
     */
    static final class MicrosecondClock extends Clock {

        private final Clock base;

        MicrosecondClock(Clock base) {
            this.base = base;
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MicrosecondClock(base.withZone(zone));
        }

        @Override
        public Instant instant() {
            return base.instant().truncatedTo(ChronoUnit.MICROS);
        }

        @Override
        public long millis() {
            return base.millis();
        }
    }
}
