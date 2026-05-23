-- Monotonically increasing sequence for human-readable loan account numbers.
-- Using a DB sequence guarantees uniqueness under concurrent disbursements
-- without the collision risk of Math.random() or application-level counters.
CREATE SEQUENCE loan_account_number_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 10;
