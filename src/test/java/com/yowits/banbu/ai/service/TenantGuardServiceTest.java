package com.yowits.banbu.ai.service;

import com.yowits.banbu.ai.config.AiGuardProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantGuardServiceTest {

    @Test
    void checkTenant_blocks_whenRequestsExceedLimit() {
        AiGuardProperties props = new AiGuardProperties();
        props.setDefaultRequestsPerMinute(2);
        TenantGuardService service = new TenantGuardService(
                props,
                Clock.fixed(Instant.parse("2026-03-10T10:00:00Z"), ZoneOffset.UTC)
        );

        assertThatCode(() -> service.checkTenant("t001")).doesNotThrowAnyException();
        assertThatCode(() -> service.checkTenant("t001")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.checkTenant("t001"))
                .isInstanceOf(TenantRateLimitExceededException.class)
                .hasMessageContaining("t001");
    }

    @Test
    void checkTenant_resetsCounter_onNewMinute() {
        AiGuardProperties props = new AiGuardProperties();
        props.setDefaultRequestsPerMinute(1);
        MutableClock clock = new MutableClock(Instant.parse("2026-03-10T10:00:00Z"));
        TenantGuardService service = new TenantGuardService(props, clock);

        assertThatCode(() -> service.checkTenant("t001")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.checkTenant("t001"))
                .isInstanceOf(TenantRateLimitExceededException.class);

        clock.setInstant(Instant.parse("2026-03-10T10:01:00Z"));
        assertThatCode(() -> service.checkTenant("t001")).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }
    }
}
