-- Extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

-- =========================================
-- 1) Core tables
-- =========================================

CREATE TABLE IF NOT EXISTS resources (
  id          BIGSERIAL PRIMARY KEY,
  type        TEXT NOT NULL CHECK (type IN ('link', 'document')),
  title       TEXT NOT NULL,
  memo        TEXT,
  status      TEXT NOT NULL DEFAULT 'todo' CHECK (status IN ('todo', 'in_progress', 'done')),
  is_pinned   BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS links (
  resource_id BIGINT PRIMARY KEY REFERENCES resources(id) ON DELETE CASCADE,
  url         TEXT NOT NULL,
  domain      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS documents (
  resource_id BIGINT PRIMARY KEY REFERENCES resources(id) ON DELETE CASCADE,
  file_path   TEXT NOT NULL,
  mime_type   TEXT NOT NULL,
  file_size   BIGINT NOT NULL,
  sha256      TEXT
);

CREATE TABLE IF NOT EXISTS tags (
  id    BIGSERIAL PRIMARY KEY,
  name  TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS resource_tags (
  resource_id BIGINT NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
  tag_id      BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (resource_id, tag_id)
);

-- =========================================
-- 2) Search units (chunks)
-- =========================================
-- NOTE: embedding dimension(384)은 임시 기본값.
-- 실제 임베딩 모델 차원에 맞춰 이후 마이그레이션으로 변경 가능.
CREATE TABLE IF NOT EXISTS chunks (
  id          BIGSERIAL PRIMARY KEY,
  resource_id BIGINT NOT NULL REFERENCES resources(id) ON DELETE CASCADE,

  chunk_text  TEXT NOT NULL,

  source_kind TEXT NOT NULL CHECK (source_kind IN ('link_meta', 'document_text')),
  page_index  INT,
  position    INT NOT NULL DEFAULT 0,

  fts         TSVECTOR,
  embedding   VECTOR(384),

  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =========================================
-- 3) Indexes
-- =========================================

-- resource filters
CREATE INDEX IF NOT EXISTS idx_resources_type ON resources(type);
CREATE INDEX IF NOT EXISTS idx_resources_status ON resources(status);
CREATE INDEX IF NOT EXISTS idx_resources_created_at ON resources(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_resources_pinned_created ON resources(is_pinned DESC, created_at DESC);

-- links domain filter
CREATE INDEX IF NOT EXISTS idx_links_domain ON links(domain);

-- tags filtering performance
CREATE INDEX IF NOT EXISTS idx_resource_tags_tag_id ON resource_tags(tag_id);
CREATE INDEX IF NOT EXISTS idx_resource_tags_resource_id ON resource_tags(resource_id);

-- chunks: FTS
CREATE OR REPLACE FUNCTION chunks_fts_trigger() RETURNS trigger AS $$
BEGIN
  NEW.fts := to_tsvector('simple', COALESCE(NEW.chunk_text, ''));
  RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_chunks_fts ON chunks;

CREATE TRIGGER trg_chunks_fts
BEFORE INSERT OR UPDATE OF chunk_text
ON chunks
FOR EACH ROW EXECUTE FUNCTION chunks_fts_trigger();

CREATE INDEX IF NOT EXISTS idx_chunks_fts_gin ON chunks USING GIN (fts);

-- chunks: vector index (HNSW)
-- 데이터가 적어도 생성해둬도 되고, 나중에 생성해도 됨.
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
ON chunks USING hnsw (embedding vector_cosine_ops);

-- useful join index
CREATE INDEX IF NOT EXISTS idx_chunks_resource_id ON chunks(resource_id);
