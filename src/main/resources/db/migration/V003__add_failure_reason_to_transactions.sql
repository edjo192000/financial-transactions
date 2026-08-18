ALTER TABLE transactions
    ADD COLUMN failure_reason TEXT;

COMMENT ON COLUMN transactions.failure_reason IS 'Reason the transaction failed; null unless status = FAILED';
