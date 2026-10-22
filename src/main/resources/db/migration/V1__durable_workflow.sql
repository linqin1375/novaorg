CREATE TABLE task_runs (task_id VARCHAR(100) PRIMARY KEY, payload LONGTEXT NOT NULL);
CREATE TABLE audit_events (event_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(100), payload LONGTEXT NOT NULL);
CREATE INDEX idx_audit_task ON audit_events(task_id);
CREATE TABLE simulated_orders (order_id VARCHAR(80) PRIMARY KEY, paid_fen BIGINT NOT NULL, refunded_fen BIGINT NOT NULL);
INSERT INTO simulated_orders VALUES ('A100',12900,0);
CREATE TABLE refund_receipts (business_key VARCHAR(200) PRIMARY KEY, order_id VARCHAR(80) NOT NULL, amount_fen BIGINT NOT NULL, created_at VARCHAR(40) NOT NULL);
CREATE TABLE users (username VARCHAR(50) PRIMARY KEY, password VARCHAR(100) NOT NULL, enabled BOOLEAN NOT NULL);
CREATE TABLE authorities (username VARCHAR(50) NOT NULL, authority VARCHAR(50) NOT NULL, PRIMARY KEY(username,authority), FOREIGN KEY(username) REFERENCES users(username));
