-- Roll back semantic asset routing persistence only; existing drafts and formal assets remain.
ALTER TABLE `s2_semantic_modeling_draft`
    DROP INDEX `uk_semantic_draft_route_analysis`,
    DROP COLUMN `route_target_asset_version`,
    DROP COLUMN `route_target_asset_id`,
    DROP COLUMN `route_target_asset_type`,
    DROP COLUMN `route_action`,
    DROP COLUMN `route_analysis_id`;
DROP TABLE IF EXISTS `s2_semantic_asset_route`;
