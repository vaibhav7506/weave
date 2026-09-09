CREATE TABLE boards (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0)
);
CREATE TABLE operations (
    board_id UUID NOT NULL REFERENCES boards(id),
    op_id UUID NOT NULL,
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, sequence_number),
    UNIQUE (board_id, op_id)
);
-- No presence table: cursor/selection updates are deliberately ephemeral.
