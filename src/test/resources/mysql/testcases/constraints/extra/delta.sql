-- Delta: Add extra constraints to tables
-- Tests detection of unauthorized constraint additions

ALTER TABLE USERS ADD CONSTRAINT UK_USERS_TENANT UNIQUE (TENANT_ID);
ALTER TABLE AUDIT_LOG ADD CONSTRAINT CHK_AUDIT_ACTION CHECK (ACTION IS NOT NULL);

