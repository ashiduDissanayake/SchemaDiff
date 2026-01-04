-- Delta: Remove columns from the USERS table
-- Tests detection of missing columns

ALTER TABLE USERS DROP COLUMN STATUS;
ALTER TABLE USERS DROP COLUMN TENANT_ID;

