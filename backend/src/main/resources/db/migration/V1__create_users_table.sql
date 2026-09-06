CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    github_id BIGINT NOT NULL UNIQUE,
    github_username VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(2048) NOT NULL,
    access_token TEXT NOT NULL,
    token_scopes VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_github_username ON users(github_username);
CREATE INDEX IF NOT EXISTS idx_users_created_at ON users(created_at);
