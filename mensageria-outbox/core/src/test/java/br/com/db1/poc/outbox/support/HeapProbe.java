package br.com.db1.poc.outbox.support;

/** Medição do heap usado depois de coletas completas, para comparar o consumo em pontos distintos do processamento. */
public final class HeapProbe {

    public static final long MEBIBYTE = 1024L * 1024L;

    private HeapProbe() {
    }

    /** Bytes de heap em uso depois de {@code System.gc()} (executado duas vezes, para as referências fracas). */
    public static long usedAfterGc() {
        System.gc();
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
