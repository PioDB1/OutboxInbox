package br.com.db1.poc.outbox.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final AtomicLong clock = new AtomicLong();

    private BackoffPolicy policy(int maxKeys) {
        return new BackoffPolicy(Duration.ofMillis(200), 2.0, Duration.ofSeconds(2), maxKeys, clock::get);
    }

    @Test
    void intervalosCrescemExponencialmenteAteOTeto() {
        BackoffPolicy policy = policy(10);

        assertEquals(Duration.ofMillis(200), policy.delayAfter(1));
        assertEquals(Duration.ofMillis(400), policy.delayAfter(2));
        assertEquals(Duration.ofMillis(800), policy.delayAfter(3));
        assertEquals(Duration.ofMillis(1600), policy.delayAfter(4));
        assertEquals(Duration.ofSeconds(2), policy.delayAfter(5));
        assertEquals(Duration.ofSeconds(2), policy.delayAfter(500));
    }

    @Test
    void sequenciaDeIntervalosNuncaDecresceENuncaExcedeOTeto() {
        BackoffPolicy policy = policy(10);

        Duration previous = Duration.ZERO;
        for (int failure = 1; failure <= 40; failure++) {
            Duration delay = policy.delayAfter(failure);
            assertTrue(delay.compareTo(previous) >= 0, "falha " + failure);
            assertTrue(delay.compareTo(Duration.ofSeconds(2)) <= 0, "falha " + failure);
            previous = delay;
        }
    }

    @Test
    void chaveFicaBloqueadaAteOFimDoIntervalo() {
        BackoffPolicy policy = policy(10);

        policy.recordFailure("a");

        assertTrue(policy.isBlocked("a"));
        assertFalse(policy.isBlocked("b"));
        clock.addAndGet(Duration.ofMillis(199).toNanos());
        assertTrue(policy.isBlocked("a"));
        clock.addAndGet(Duration.ofMillis(1).toNanos());
        assertFalse(policy.isBlocked("a"));
    }

    @Test
    void falhasConsecutivasAumentamOIntervaloDaChave() {
        BackoffPolicy policy = policy(10);

        assertEquals(Duration.ofMillis(200), policy.recordFailure("a"));
        assertEquals(Duration.ofMillis(400), policy.recordFailure("a"));
        assertEquals(2, policy.failures("a"));
        assertEquals(Duration.ofMillis(200), policy.recordFailure("b"));
    }

    @Test
    void sucessoZeraOBackoffDaChave() {
        BackoffPolicy policy = policy(10);
        policy.recordFailure("a");
        policy.recordFailure("a");

        policy.recordSuccess("a");

        assertFalse(policy.isBlocked("a"));
        assertEquals(0, policy.failures("a"));
        assertEquals(Duration.ofMillis(200), policy.recordFailure("a"));
    }

    @Test
    void informaOTempoAteATentativaMaisProxima() {
        BackoffPolicy policy = policy(10);
        assertEquals(Optional.empty(), policy.untilNextAttempt());

        policy.recordFailure("a");
        policy.recordFailure("a");
        policy.recordFailure("b");
        clock.addAndGet(Duration.ofMillis(50).toNanos());

        assertEquals(Optional.of(Duration.ofMillis(150)), policy.untilNextAttempt());
    }

    @Test
    void estadoEmMemoriaTemNoMaximoMaxKeysChaves() {
        BackoffPolicy policy = policy(2);
        policy.recordFailure("a");
        clock.addAndGet(1);
        policy.recordFailure("b");
        clock.addAndGet(1);

        policy.recordFailure("c");

        assertEquals(0, policy.failures("a"));
        assertEquals(1, policy.failures("b"));
        assertEquals(1, policy.failures("c"));
    }
}
