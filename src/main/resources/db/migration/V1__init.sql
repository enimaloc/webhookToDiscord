CREATE TABLE endpoints (
  id text PRIMARY KEY, name text NOT NULL, enabled boolean NOT NULL DEFAULT true,
  script text NOT NULL, auth jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE requests (
  id bigserial PRIMARY KEY,
  endpoint_id text NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
  received_at timestamptz NOT NULL DEFAULT now(),
  method text NOT NULL, headers jsonb NOT NULL, query text NOT NULL, body bytea NOT NULL,
  status text NOT NULL CHECK (status IN ('pending','processing','done','failed')));
CREATE INDEX requests_endpoint_status ON requests(endpoint_id, status, id);
CREATE INDEX requests_received_at ON requests(received_at);
CREATE TABLE executions (
  id bigserial PRIMARY KEY,
  request_id bigint NOT NULL REFERENCES requests(id) ON DELETE CASCADE,
  started_at timestamptz NOT NULL DEFAULT now(),
  mode text NOT NULL CHECK (mode IN ('normal','preview','replay')),
  status text NOT NULL CHECK (status IN ('success','script_error','send_error')),
  logs text NOT NULL, messages jsonb, send_results jsonb, error text);
