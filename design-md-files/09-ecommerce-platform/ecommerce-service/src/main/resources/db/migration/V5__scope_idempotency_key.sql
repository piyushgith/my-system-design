-- Idempotency keys are client-generated and only meaningful per buyer.
-- Replace the global unique constraint with a composite (buyer_id, idempotency_key)
-- so two different buyers can independently use the same key.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_idempotency_key_key;

ALTER TABLE orders
    ADD CONSTRAINT uq_orders_buyer_idempotency UNIQUE (buyer_id, idempotency_key);
