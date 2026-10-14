package br.com.db1.poc.outbox.support;

/** Imagens Docker fixadas dos testes de integração. */
public final class Images {

    public static final String SQL_SERVER = "mcr.microsoft.com/mssql/server:2019-CU32-ubuntu-20.04";
    public static final String RABBITMQ = "rabbitmq:4.3.6-management";
    public static final String TOXIPROXY = "ghcr.io/shopify/toxiproxy:2.10.0";

    private Images() {
    }
}
