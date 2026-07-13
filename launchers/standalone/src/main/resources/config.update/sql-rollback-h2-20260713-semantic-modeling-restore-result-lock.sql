-- 20260713 phase 4 restore replay determinism rollback.
ALTER TABLE `s2_semantic_modeling_draft_version`
    DROP COLUMN IF EXISTS `result_lock_version`;
