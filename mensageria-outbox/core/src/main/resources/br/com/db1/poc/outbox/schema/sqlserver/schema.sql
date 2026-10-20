-- Esquema SQL Server da outbox, do lease e do ponto de leitura.
-- Idempotente: cada objeto só é criado quando ainda não existe. Os lotes são separados por linhas "GO",
-- que o OutboxSchemaInstaller interpreta; o driver JDBC não conhece esse separador.

SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO

IF OBJECT_ID(N'outbox', N'U') IS NULL
BEGIN
    CREATE TABLE outbox (
        seq             BIGINT IDENTITY(1,1) NOT NULL,
        message_id      UNIQUEIDENTIFIER     NOT NULL,
        correlation_id  UNIQUEIDENTIFIER     NOT NULL,
        idempotency_key NVARCHAR(200)        NOT NULL,
        aggregate_key   NVARCHAR(100)        NOT NULL,
        type            NVARCHAR(100)        NOT NULL,
        schema_ver      SMALLINT             NOT NULL,
        occurred_at     DATETIME2(6)         NOT NULL,
        trace_id        NVARCHAR(64)         NOT NULL,
        payload         VARBINARY(MAX)       NOT NULL,
        created_at      DATETIME2(6)         NOT NULL CONSTRAINT DF_outbox_created_at DEFAULT SYSUTCDATETIME(),
        rv              ROWVERSION           NOT NULL,
        sent_at         DATETIME2(6)         NULL,
        CONSTRAINT PK_outbox PRIMARY KEY CLUSTERED (seq)
    );
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_outbox_message_id' AND object_id = OBJECT_ID(N'outbox'))
    CREATE UNIQUE NONCLUSTERED INDEX UX_outbox_message_id ON outbox (message_id);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_outbox_rv' AND object_id = OBJECT_ID(N'outbox'))
    CREATE UNIQUE NONCLUSTERED INDEX UX_outbox_rv ON outbox (rv);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_outbox_aggregate' AND object_id = OBJECT_ID(N'outbox'))
    CREATE NONCLUSTERED INDEX IX_outbox_aggregate ON outbox (aggregate_key, seq);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_outbox_pendente' AND object_id = OBJECT_ID(N'outbox'))
    CREATE NONCLUSTERED INDEX IX_outbox_pendente ON outbox (seq) WHERE sent_at IS NULL;
GO

IF OBJECT_ID(N'outbox_lease', N'U') IS NULL
BEGIN
    CREATE TABLE outbox_lease (
        lease_name VARCHAR(50)   NOT NULL,
        holder_id  NVARCHAR(100) NOT NULL,
        expires_at DATETIME2(6)  NOT NULL,
        CONSTRAINT PK_outbox_lease PRIMARY KEY (lease_name)
    );
END;
GO

IF OBJECT_ID(N'outbox_read_point', N'U') IS NULL
BEGIN
    CREATE TABLE outbox_read_point (
        relay_name VARCHAR(50)  NOT NULL,
        last_rv    BINARY(8)    NOT NULL,
        updated_at DATETIME2(6) NOT NULL CONSTRAINT DF_outbox_read_point_updated_at DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_outbox_read_point PRIMARY KEY (relay_name)
    );
END;
GO
