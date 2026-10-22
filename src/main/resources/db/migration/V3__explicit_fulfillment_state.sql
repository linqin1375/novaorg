ALTER TABLE simulated_orders ADD COLUMN fulfillment_status VARCHAR(24) NOT NULL DEFAULT 'UNSHIPPED';
UPDATE simulated_orders SET fulfillment_status='DELIVERED' WHERE order_id='A103';
