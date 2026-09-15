-- SRM Credit Engine - schema inicial.
--
-- Decisoes registradas no SPEC.md e refletidas aqui:
--  * valores monetarios em NUMERIC(19,2) e taxas em NUMERIC(19,6): nunca float/real;
--  * receivables com coluna de versao para optimistic locking;
--  * settlements imutavel e unico por recebivel, com a chave de idempotencia no banco
--    (unicidade em memoria nao sobrevive a duas instancias da aplicacao);
--  * a cotacao efetivamente aplicada e copiada para a liquidacao, nao referenciada,
--    para que o registro continue reproduzivel mesmo se a tabela de taxas mudar.

CREATE TABLE assignors (
    id          UUID         PRIMARY KEY,
    document    VARCHAR(14)  NOT NULL,
    legal_name  VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_assignors_document UNIQUE (document)
);

COMMENT ON COLUMN assignors.document IS 'CNPJ/CPF apenas digitos';

CREATE TABLE receivables (
    id              UUID           PRIMARY KEY,
    assignor_id     UUID           NOT NULL REFERENCES assignors (id),
    receivable_type VARCHAR(30)    NOT NULL,
    face_value      NUMERIC(19, 2) NOT NULL,
    face_currency   CHAR(3)        NOT NULL,
    due_date        DATE           NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_receivables_face_value_positive CHECK (face_value > 0),
    CONSTRAINT ck_receivables_status CHECK (status IN ('PENDING', 'SETTLED', 'CANCELLED')),
    CONSTRAINT ck_receivables_type CHECK (receivable_type IN ('DUPLICATA_MERCANTIL', 'CHEQUE_PRE_DATADO')),
    CONSTRAINT ck_receivables_face_currency CHECK (face_currency IN ('BRL', 'USD'))
);

CREATE INDEX ix_receivables_assignor ON receivables (assignor_id);
CREATE INDEX ix_receivables_status_due_date ON receivables (status, due_date);

CREATE TABLE fx_rates (
    id             UUID           PRIMARY KEY,
    base_currency  CHAR(3)        NOT NULL,
    quote_currency CHAR(3)        NOT NULL,
    rate           NUMERIC(19, 6) NOT NULL,
    effective_at   TIMESTAMPTZ    NOT NULL,
    source         VARCHAR(40)    NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_fx_rates_rate_positive CHECK (rate > 0),
    CONSTRAINT ck_fx_rates_distinct_currencies CHECK (base_currency <> quote_currency),
    CONSTRAINT uk_fx_rates_pair_effective_at UNIQUE (base_currency, quote_currency, effective_at)
);

-- Consulta dominante: "cotacao vigente mais recente do par nesta data/hora".
CREATE INDEX ix_fx_rates_pair_effective_at_desc
    ON fx_rates (base_currency, quote_currency, effective_at DESC);

CREATE TABLE settlements (
    id                    UUID           PRIMARY KEY,
    receivable_id         UUID           NOT NULL REFERENCES receivables (id),
    assignor_id           UUID           NOT NULL REFERENCES assignors (id),
    idempotency_key       VARCHAR(120)   NOT NULL,
    request_fingerprint   CHAR(64)       NOT NULL,
    term_months           INTEGER        NOT NULL,
    monthly_base_rate     NUMERIC(19, 6) NOT NULL,
    monthly_spread        NUMERIC(19, 6) NOT NULL,
    face_value            NUMERIC(19, 2) NOT NULL,
    face_currency         CHAR(3)        NOT NULL,
    present_value         NUMERIC(19, 2) NOT NULL,
    discount_amount       NUMERIC(19, 2) NOT NULL,
    settlement_amount     NUMERIC(19, 2) NOT NULL,
    settlement_currency   CHAR(3)        NOT NULL,
    fx_rate               NUMERIC(19, 6),
    fx_base_currency      CHAR(3),
    fx_quote_currency     CHAR(3),
    fx_rate_effective_at  TIMESTAMPTZ,
    fx_rate_source        VARCHAR(40),
    settled_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uk_settlements_receivable UNIQUE (receivable_id),
    CONSTRAINT uk_settlements_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_settlements_term_months CHECK (term_months >= 0),
    CONSTRAINT ck_settlements_amounts_positive CHECK (
        face_value > 0 AND present_value >= 0 AND settlement_amount >= 0
    ),
    -- Liquidacao cross-currency sem cotacao registrada e impossivel de auditar.
    CONSTRAINT ck_settlements_fx_required_when_cross_currency CHECK (
        (settlement_currency = face_currency AND fx_rate IS NULL)
            OR (settlement_currency <> face_currency
                AND fx_rate IS NOT NULL
                AND fx_rate > 0
                AND fx_base_currency IS NOT NULL
                AND fx_quote_currency IS NOT NULL
                AND fx_rate_effective_at IS NOT NULL
                AND fx_rate_source IS NOT NULL)
    )
);

-- Extrato analitico: filtro por periodo, cedente e moeda.
CREATE INDEX ix_settlements_settled_at ON settlements (settled_at DESC);
CREATE INDEX ix_settlements_assignor_settled_at ON settlements (assignor_id, settled_at DESC);
CREATE INDEX ix_settlements_currency_settled_at ON settlements (settlement_currency, settled_at DESC);

-- Imutabilidade garantida pelo banco, nao pela boa vontade da aplicacao:
-- alterar ou apagar uma liquidacao registrada nao e operacao do sistema.
-- Correcao se faz por lancamento compensatorio (estorno).
CREATE OR REPLACE FUNCTION settlements_reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'settlements e imutavel: % negado em % (registre um estorno compensatorio)',
        TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_settlements_immutable
    BEFORE UPDATE OR DELETE
    ON settlements
    FOR EACH ROW
EXECUTE FUNCTION settlements_reject_mutation();
