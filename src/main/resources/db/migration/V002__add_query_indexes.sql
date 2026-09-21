CREATE INDEX idx_transactions_account_id ON transactions (account_id);

CREATE INDEX idx_transactions_status ON transactions (status);

CREATE INDEX idx_transactions_type ON transactions (type);

CREATE INDEX idx_transactions_account_created_at ON transactions (account_id, created_at DESC);