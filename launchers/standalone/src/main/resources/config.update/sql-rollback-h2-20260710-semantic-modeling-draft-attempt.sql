-- Roll back phase 3 generation-attempt history without deleting logical drafts or versions.
DROP TABLE IF EXISTS `s2_semantic_modeling_draft_attempt`;

ALTER TABLE `s2_semantic_modeling_draft`
    DROP COLUMN IF EXISTS `current_attempt_no`;
