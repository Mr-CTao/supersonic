-- Roll back semantic asset routing persistence only; existing drafts and formal assets remain.
DROP INDEX IF EXISTS uk_semantic_draft_route_analysis;
ALTER TABLE s2_semantic_modeling_draft
    DROP COLUMN IF EXISTS route_target_asset_version,
    DROP COLUMN IF EXISTS route_target_asset_id,
    DROP COLUMN IF EXISTS route_target_asset_type,
    DROP COLUMN IF EXISTS route_action,
    DROP COLUMN IF EXISTS route_analysis_id;
DROP TABLE IF EXISTS s2_semantic_asset_route;
