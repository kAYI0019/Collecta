-- =========================================
-- Ingest status tracking
-- =========================================

CREATE TABLE IF NOT EXISTS ingest_jobs (
  resource_id   BIGINT PRIMARY KEY REFERENCES resources(id) ON DELETE CASCADE,
  resource_type TEXT NOT NULL CHECK (resource_type IN ('link', 'document')),
  title         TEXT,
  status        TEXT NOT NULL CHECK (status IN ('queued', 'processing', 'done', 'failed')),
  error_message TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ingest_jobs_status ON ingest_jobs(status);
CREATE INDEX IF NOT EXISTS idx_ingest_jobs_updated_at ON ingest_jobs(updated_at DESC);
