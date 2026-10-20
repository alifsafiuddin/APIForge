-- Baseline migration.
-- citext provides case-insensitive text, used for unique e-mail addresses (see docs/DATABASE_SCHEMA.md).
-- It is a trusted extension (PostgreSQL 13+), so the database owner can create it without superuser rights.
CREATE EXTENSION IF NOT EXISTS citext;
