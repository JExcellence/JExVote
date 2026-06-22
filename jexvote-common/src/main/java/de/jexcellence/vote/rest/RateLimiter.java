package de.jexcellence.vote.rest;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple sliding-window rate limiter keyed by client IP address.
 * Tracks request counts in 60-second windows. Thread-safe.
 */
final class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int maxPerMinute;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    RateLimiter(int maxPerMinute) {
        this.maxPerMinute = maxPerMinute;
    }

    boolean tryAcquire(@NotNull String ip) {
        if (maxPerMinute <= 0) return true;
        WindowCounter counter = counters.computeIfAbsent(ip, k -> new WindowCounter());
        return counter.tryIncrement(maxPerMinute);
    }

    /** Evict stale entries — call periodically from a background task. */
    void evictStale() {
        long cutoff = System.currentTimeMillis() - WINDOW_MILLIS * 2;
        counters.entrySet().removeIf(e -> e.getValue().windowStart < cutoff);
    }

    private static final class WindowCounter {
        volatile long windowStart = System.currentTimeMillis();
        private final AtomicInteger count = new AtomicInteger(0);

        boolean tryIncrement(int max) {
            long now = System.currentTimeMillis();
            if (now - windowStart > WINDOW_MILLIS) {
                // Reset window — slight race is acceptable for rate limiting.
                windowStart = now;
                count.set(1);
                return true;
            }
            return count.incrementAndGet() <= max;
        }
    }
}
