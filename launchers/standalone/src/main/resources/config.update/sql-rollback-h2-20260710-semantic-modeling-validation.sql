-- Roll back phase 4 validation persistence only. No formal semantic assets are changed.
DROP TABLE IF EXISTS `s2_semantic_validation_report`;

UPDATE `s2_semantic_modeling_draft`
SET `status` = 'DRAFT',
    `lock_version` = `lock_version` + 1,
    `updated_by` = 'phase4_rollback',
    `updated_at` = CURRENT_TIMESTAMP
WHERE `status` = 'PENDING_APPROVAL';

DROP INDEX IF EXISTS `uk_semantic_draft_version_request`;

ALTER TABLE `s2_semantic_modeling_draft_version`
    DROP COLUMN IF EXISTS `request_fingerprint`;
ALTER TABLE `s2_semantic_modeling_draft_version`
    DROP COLUMN IF EXISTS `request_idempotency_key`;

ALTER TABLE `s2_semantic_modeling_draft`
    DROP COLUMN IF EXISTS `submitted_at`;
ALTER TABLE `s2_semantic_modeling_draft`
    DROP COLUMN IF EXISTS `submitted_by`;
ALTER TABLE `s2_semantic_modeling_draft`
    DROP COLUMN IF EXISTS `submission_idempotency_key`;
ALTER TABLE `s2_semantic_modeling_draft`
    DROP COLUMN IF EXISTS `submitted_validation_report_id`;
