package br.com.db1.poc.inbox;

import java.util.PriorityQueue;

/**
 * {@link RetryScheduler} com relógio simulado: as tarefas só executam quando o teste avança o tempo, na thread do
 * teste, e cada uma vê o relógio no seu instante de vencimento.
 */
final class ManualScheduler implements RetryScheduler {

    private static final class Scheduled implements Comparable<Scheduled> {

        final long due;
        final long sequence;
        final Runnable task;

        Scheduled(long due, long sequence, Runnable task) {
            this.due = due;
            this.sequence = sequence;
            this.task = task;
        }

        @Override
        public int compareTo(Scheduled other) {
            int byDue = Long.compare(due, other.due);
            return byDue != 0 ? byDue : Long.compare(sequence, other.sequence);
        }
    }

    private final PriorityQueue<Scheduled> queue = new PriorityQueue<>();
    private long now;
    private long sequence;
    private boolean closed;

    @Override
    public synchronized void schedule(long delayMillis, Runnable task) {
        if (!closed) {
            queue.add(new Scheduled(now + delayMillis, sequence++, task));
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        queue.clear();
    }

    /** Relógio simulado, em milissegundos desde o início do teste. */
    synchronized long now() {
        return now;
    }

    /** Tarefas agendadas e ainda não executadas. */
    synchronized int pending() {
        return queue.size();
    }

    /** Intervalo, a partir de agora, até a próxima tarefa; {@code -1} se não há tarefa. */
    synchronized long nextDelay() {
        Scheduled next = queue.peek();
        return next == null ? -1 : next.due - now;
    }

    /** Avança o relógio até a próxima tarefa e a executa; {@code false} se não há tarefa. */
    boolean runNext() {
        Scheduled next;
        synchronized (this) {
            next = queue.poll();
            if (next == null) {
                return false;
            }
            now = next.due;
        }
        next.task.run();
        return true;
    }
}
