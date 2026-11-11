CREATE TABLE store (
  endpoint_id text NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
  key text NOT NULL, value text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (endpoint_id, key));
CREATE INDEX store_updated_at ON store(updated_at);
