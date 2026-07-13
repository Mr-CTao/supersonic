-- 20260710 AI semantic modeling phase 4: multi-turn revision persistence and validation gate.
-- This migration only extends isolated drafts and creates validation reports. It never writes
-- formal models, dimensions, metrics, terms, approval records, published assets or rollback data.

ALTER TABLE s2_semantic_modeling_draft
    ADD COLUMN IF NOT EXISTS submitted_validation_report_id bigint DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS submission_idempotency_key varchar(128) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS submitted_by varchar(100) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS submitted_at timestamp DEFAULT NULL;

ALTER TABLE s2_semantic_modeling_draft_version
    ADD COLUMN IF NOT EXISTS request_idempotency_key varchar(128) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS request_fingerprint varchar(128) DEFAULT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_draft_version_request
    ON s2_semantic_modeling_draft_version (draft_id, request_idempotency_key);

CREATE TABLE IF NOT EXISTS s2_semantic_validation_report (
    id BIGSERIAL PRIMARY KEY,
    draft_id bigint NOT NULL,
    draft_version_id bigint NOT NULL,
    draft_version_no integer NOT NULL,
    status varchar(32) NOT NULL,
    validation_options text DEFAULT NULL,
    required_check_results text DEFAULT NULL,
    planned_objects text DEFAULT NULL,
    field_existence_result text DEFAULT NULL,
    conflict_result text DEFAULT NULL,
    sensitive_field_result text DEFAULT NULL,
    sample_question_results text DEFAULT NULL,
    sql_safety_result text DEFAULT NULL,
    performance_risk_result text DEFAULT NULL,
    uncertainty_result text DEFAULT NULL,
    blocking_items text DEFAULT NULL,
    warning_items text DEFAULT NULL,
    blocking_count integer NOT NULL DEFAULT 0,
    warning_count integer NOT NULL DEFAULT 0,
    active_marker integer DEFAULT NULL,
    system_error_code varchar(64) DEFAULT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamp NOT NULL,
    finished_at timestamp DEFAULT NULL
);

COMMENT ON TABLE s2_semantic_validation_report IS
    'Phase 4 validation reports for isolated drafts; no approval, publication or formal semantic asset writes';
CREATE UNIQUE INDEX IF NOT EXISTS uk_semantic_validation_active
    ON s2_semantic_validation_report (draft_id, active_marker);
CREATE INDEX IF NOT EXISTS idx_semantic_validation_draft
    ON s2_semantic_validation_report (draft_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_semantic_validation_version_status
    ON s2_semantic_validation_report (draft_version_id, status);
