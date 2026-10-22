package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    /** TC-17: {@code min(max, initial × multiplier^(n-1))}. */
    @Test
    void intervalosCrescemAteOTeto() {
        RetryBackoff backoff = new RetryBackoff(Duration.ofMillis(100), 2.0, Duration.ofMillis(300));

        assertEquals(100, backoff.delayMillis(1));
        assertEquals(200, backoff.delayMillis(2));
        assertEquals(300, backoff.delayMillis(3));
        assertEquals(300, backoff.delayMillis(4));
    }

    @Test
    void multiplicadorUmMantemOIntervalo() {
        RetryBackoff backoff = new RetryBackoff(Duration.ofMillis(50), 1.0, Duration.ofSeconds(1));

        assertEquals(50, backoff.delayMillis(1));
        assertEquals(50, backoff.delayMillis(10));
    }

    @Test
    void numeroGrandeDeFalhasNaoEstouraOTeto() {
        RetryBackoff backoff = new RetryBackoff(Duration.ofMillis(200), 2.0, Duration.ofSeconds(30));

        assertEquals(30_000, backoff.delayMillis(5_000));
    }

    /** TC-23: configuração inválida do backoff. */
    @Test
    void configuracaoInvalidaERecusada() {
        Duration one = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(Duration.ZERO, 2.0, one));
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(one, 2.0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(one, Double.NaN, one));
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(one, Double.POSITIVE_INFINITY, one));
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(one, 0.5, one));
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(one, 2.0, Duration.ofMillis(10)));
    }
}
