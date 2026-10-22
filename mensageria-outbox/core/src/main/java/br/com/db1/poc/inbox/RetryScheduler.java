package br.com.db1.poc.inbox;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Agenda a repetição de uma tarefa depois de um intervalo, fora das threads de trabalho: a espera do backoff não
 * ocupa thread de trabalho. A tarefa agendada só repassa o trabalho às threads de trabalho.
 */
interface RetryScheduler {

    /** Executa a tarefa depois de {@code delayMillis}; depois do {@link #close()} a tarefa é descartada. */
    void schedule(long delayMillis, Runnable task);

    void close();

    /** Agendador de uma thread, daemon, sobre um {@link ScheduledThreadPoolExecutor}. */
    static RetryScheduler threaded() {
        final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "inbox-retry");
                thread.setDaemon(true);
                return thread;
            }
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return new RetryScheduler() {
            @Override
            public void schedule(long delayMillis, Runnable task) {
                try {
                    executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    // agendador encerrado: a mensagem permanece sem ack e o broker a reentrega
                }
            }

            @Override
            public void close() {
                executor.shutdownNow();
            }
        };
    }
}
