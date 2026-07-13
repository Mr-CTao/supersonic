-- Roll back phase 4 validation persistence only. No formal semantic assets are changed.
DROP TABLE IF EXISTS `s2_semantic_validation_report`;

UPDATE `s2_semantic_modeling_draft`
SET `status` = 'DRAFT',
    `lock_version` = `lock_version` + 1,
    `updated_by` = 'phase4_rollback',
    `updated_at` = CURRENT_TIMESTAMP
WHERE `status` = 'PENDING_APPROVAL';

ALTER TABLE `s2_semantic_modeling_draft_version`
    DROP INDEX `uk_semantic_draft_version_request`,
    DROP COLUMN `request_fingerprint`,
    DROP COLUMN `request_idempotency_key`;

ALTER TABLE `s2_semantic_modeling_draft`
    DROP COLUMN `submitted_at`,
    DROP COLUMN `submitted_by`,
    DROP COLUMN `submission_idempotency_key`,
    DROP COLUMN `submitted_validation_report_id`;
