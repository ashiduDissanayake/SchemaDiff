-- Delta: Remove the USERS table from target
-- This simulates a schema drift where a critical table is missing
DROP TABLE IF EXISTS USER_ROLES;
DROP TABLE IF EXISTS USER_ATTRIBUTES;
DROP TABLE IF EXISTS TOKENS;
DROP TABLE IF EXISTS USERS;

