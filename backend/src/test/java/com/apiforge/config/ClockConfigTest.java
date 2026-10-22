package com.apiforge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ClockConfigTest {

    private final Clock fixed = Clock.fixed(Instant.parse("2026-10-22T08:05:49.798466800Z"), ZoneOffset.UTC);
    private final Clock clock = new ClockConfig.MicrosecondClock(fixed);

    @Test
    void instantsHaveMicrosecondPrecisionLikePostgres() {
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-22T08:05:49.798466Z"));
    }

    @Test
    void millisAndZoneComeFromTheBaseClock() {
        assertThat(clock.millis()).isEqualTo(fixed.millis());
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.withZone(ZoneId.of("Asia/Kuala_Lumpur")).getZone()).isEqualTo(ZoneId.of("Asia/Kuala_Lumpur"));
        assertThat(new ClockConfig().clock().millis()).isPositive();
    }
}
