package br.com.db1.poc.shared.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Espera por condição com timeout explícito; falha com a descrição do que não aconteceu. */
public final class Await {

    private static final Duration STEP = Duration.ofMillis(50);

    private Await() {
    }

    public static void until(Duration timeout, String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("Condição não satisfeita em " + timeout + ": " + description);
            }
            try {
                Thread.sleep(STEP.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Espera interrompida: " + description, e);
            }
        }
    }
}
