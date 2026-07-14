-- Roll back phase 5 governance tables only. Formal semantic objects must be rolled back in the UI first.
DROP TABLE IF EXISTS `s2_semantic_release_step`;
DROP TABLE IF EXISTS `s2_semantic_release`;
ALTER TABLE `s2_semantic_modeling_draft`
    DROP COLUMN `rejected_at`,
    DROP COLUMN `rejected_by`,
    DROP COLUMN `approval_reason`,
    DROP COLUMN `approved_at`,
    DROP COLUMN `approved_by`;
