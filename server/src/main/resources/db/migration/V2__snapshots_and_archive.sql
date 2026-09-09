CREATE TABLE board_snapshots (
    board_id UUID PRIMARY KEY REFERENCES boards(id),
    sequence_number BIGINT NOT NULL,
    compacted_state JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Archive remains part of the immutable logical log: history and retry dedup survive compaction.
CREATE TABLE operations_archive (LIKE operations INCLUDING ALL);
ALTER TABLE operations_archive ADD FOREIGN KEY (board_id) REFERENCES boards(id);
