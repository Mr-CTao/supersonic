-- 20260710 AI semantic modeling phase 3 rollback.
-- Drop immutable child snapshots first, then the draft aggregate root.
DROP TABLE IF EXISTS s2_semantic_modeling_draft_attempt;
DROP TABLE IF EXISTS s2_semantic_modeling_draft_version;
DROP TABLE IF EXISTS s2_semantic_modeling_draft;
