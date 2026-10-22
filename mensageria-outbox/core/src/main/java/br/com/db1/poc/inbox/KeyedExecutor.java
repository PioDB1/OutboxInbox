package br.com.db1.poc.inbox;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fila de execução por chave sobre um pool de threads de trabalho: uma tarefa por chave por vez, na ordem de
 * submissão, e chaves diferentes em paralelo.
 *
 * <p>A chave retém a vez desde o início da tarefa até {@link Turn#release()}, que pode ocorrer em outra thread e
 * depois de uma espera (o retry com backoff mantém a chave ocupada). A chave sem tarefas pendentes sai do mapa.
 */
final class KeyedExecutor {

    /** Tarefa de uma chave; deve chamar {@link Turn#release()} quando a chave puder passar para a próxima. */
    interface Task {
        void run(Turn turn);
    }

    /** Vez de uma chave; liberar mais de uma vez tem o efeito de liberar uma. */
    interface Turn {
        void release();
    }

    private final ThreadPoolExecutor pool;
    private final Map<String, Queue<Task>> keys = new HashMap<>();

    KeyedExecutor(int workers) {
        this.pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), new WorkerThreads());
    }

    /**
     * Enfileira a tarefa na chave; ela começa quando as anteriores da chave liberam a vez.
     *
     * @throws RejectedExecutionException o executor foi encerrado
     */
    void submit(String key, Task task) {
        boolean idle;
        synchronized (keys) {
            Queue<Task> waiting = keys.get(key);
            idle = waiting == null;
            if (idle) {
                keys.put(key, new ArrayDeque<Task>());
            } else {
                waiting.add(task);
            }
        }
        if (idle) {
            try {
                start(key, task);
            } catch (RejectedExecutionException e) {
                forget(key);
                throw e;
            }
        }
    }

    /** Executa o trabalho em uma thread do pool sem passar pela fila de nenhuma chave. */
    void dispatch(Runnable work) {
        pool.execute(work);
    }

    /** Chaves com tarefa em execução ou aguardando. */
    int activeKeys() {
        synchronized (keys) {
            return keys.size();
        }
    }

    /** Recusa novas tarefas, deixa terminar as já iniciadas e espera até {@code timeoutMillis}. */
    void shutdown(long timeoutMillis) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void start(String key, Task task) {
        pool.execute(() -> task.run(new KeyTurn(key)));
    }

    private void forget(String key) {
        synchronized (keys) {
            keys.remove(key);
        }
    }

    private void release(String key) {
        Task next;
        synchronized (keys) {
            Queue<Task> waiting = keys.get(key);
            next = waiting == null ? null : waiting.poll();
            if (next == null) {
                keys.remove(key);
                return;
            }
        }
        try {
            start(key, next);
        } catch (RejectedExecutionException e) {
            // executor encerrado: a tarefa não executa e a mensagem volta ao consumidor pelo broker
            forget(key);
        }
    }

    private final class KeyTurn implements Turn {

        private final String key;
        private final AtomicInteger released = new AtomicInteger();

        KeyTurn(String key) {
            this.key = key;
        }

        @Override
        public void release() {
            if (released.compareAndSet(0, 1)) {
                KeyedExecutor.this.release(key);
            }
        }
    }

    private static final class WorkerThreads implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "inbox-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
