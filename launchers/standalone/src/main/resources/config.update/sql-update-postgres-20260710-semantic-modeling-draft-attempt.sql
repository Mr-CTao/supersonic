-- 20260710 AI semantic modeling phase 3: generation-attempt history and retry support.
-- The migration only extends the isolated draft subsystem. It does not touch formal semantic
-- assets, publish events, or introduce foreign keys to the phase 1/2 tables.

ALTER TABLE s2_semantic_modeling_draft
    ADD COLUMN IF NOT EXISTS current_attempt_no integer NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS s2_semantic_modeling_draft_attempt (
    id BIGSERIAL PRIMARY KEY,
    draft_id bigint NOT NULL,
    attempt_no integer NOT NULL,
    trigger_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    chat_model_id integer NOT NULL,
    include_sample boolean NOT NULL DEFAULT false,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(128) DEFAULT NULL,
    llm_conversation_id bigint DEFAULT NULL,
    generate_request_id varchar(128) DEFAULT NULL,
    repair_request_id varchar(128) DEFAULT NULL,
    raw_output text DEFAULT NULL,
    repaired_output text DEFAULT NULL,
    failure_stage varchar(64) DEFAULT NULL,
    validation_issues text DEFAULT NULL,
    error_code varchar(64) DEFAULT NULL,
    error_message varchar(1500) DEFAULT NULL,
    started_at timestamp DEFAULT NULL,
    finished_at timestamp DEFAULT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamp NOT NULL,
    updated_by varchar(100) NOT NULL,
    updated_at timestamp NOT NULL
);

COMMENT ON TABLE s2_semantic_modeling_draft_attempt IS
    'Immutable generation-attempt history for AI semantic modeling drafts';
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_attempt_no
    ON s2_semantic_modeling_draft_attempt (draft_id, attempt_no);
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_attempt_idempotency
    ON s2_semantic_modeling_draft_attempt (created_by, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_attempt_list
    ON s2_semantic_modeling_draft_attempt (draft_id, attempt_no DESC);
CREATE INDEX IF NOT EXISTS idx_semantic_draft_attempt_recovery
    ON s2_semantic_modeling_draft_attempt (status, started_at);

-- Existing drafts predate attempt history. Backfill a single immutable attempt without trying to
-- reconstruct historical provider request IDs that were not retained by the original schema.
INSERT INTO s2_semantic_modeling_draft_attempt (
    draft_id, attempt_no, trigger_type, status, chat_model_id, include_sample,
    idempotency_key, llm_conversation_id, raw_output, repaired_output, error_code,
    error_message, started_at, finished_at, created_by, created_at, updated_by, updated_at
)
SELECT id,
       1,
       'INITIAL',
       CASE
           WHEN status = 'DRAFT' THEN 'SUCCEEDED'
           WHEN status = 'GENERATION_FAILED' THEN 'FAILED'
           WHEN status = 'GENERATING' AND generation_started_at IS NULL THEN 'QUEUED'
           ELSE 'GENERATING'
       END,
       chat_model_id,
       include_sample,
       idempotency_key,
       llm_conversation_id,
       raw_output,
       repaired_output,
       error_code,
       error_message,
       generation_started_at,
       generation_finished_at,
       created_by,
       created_at,
       updated_by,
       updated_at
FROM s2_semantic_modeling_draft
ON CONFLICT (draft_id, attempt_no) DO NOTHING;
