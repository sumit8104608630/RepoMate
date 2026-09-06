CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS repositories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    github_repo_id BIGINT NOT NULL,
    owner VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    full_name VARCHAR(300) NOT NULL,
    is_private BOOLEAN NOT NULL DEFAULT FALSE,
    default_branch VARCHAR(100) NOT NULL,
    language VARCHAR(100),
    html_url VARCHAR(500),
    description TEXT,
    index_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    indexed_at TIMESTAMP WITH TIME ZONE,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    files_total INTEGER NOT NULL DEFAULT 0,
    files_processed INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, github_repo_id)
);

CREATE INDEX IF NOT EXISTS idx_repositories_user_id ON repositories(user_id);
CREATE INDEX IF NOT EXISTS idx_repositories_github_repo_id ON repositories(github_repo_id);
CREATE INDEX IF NOT EXISTS idx_repositories_full_name ON repositories(full_name);
CREATE INDEX IF NOT EXISTS idx_repositories_index_status ON repositories(index_status);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'vector_store'
    ) THEN
        ALTER TABLE IF EXISTS vector_store
            ALTER COLUMN embedding TYPE vector(2048)
            USING embedding::vector(2048);
    END IF;
END $$;
