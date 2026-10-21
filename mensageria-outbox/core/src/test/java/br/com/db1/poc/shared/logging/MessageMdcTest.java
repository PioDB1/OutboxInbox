package br.com.db1.poc.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import br.com.db1.poc.shared.support.LogCapture;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class MessageMdcTest {

    private static final Logger LOG = LoggerFactory.getLogger(MessageMdcTest.class);

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    /** TC-5: dentro do bloco as quatro chaves estão no MDC, e depois dele nenhuma. */
    @Test
    void colocaAsQuatroChavesERemoveAoFechar() {
        try (MessageMdc mdc = MessageMdc.open("m", "c", "a", "t")) {
            assertEquals("m", MDC.get("messageId"));
            assertEquals("c", MDC.get("correlationId"));
            assertEquals("a", MDC.get("aggregateKey"));
            assertEquals("t", MDC.get("traceId"));
        }

        assertNull(MDC.get("messageId"));
        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("aggregateKey"));
        assertNull(MDC.get("traceId"));
    }

    /** TC-5: valor nulo não coloca a chave; as demais são colocadas e removidas. */
    @Test
    void valorNuloNaoColocaAChave() {
        try (MessageMdc mdc = MessageMdc.open(null, "c", "a", null)) {
            assertNull(MDC.get("messageId"));
            assertEquals("c", MDC.get("correlationId"));
            assertEquals("a", MDC.get("aggregateKey"));
            assertNull(MDC.get("traceId"));
        }

        assertNull(MDC.get("correlationId"));
        assertNull(MDC.get("aggregateKey"));
    }

    /** TC-5: uma chave já presente é sobrescrita enquanto o bloco está aberto e removida ao fechar. */
    @Test
    void chavePreExistenteESobrescritaERemovidaAoFechar() {
        MDC.put("messageId", "anterior");
        MDC.put("outra", "fica");

        try (MessageMdc mdc = MessageMdc.open("m", "c", "a", "t")) {
            assertEquals("m", MDC.get("messageId"));
        }

        assertNull(MDC.get("messageId"));
        assertEquals("fica", MDC.get("outra"));
    }

    /** TC-5: os eventos de log escritos dentro do bloco carregam as chaves no MDC do evento. */
    @Test
    void logsDentroDoBlocoCarregamAsChaves() {
        try (LogCapture capture = new LogCapture(MessageMdcTest.class)) {
            try (MessageMdc mdc = MessageMdc.open("m", "c", "a", "t")) {
                LOG.info("dentro");
            }
            LOG.info("fora");

            ILoggingEvent inside = capture.events().get(0);
            ILoggingEvent outside = capture.events().get(1);
            Map<String, String> mdcInside = inside.getMDCPropertyMap();
            assertEquals("m", mdcInside.get("messageId"));
            assertEquals("c", mdcInside.get("correlationId"));
            assertEquals("a", mdcInside.get("aggregateKey"));
            assertEquals("t", mdcInside.get("traceId"));
            assertNull(outside.getMDCPropertyMap().get("messageId"));
        }
    }
}
