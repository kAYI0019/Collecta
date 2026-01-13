-- Extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;

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
-- 2) Search units (chunks) - Elasticsearch로 이동
-- =========================================
-- NOTE: 검색 데이터는 Elasticsearch에 저장됩니다.
-- PostgreSQL은 메타데이터만 관리합니다.

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
