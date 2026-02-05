-- =========================================
-- Allow cancelled ingest status
-- =========================================

-- 기존 체크 제약 조건(ingest_jobs_status_check)은 컬럼 레벨 CHECK로 생성되어
-- Postgres 기본 네이밍 규칙에 따라 ingest_jobs_status_check 이름을 가집니다.
ALTER TABLE ingest_jobs
  DROP CONSTRAINT IF EXISTS ingest_jobs_status_check;

ALTER TABLE ingest_jobs
  ADD CONSTRAINT ingest_jobs_status_check
  CHECK (status IN ('queued', 'processing', 'done', 'failed', 'cancelled'));

