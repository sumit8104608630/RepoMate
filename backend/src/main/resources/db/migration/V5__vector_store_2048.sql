DROP INDEX IF EXISTS public.vector_store_embedding_idx;

TRUNCATE TABLE public.vector_store;

ALTER TABLE public.vector_store
    ALTER COLUMN embedding TYPE vector(2048);

CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON public.vector_store
    USING hnsw (embedding vector_cosine_ops);
