ALTER TABLE simulated_orders ADD COLUMN promised_hours INT NOT NULL DEFAULT 48;
ALTER TABLE simulated_orders ADD COLUMN actual_hours INT NOT NULL DEFAULT 96;
ALTER TABLE simulated_orders ADD COLUMN customer_id VARCHAR(80) NOT NULL DEFAULT 'C-8837';
ALTER TABLE simulated_orders ADD COLUMN claims_30d INT NOT NULL DEFAULT 1;
ALTER TABLE simulated_orders ADD COLUMN category VARCHAR(80) NOT NULL DEFAULT '数码';
INSERT INTO simulated_orders VALUES ('A101',25900,0,24,100,'C-8838',0,'家居');
INSERT INTO simulated_orders VALUES ('A102',49900,0,12,72,'C-8839',4,'生鲜');
INSERT INTO simulated_orders VALUES ('A103',8900,0,48,12,'C-8840',0,'图书');
INSERT INTO simulated_orders VALUES ('A104',200000,0,24,150,'C-8841',2,'家电');
