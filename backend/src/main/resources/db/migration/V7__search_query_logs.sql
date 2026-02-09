-- =========================================
-- Search query logs
-- =========================================

CREATE TABLE IF NOT EXISTS search_query_logs (
  id             BIGSERIAL PRIMARY KEY,
  query_text     TEXT,
  mode           TEXT NOT NULL,
  resource_type  TEXT,
  domain         TEXT,
  status         TEXT,
  is_pinned      BOOLEAN,
  tags           TEXT,
  sort           TEXT,
  page           INTEGER NOT NULL,
  page_size      INTEGER NOT NULL,
  total_results  BIGINT NOT NULL,
  latency_ms     BIGINT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_search_query_logs_created_at
  ON search_query_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_search_query_logs_mode
  ON search_query_logs (mode);

