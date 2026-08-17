CREATE TABLE transactions (
                              id                       UUID PRIMARY KEY,
                              idempotency_key          VARCHAR(255) NOT NULL,
                              account_id               VARCHAR(255) NOT NULL,
                              type                     VARCHAR(20)  NOT NULL,
                              amount                   NUMERIC(19, 2) NOT NULL,
                              currency                 VARCHAR(10)  NOT NULL,
                              description              TEXT,
                              status                   VARCHAR(20)  NOT NULL,
                              provider_transaction_id  VARCHAR(255),
                              balance_after            NUMERIC(19, 2),
                              created_at               TIMESTAMPTZ  NOT NULL,

                              CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key),
                              CONSTRAINT chk_transactions_type CHECK (type IN ('CREDIT', 'DEBIT')),
                              CONSTRAINT chk_transactions_status CHECK (status IN ('EXECUTED', 'REJECTED', 'FAILED')),
                              CONSTRAINT chk_transactions_currency CHECK (currency = 'MXN')
);

COMMENT ON TABLE transactions IS 'Financial transactions executed against the external provider';
COMMENT ON COLUMN transactions.idempotency_key IS 'Client-supplied key to prevent duplicate execution on retry';
COMMENT ON COLUMN transactions.provider_transaction_id IS 'Provider-side transaction id; null when status = FAILED';
COMMENT ON COLUMN transactions.balance_after IS 'Account balance reported by the provider after execution; null unless status = EXECUTED';