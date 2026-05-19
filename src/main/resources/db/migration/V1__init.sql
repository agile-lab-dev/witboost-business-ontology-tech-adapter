-- Graph state table: one row per provisioned system URN.
-- Provision atomically replaces a system's partition; unprovision deletes it.

CREATE TABLE graph_state (
    system_urn  VARCHAR     PRIMARY KEY,
    content     TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
