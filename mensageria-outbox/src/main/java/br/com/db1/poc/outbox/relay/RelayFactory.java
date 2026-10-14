package br.com.db1.poc.outbox.relay;

import br.com.db1.poc.outbox.dialect.OutboxDialect;
import br.com.db1.poc.outbox.dialect.sqlserver.SqlServerOutboxDialect;
import br.com.db1.poc.outbox.publish.MessagePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;

/** Cria o relay da estratégia configurada em {@code outbox.relay.strategy}. */
public final class RelayFactory {

    private RelayFactory() {
    }

    /**
     * @param meterRegistry registro em que o relay publica {@code outbox.lag.seconds}, {@code outbox.pending} e
     *                      {@code outbox.publish.retries}
     * @param holderId identificador desta instância no lease; deve ser distinto entre as instâncias do banco
     */
    public static Relay create(RelayConfig config, DataSource dataSource, MessagePublisher publisher,
            MeterRegistry meterRegistry, String holderId) {
        OutboxDialect dialect = new SqlServerOutboxDialect();
        return switch (config.strategy()) {
            case CLAIM -> new ClaimRelay(config, dataSource, dialect, publisher, meterRegistry, holderId);
            case READ_POINT -> new ReadPointRelay(config, dataSource, dialect, publisher, meterRegistry, holderId);
        };
    }
}
