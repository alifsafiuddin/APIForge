package com.apiforge.outbound;

import java.time.Duration;
import java.util.List;

import org.springframework.util.unit.DataSize;

import com.apiforge.outbound.OutboundProperties.TrustedTarget;

/** Builds outbound configurations for tests. */
final class OutboundTestSupport {

    private OutboundTestSupport() {
    }

    static OutboundProperties properties(List<TrustedTarget> trusted) {
        return properties(trusted, Duration.ofSeconds(5), DataSize.ofKilobytes(64));
    }

    static OutboundProperties properties(List<TrustedTarget> trusted, Duration totalDeadline, DataSize maxResponse) {
        return new OutboundProperties(!trusted.isEmpty(), trusted, false, Duration.ofSeconds(2), Duration.ofSeconds(60),
                totalDeadline, maxResponse, DataSize.ofKilobytes(16), DataSize.ofKilobytes(256), 3, 100, 16, 2, 8,
                6000, 500, "X-APIForge-Request-Id");
    }
}
