-- Detection runs in the ingest transaction, serialized by a customer row lock.
CREATE TABLE sanctioned_counterparties (
    identifier VARCHAR(255) PRIMARY KEY,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX idx_alerts_unique_dedup ON alerts(dedup_key);
CREATE INDEX IF NOT EXISTS idx_transactions_account_time ON transactions(account_id,transaction_time);
ALTER TABLE accounts ADD COLUMN risk_rating VARCHAR(20) NOT NULL DEFAULT 'LOW';
-- Correct the original prototype's currency semantics. These thresholds are USD values;
-- the engine converts them using the effective configurable USD -> base currency rate.
UPDATE rule_config SET configuration='{"threshold_amount":10000,"currency":"USD","score":20}' WHERE rule_code='CTR';
UPDATE rule_config SET configuration='{"threshold_lower":9000,"threshold_upper":9999,"currency":"USD","window_hours":24,"min_transactions":3,"score":30}' WHERE rule_code='STRUCTURING';
UPDATE rule_config SET configuration='{"round_interval":1000,"currency":"USD","window_hours":24,"min_transactions":3,"score":10}' WHERE rule_code='ROUND_NUMBER';
UPDATE rule_config SET configuration='{"multiplier":3,"rolling_days":90,"score":20}' WHERE rule_code='BEHAVIORAL_DEVIATION';
-- Existing unnormalized records must be re-imported after configuring FX before they
-- can form valid detection evidence. New ingestion always rejects missing FX.
