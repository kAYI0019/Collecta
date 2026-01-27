-- =========================================
-- Ingest progress tracking
-- =========================================

ALTER TABLE ingest_jobs
  ADD COLUMN IF NOT EXISTS stage TEXT,
  ADD COLUMN IF NOT EXISTS total_units INTEGER,
  ADD COLUMN IF NOT EXISTS processed_units INTEGER;
