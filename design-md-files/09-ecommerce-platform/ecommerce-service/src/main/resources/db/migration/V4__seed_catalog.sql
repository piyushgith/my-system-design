-- Demo seed data so the MVP is browsable on first boot.
-- Admin user is NOT seeded (no fake password hash): register a user, then run
--   UPDATE users SET role = 'ADMIN' WHERE email = '<you>';
-- to grant admin access.

INSERT INTO categories (id, name, slug) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Electronics', 'electronics'),
    ('22222222-2222-2222-2222-222222222222', 'Books',       'books'),
    ('33333333-3333-3333-3333-333333333333', 'Home',        'home');

INSERT INTO products (category_id, title, description, price_amount, currency, stock_quantity, image_url) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Wireless Headphones', 'Over-ear Bluetooth headphones with noise cancellation', 499900, 'INR', 50, NULL),
    ('11111111-1111-1111-1111-111111111111', 'USB-C Charger 65W',    'Fast GaN charger for laptops and phones',              199900, 'INR', 120, NULL),
    ('22222222-2222-2222-2222-222222222222', 'Designing Data-Intensive Applications', 'The big ideas behind reliable, scalable systems', 79900, 'INR', 30, NULL),
    ('22222222-2222-2222-2222-222222222222', 'Clean Code',           'A handbook of agile software craftsmanship',           59900, 'INR', 40, NULL),
    ('33333333-3333-3333-3333-333333333333', 'Ceramic Coffee Mug',   'Dishwasher-safe 350ml mug',                            29900, 'INR', 200, NULL);
