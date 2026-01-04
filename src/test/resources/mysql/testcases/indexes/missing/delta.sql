-- Delta: Drop indexes from tables
-- Tests detection of missing indexes

ALTER TABLE AUDIT_LOG DROP INDEX IDX_AUDIT_USER;
ALTER TABLE AUDIT_LOG DROP INDEX IDX_AUDIT_TIMESTAMP;

