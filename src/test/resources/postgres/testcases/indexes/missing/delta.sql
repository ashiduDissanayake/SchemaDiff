-- Delta: Drop indexes from tables (PostgreSQL)
-- Tests detection of missing indexes

DROP INDEX IF EXISTS IDX_AUDIT_USER;
DROP INDEX IF EXISTS IDX_AUDIT_ACTION;

