package edu.arena.server.security;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded token buckets. Rejected requests keep existing keys active; new keys never evict them.
 */
public final class BoundedRateLimiter {
    private final int maxKeys;
    private final double ratePerNano;
    private final int burst;
    private final long idleNanos;
    private final LongSupplier nanoClock;
    private final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true);
    private long observedNow;

    public BoundedRateLimiter(
            int maxKeys,
            double ratePerSecond,
            int burst,
            Duration idleTtl,
            LongSupplier nanoClock) {
        Objects.requireNonNull(idleTtl, "idleTtl");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (maxKeys < 1
                || maxKeys > 1_000_000
                || !Double.isFinite(ratePerSecond)
                || ratePerSecond <= 0
                || ratePerSecond > 1_000_000
                || burst < 1
                || burst > 1_000_000) {
            throw new IllegalArgumentException("Invalid rate limiter capacity or rate");
        }
        try {
            this.idleNanos = idleTtl.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Idle TTL is too large", e);
        }
        // Expiration must not replace a partially replenished bucket with a full burst.
        if (idleNanos <= 0 || idleNanos < burst / ratePerSecond * 1_000_000_000.0) {
            throw new IllegalArgumentException(
                    "Idle TTL must cover replenishment of a complete burst");
        }
        this.maxKeys = maxKeys;
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        this.burst = burst;
        this.observedNow = nanoClock.getAsLong();
    }

    public synchronized boolean allow(String key) {
        if (key == null || key.isEmpty() || key.length() > 256) {
            return false;
        }
        long now = now();
        expire(now);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                return false;
            }
            bucket = new Bucket(burst, now);
            buckets.put(key, bucket);
        }
        bucket.tokens = Math.min(burst, bucket.tokens + (now - bucket.updatedAt) * ratePerNano);
        bucket.updatedAt = now;
        if (bucket.tokens < 1.0) {
            return false;
        }
        bucket.tokens -= 1.0;
        return true;
    }

    public synchronized int keyCount() {
        expire(now());
        return buckets.size();
    }

    private long now() {
        long candidate = nanoClock.getAsLong();
        if (candidate - observedNow >= 0) {
            observedNow = candidate;
        }
        return observedNow;
    }

    private void expire(long now) {
        var entries = buckets.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue().updatedAt < idleNanos) {
                break;
            }
            entries.remove();
        }
    }

    private static final class Bucket {
        private double tokens;
        private long updatedAt;

        private Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.updatedAt = now;
        }
    }
}
