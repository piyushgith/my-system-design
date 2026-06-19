-- Enforce referential integrity between order line items and products at the DB level.
-- Products are archived (status change), never hard-deleted, so this FK is safe.
ALTER TABLE order_items
    ADD CONSTRAINT fk_order_items_product
    FOREIGN KEY (product_id) REFERENCES products(id);
