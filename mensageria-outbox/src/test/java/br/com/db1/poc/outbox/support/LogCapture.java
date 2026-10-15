package br.com.db1.poc.outbox.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captura em memória os eventos de log de um logger, em nível {@code DEBUG}, até o {@link #close()}. */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(Class<?> source) {
        this.logger = (Logger) LoggerFactory.getLogger(source);
        this.previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(appender.list));
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
