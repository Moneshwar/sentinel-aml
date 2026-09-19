ALTER TABLE alerts ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE cases ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Database enforcement applies to ordinary application UPDATE/DELETE/TRUNCATE,
-- including accidental repository mutations. Database owners must separately
-- restrict privileged maintenance access, which can override any trigger.
CREATE FUNCTION sentinel_reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update_delete
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION sentinel_reject_audit_mutation();

CREATE TRIGGER audit_log_no_truncate
BEFORE TRUNCATE ON audit_log
FOR EACH STATEMENT EXECUTE FUNCTION sentinel_reject_audit_mutation();
