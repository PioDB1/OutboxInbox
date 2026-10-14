package br.com.db1.poc.outbox.rabbit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.db1.poc.outbox.publish.MessagePublisher;
import br.com.db1.poc.outbox.publish.PublishException;
import br.com.db1.poc.outbox.publish.PublishableMessage;
import br.com.db1.poc.outbox.support.BrokerIT;
import br.com.db1.poc.outbox.support.TestEnvelopes;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RabbitMessagePublisherIT extends BrokerIT {

    @Test
    void publicaNaFilaQuorumComHeadersPropertiesECorpo() throws PublishException {
        MessagePublisher publisher = rabbitPublisher();
        PublishableMessage message = PublishableMessage.from(TestEnvelopes.of("agg-1"));

        publisher.publish(message);

        List<Received> received = awaitMessages(1, Duration.ofSeconds(10));
        Received first = received.get(0);
        assertArrayEquals(message.payload(), first.body());
        assertEquals(message.messageId().toString(), first.header("messageId"));
        assertEquals(message.correlationId().toString(), first.header("correlationId"));
        assertEquals(message.traceId(), first.header("traceId"));
        assertEquals(message.aggregateKey(), first.header("aggregateKey"));
        assertEquals(message.messageId().toString(), first.properties().getMessageId());
        assertEquals(2, first.properties().getDeliveryMode());
    }

    @Test
    void mensagemSemFilaLigadaELevantaFalhaDeMensagem() {
        MessagePublisher publisher = rabbitPublisher();
        PublishableMessage message = PublishableMessage.from(TestEnvelopes.of("agg-1", "TipoSemFila"));

        PublishException failure = assertThrows(PublishException.class, () -> publisher.publish(message));

        assertEquals(PublishException.Kind.MESSAGE, failure.kind());
    }

    @Test
    void brokerIndisponivelLevantaFalhaDeConexaoEPublicaQuandoVolta() throws IOException, PublishException {
        MessagePublisher publisher = rabbitPublisher();
        broker.proxy().disable();

        PublishException failure = assertThrows(PublishException.class,
                () -> publisher.publish(PublishableMessage.from(TestEnvelopes.of("agg-1"))));
        assertEquals(PublishException.Kind.CONNECTION, failure.kind());

        broker.proxy().enable();
        publisher.publish(PublishableMessage.from(TestEnvelopes.of("agg-1")));
        assertEquals(1, awaitMessages(1, Duration.ofSeconds(10)).size());
    }
}
