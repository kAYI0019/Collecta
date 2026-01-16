-- =========================================
-- Outbox for resource delete events
-- =========================================

CREATE TABLE IF NOT EXISTS outbox_events (
  id             BIGSERIAL PRIMARY KEY,
  aggregate_type TEXT NOT NULL,
  aggregate_id   BIGINT NOT NULL,
  event_type     TEXT NOT NULL,
  payload        JSONB NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  published_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_events_unpublished
  ON outbox_events (id)
  WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION enqueue_resource_deleted()
RETURNS trigger AS $$
BEGIN
  INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
  VALUES (
    'resource',
    OLD.id,
    'RESOURCE_DELETED',
    jsonb_build_object('resource_id', OLD.id, 'resource_type', OLD.type)
  );
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_resources_outbox_delete ON resources;
CREATE TRIGGER trg_resources_outbox_delete
AFTER DELETE ON resources
FOR EACH ROW EXECUTE FUNCTION enqueue_resource_deleted();
