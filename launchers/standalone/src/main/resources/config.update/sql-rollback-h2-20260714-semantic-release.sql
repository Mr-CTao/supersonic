-- Roll back phase 5 governance tables only. Formal semantic objects must be rolled back in the UI first.
DROP TABLE IF EXISTS `s2_semantic_release_step`;
DROP TABLE IF EXISTS `s2_semantic_release`;
ALTER TABLE `s2_semantic_modeling_draft` DROP COLUMN IF EXISTS `rejected_at`;
ALTER TABLE `s2_semantic_modeling_draft` DROP COLUMN IF EXISTS `rejected_by`;
ALTER TABLE `s2_semantic_modeling_draft` DROP COLUMN IF EXISTS `approval_reason`;
ALTER TABLE `s2_semantic_modeling_draft` DROP COLUMN IF EXISTS `approved_at`;
ALTER TABLE `s2_semantic_modeling_draft` DROP COLUMN IF EXISTS `approved_by`;
