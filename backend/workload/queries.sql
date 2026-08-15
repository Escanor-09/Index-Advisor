-- Sample workload for the Index Advisor.
-- Deliberately small (V1 does not need hundreds of queries) and deliberately
-- repeats a few columns (category_id, customer_id) so workload-level
-- frequency analysis (Milestone 8) has something real to aggregate.

SELECT * FROM products WHERE category_id = 25;

SELECT * FROM products WHERE category_id = 100;

SELECT * FROM products WHERE status = 'active';

SELECT * FROM products WHERE category_id = 25 AND status = 'active';

SELECT * FROM orders WHERE customer_id = 500;

SELECT * FROM orders WHERE status = 'delivered';

SELECT * FROM order_items WHERE product_id = 100;

SELECT * FROM payments WHERE order_id = 200;

SELECT * FROM shipping_addresses WHERE customer_id = 500;

SELECT * FROM customers WHERE status = 'active';
