-- 20260713 phase 4 restore replay determinism.
-- Nullable preserves compatibility for non-RESTORED and migration-before historical versions.
ALTER TABLE s2_semantic_modeling_draft_version
    ADD COLUMN IF NOT EXISTS result_lock_version integer DEFAULT NULL;
