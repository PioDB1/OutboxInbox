package br.com.db1.poc.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.db1.poc.shared.support.Await;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KeyedExecutorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final KeyedExecutor executor = new KeyedExecutor(8);

    @AfterEach
    void shutdown() {
        executor.shutdown(1_000);
    }

    /** TC-13: mesma chave, na ordem de submissão e uma por vez. */
    @Test
    void mesmaChaveExecutaEmOrdemEUmaPorVez() {
        List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        int total = 100;

        for (int i = 0; i < total; i++) {
            final int number = i;
            executor.submit("A", turn -> {
                int now = running.incrementAndGet();
                maxRunning.accumulateAndGet(now, Math::max);
                order.add(number);
                running.decrementAndGet();
                turn.release();
            });
        }

        Await.until(TIMEOUT, "100 tarefas executadas", () -> order.size() == total);
        for (int i = 0; i < total; i++) {
            assertEquals(i, order.get(i).intValue());
        }
        assertEquals(1, maxRunning.get());
    }

    /** TC-14: chaves diferentes executam ao mesmo tempo. */
    @Test
    void chavesDiferentesExecutamEmParalelo() throws Exception {
        CountDownLatch allRunning = new CountDownLatch(8);
        AtomicInteger passed = new AtomicInteger();

        for (int i = 0; i < 8; i++) {
            executor.submit("chave-" + i, turn -> {
                allRunning.countDown();
                try {
                    if (allRunning.await(5, TimeUnit.SECONDS)) {
                        passed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    turn.release();
                }
            });
        }

        Await.until(TIMEOUT, "as 8 tarefas terminaram", () -> executor.activeKeys() == 0);
        assertEquals(8, passed.get());
    }

    /** A chave retém a vez até {@code release}, que pode vir de outra thread depois de uma espera. */
    @Test
    void aVezSoPassaNoRelease() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        List<KeyedExecutor.Turn> turns = Collections.synchronizedList(new ArrayList<KeyedExecutor.Turn>());
        AtomicInteger secondStarted = new AtomicInteger();

        executor.submit("A", turn -> {
            turns.add(turn);
            firstStarted.countDown();
        });
        executor.submit("A", turn -> secondStarted.incrementAndGet());
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);

        assertEquals(0, secondStarted.get());
        assertEquals(1, executor.activeKeys());

        turns.get(0).release();

        Await.until(TIMEOUT, "a segunda tarefa começou", () -> secondStarted.get() == 1);
    }

    @Test
    void liberarDuasVezesLiberaUmaSo() throws Exception {
        List<KeyedExecutor.Turn> turns = Collections.synchronizedList(new ArrayList<KeyedExecutor.Turn>());
        AtomicInteger started = new AtomicInteger();
        executor.submit("A", turns::add);
        executor.submit("A", turn -> {
            started.incrementAndGet();
            turns.add(turn);
        });
        executor.submit("A", turn -> started.incrementAndGet());
        Await.until(TIMEOUT, "primeira tarefa executou", () -> turns.size() == 1);

        turns.get(0).release();
        turns.get(0).release();
        Await.until(TIMEOUT, "segunda tarefa começou", () -> started.get() == 1);
        Thread.sleep(200);

        assertEquals(1, started.get());
    }

    /** TC-16: a estrutura da chave é liberada quando a última tarefa termina. */
    @Test
    void chaveSemTarefasPendentesELiberada() {
        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < 1_000; i++) {
            executor.submit("chave-" + i, turn -> {
                done.incrementAndGet();
                turn.release();
            });
        }

        Await.until(TIMEOUT, "1000 tarefas executadas", () -> done.get() == 1_000);
        Await.until(TIMEOUT, "nenhuma chave mantida", () -> executor.activeKeys() == 0);
    }

    @Test
    void chaveReutilizadaDepoisDeLiberada() {
        AtomicInteger done = new AtomicInteger();
        executor.submit("A", turn -> {
            done.incrementAndGet();
            turn.release();
        });
        Await.until(TIMEOUT, "primeira", () -> executor.activeKeys() == 0);

        executor.submit("A", turn -> {
            done.incrementAndGet();
            turn.release();
        });

        Await.until(TIMEOUT, "segunda", () -> done.get() == 2);
    }

    @Test
    void dispatchExecutaForaDasFilasDasChaves() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);

        executor.dispatch(ran::countDown);

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertEquals(0, executor.activeKeys());
    }

    @Test
    void depoisDoShutdownRecusaESoltaAChave() {
        executor.shutdown(1_000);

        assertThrows(RejectedExecutionException.class, () -> executor.submit("A", turn -> { }));
        assertEquals(0, executor.activeKeys());
    }
}
