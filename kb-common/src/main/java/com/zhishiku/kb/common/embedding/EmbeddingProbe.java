package com.zhishiku.kb.common.embedding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class EmbeddingProbe {
    public static final String SYNTHETIC_INPUT = "embedding readiness probe";

    private final UUID instanceId;
    private final EmbeddingClientRegistry clients;
    private final Clock clock;
    private final Duration interval;
    private final Duration lifetime;
    private Instant nextProbe = Instant.MIN;

    public EmbeddingProbe(EmbeddingClientRegistry clients, Clock clock, Duration interval, Duration lifetime) {
        this(clients, clock, interval, lifetime, UUID.randomUUID());
    }

    public EmbeddingProbe(EmbeddingClientRegistry clients, Clock clock, Duration interval, Duration lifetime,
                          UUID instanceId) {
        if (interval.isNegative() || interval.isZero() || lifetime.compareTo(interval) <= 0) {
            throw new IllegalArgumentException("embedding probe interval must be positive and shorter than lifetime");
        }
        this.clients = clients;
        this.clock = clock;
        this.interval = interval;
        this.lifetime = lifetime;
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    public synchronized void poll(Consumer<EmbeddingReadiness> store) {
        if (clock.instant().isBefore(nextProbe)) return;
        try {
            for (var client : clients.clients()) {
                boolean ready;
                try {
                    client.embed(List.of(SYNTHETIC_INPUT)).require(client.profile(), client.role(), 1);
                    ready = true;
                } catch (RuntimeException e) {
                    ready = false;
                }
                Instant checkedAt = clock.instant();
                store.accept(new EmbeddingReadiness(instanceId, client.role(), client.profile().profileId(),
                        client.profile().fingerprint(), EmbeddingReadiness.PROTOCOL_VERSION, ready,
                        checkedAt, checkedAt.plus(lifetime)));
            }
        } finally {
            nextProbe = clock.instant().plus(interval);
        }
    }
}
