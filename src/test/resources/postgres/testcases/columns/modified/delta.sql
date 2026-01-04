-- Delta: Column modifications for PostgreSQL
-- Tests detection of missing and extra columns

-- Drop a column
ALTER TABLE USERS DROP COLUMN IF EXISTS EMAIL;

-- Add an extra column
ALTER TABLE USERS ADD COLUMN EXTRA_FIELD VARCHAR(100);

