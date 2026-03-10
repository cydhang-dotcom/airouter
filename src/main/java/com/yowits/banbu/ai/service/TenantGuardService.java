package com.yowits.banbu.ai.service;

import com.yowits.banbu.ai.config.AiGuardProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TenantGuardService {

    private final AiGuardProperties guardProperties;
    private final Clock clock;
    private final Map<String, CounterWindow> counters = new ConcurrentHashMap<>();

    @Autowired
    public TenantGuardService(AiGuardProperties guardProperties) {
        this(guardProperties, Clock.systemUTC());
    }

    TenantGuardService(AiGuardProperties guardProperties, Clock clock) {
        this.guardProperties = guardProperties;
        this.clock = clock;
    }

    public void checkTenant(String tenantId) {
        if (!guardProperties.isEnabled()) {
            return;
        }
        int limit = guardProperties.resolveRequestsPerMinute(tenantId);
        if (limit <= 0) {
            return;
        }
        String key = tenantId == null || tenantId.isBlank() ? "_anonymous" : tenantId;
        long minute = clock.instant().getEpochSecond() / 60;
        CounterWindow counter = counters.computeIfAbsent(key, ignored -> new CounterWindow(minute, 0));
        synchronized (counter) {
            if (counter.minute != minute) {
                counter.minute = minute;
                counter.count = 0;
            }
            counter.count++;
            if (counter.count > limit) {
                throw new TenantRateLimitExceededException("Tenant rate limit exceeded for tenantId=" + key);
            }
        }
    }

    public void reset() {
        counters.clear();
    }

    private static final class CounterWindow {
        private long minute;
        private int count;

        private CounterWindow(long minute, int count) {
            this.minute = minute;
            this.count = count;
        }
    }
}
