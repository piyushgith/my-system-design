CREATE OR REPLACE FUNCTION ledger.check_double_entry()
RETURNS TRIGGER AS $$
DECLARE
    debit_sum BIGINT;
    credit_sum BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_paise) FILTER (WHERE entry_type = 'D'), 0),
           COALESCE(SUM(amount_paise) FILTER (WHERE entry_type = 'C'), 0)
    INTO debit_sum, credit_sum
    FROM ledger.journal_entries
    WHERE txn_id = NEW.txn_id;

    IF debit_sum <> credit_sum THEN
        RAISE EXCEPTION 'Double-entry imbalance for txn_id %: debits=% credits=%', NEW.txn_id, debit_sum, credit_sum;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_double_entry
    AFTER INSERT ON ledger.journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger.check_double_entry();
