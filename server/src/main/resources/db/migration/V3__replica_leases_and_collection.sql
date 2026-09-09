ALTER TABLE boards ADD COLUMN gc_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE board_snapshots ADD COLUMN gc_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE replica_leases (
    board_id UUID NOT NULL REFERENCES boards(id),
    lease_id TEXT NOT NULL,
    acknowledged_sequence BIGINT NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (board_id, lease_id)
);
CREATE INDEX replica_leases_expiry ON replica_leases(expires_at);
-- Keep only an ID/sequence fence after removing the large element/register record.
-- Historical operation payloads remain in the archive for history playback.
CREATE TABLE retired_elements (
    board_id UUID NOT NULL REFERENCES boards(id),
    element_id UUID NOT NULL,
    removed_sequence BIGINT NOT NULL,
    PRIMARY KEY (board_id, element_id)
);
CREATE INDEX archived_removals ON operations_archive(board_id,sequence_number)
    WHERE payload->>'type'='ELEMENT_REMOVED';
