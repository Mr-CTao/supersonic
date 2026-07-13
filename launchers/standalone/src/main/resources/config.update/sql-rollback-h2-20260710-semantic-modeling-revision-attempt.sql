-- Roll back phase-4 revision-attempt persistence only; draft versions and formal assets are preserved.
DROP TABLE IF EXISTS `s2_semantic_modeling_revision_attempt`;
