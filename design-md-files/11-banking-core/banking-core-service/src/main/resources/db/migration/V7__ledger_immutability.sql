-- Application must INSERT-only on journal_entries. Documented for production role hardening.
COMMENT ON TABLE ledger.journal_entries IS 'Immutable ledger legs: INSERT only, no UPDATE/DELETE';
