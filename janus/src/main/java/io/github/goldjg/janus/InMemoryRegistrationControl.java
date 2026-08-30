package io.github.goldjg.janus;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Per-process rate, exhaustion, and idempotency guard.
 *
 * <p>This is deliberately a secondary control. Multi-replica deployments must
 * also enforce admission/rate limits at ingress and monitor tenant object quotas.
 */
final class InMemoryRegistrationControl {
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final ArrayDeque<Long> globalAttempts = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Long>> sourceAttempts = new HashMap<>();
    private final Map<String, CacheEntry> completed = new HashMap<>();
    private final Map<String, CompletableFuture<ProvisionedClient>> inFlight = new HashMap<>();
    private int successfulRegistrations;

    InMemoryRegistrationControl() {
        this(Clock.systemUTC());
    }

    InMemoryRegistrationControl(Clock clock) {
        this.clock = clock;
    }

    RegistrationOutcome execute(String sourceKey, String idempotencyKey, JanusConfig config,
            Supplier<ProvisionedClient> operation) {
        CompletableFuture<ProvisionedClient> future;
        boolean owner = false;
        synchronized (this) {
            long now = clock.millis();
            prune(now);
            CacheEntry cached = completed.get(idempotencyKey);
            if (cached != null) {
                return new RegistrationOutcome(cached.client(), true);
            }
            future = inFlight.get(idempotencyKey);
            if (future == null) {
                if (successfulRegistrations >= config.getMaxRegistrationsPerProcess()) {
                    throw new RegistrationLimitException("registration capacity is temporarily exhausted");
                }
                enforceRate(sourceAttempts.computeIfAbsent(sourceKey, ignored -> new ArrayDeque<>()),
                        config.getSourceRatePerMinute(), "source registration rate exceeded");
                enforceRate(globalAttempts, config.getGlobalRatePerMinute(),
                        "global registration rate exceeded");
                sourceAttempts.get(sourceKey).addLast(now);
                globalAttempts.addLast(now);
                future = new CompletableFuture<>();
                inFlight.put(idempotencyKey, future);
                owner = true;
            }
        }

        if (!owner) {
            return new RegistrationOutcome(join(future), true);
        }

        try {
            ProvisionedClient created = operation.get();
            synchronized (this) {
                successfulRegistrations++;
                completed.put(idempotencyKey, new CacheEntry(created,
                        clock.millis() + Duration.ofSeconds(config.getIdempotencyTtlSeconds()).toMillis()));
                inFlight.remove(idempotencyKey);
                future.complete(created);
            }
            return new RegistrationOutcome(created, false);
        } catch (RuntimeException e) {
            synchronized (this) {
                inFlight.remove(idempotencyKey);
                future.completeExceptionally(e);
            }
            throw e;
        }
    }

    private void enforceRate(ArrayDeque<Long> attempts, int maximum, String message) {
        if (attempts.size() >= maximum) {
            throw new RegistrationLimitException(message);
        }
    }

    private void prune(long now) {
        long cutoff = now - WINDOW.toMillis();
        globalAttempts.removeIf(attempt -> attempt <= cutoff);
        sourceAttempts.values().forEach(attempts -> attempts.removeIf(attempt -> attempt <= cutoff));
        sourceAttempts.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        completed.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private static ProvisionedClient join(CompletableFuture<ProvisionedClient> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    private record CacheEntry(ProvisionedClient client, long expiresAtMillis) {}
}
