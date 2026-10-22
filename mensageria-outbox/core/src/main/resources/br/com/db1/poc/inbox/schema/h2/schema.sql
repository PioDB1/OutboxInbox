-- Esquema H2 da inbox.
-- Idempotente: a tabela só é criada quando ainda não existe. Os lotes são separados por linhas "GO", que o
-- InboxSchemaInstaller interpreta.

-- Um message_id por mensagem consumida; a chave primária é o dedup. O H2InboxDialect informa consumed_at em UTC;
-- o padrão da coluna grava o horário da zona da JVM e só vale para uma inserção fora do dialeto.
CREATE TABLE IF NOT EXISTS inbox (
    message_id  UUID      NOT NULL,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_inbox PRIMARY KEY (message_id)
);
GO
