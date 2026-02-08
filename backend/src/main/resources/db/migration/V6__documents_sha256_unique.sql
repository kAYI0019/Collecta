-- =========================================
-- Document dedupe hard constraint
-- =========================================

-- 기존 중복 해시가 있으면 가장 먼저 생성된 리소스만 남기고 나머지는 NULL로 정리
WITH ranked AS (
  SELECT resource_id,
         sha256,
         ROW_NUMBER() OVER (PARTITION BY sha256 ORDER BY resource_id) AS rn
  FROM documents
  WHERE sha256 IS NOT NULL
)
UPDATE documents d
SET sha256 = NULL
FROM ranked r
WHERE d.resource_id = r.resource_id
  AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_documents_sha256
  ON documents (sha256);

