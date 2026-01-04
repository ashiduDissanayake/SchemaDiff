-- Delta: Add extra indexes to tables
-- Tests detection of unauthorized index additions

CREATE INDEX IDX_USERS_EMAIL ON USERS(EMAIL);
CREATE INDEX IDX_USERS_STATUS ON USERS(STATUS);
CREATE INDEX IDX_TOKENS_USER ON TOKENS(USER_ID);

