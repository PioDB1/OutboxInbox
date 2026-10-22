-- Esquema SQL Server da inbox.
-- Idempotente: a tabela só é criada quando ainda não existe. Os lotes são separados por linhas "GO", que o
-- InboxSchemaInstaller interpreta; o driver JDBC não conhece esse separador.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

-- Um message_id por mensagem consumida; a chave primária é o dedup.
IF OBJECT_ID(N'inbox', N'U') IS NULL
BEGIN
    CREATE TABLE inbox (
        message_id  UNIQUEIDENTIFIER NOT NULL,
        consumed_at DATETIME2        NOT NULL CONSTRAINT DF_inbox_consumed_at DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_inbox PRIMARY KEY (message_id)
    );
END;
GO
