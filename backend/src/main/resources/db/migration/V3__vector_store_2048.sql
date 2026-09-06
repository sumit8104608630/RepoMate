DROP TABLE IF EXISTS public.vector_store CASCADE;

CREATE TABLE public.vector_store (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(1536) NOT NULL
);

CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON public.vector_store
    USING hnsw (embedding vector_cosine_ops);
