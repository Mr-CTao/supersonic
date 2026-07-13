-- 20260713 phase 4 restore replay determinism.
-- Dynamic DDL keeps the standalone migration idempotent for partially upgraded environments.
SET @stage4_restore_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft_version'
             AND column_name = 'result_lock_version'),
    'DO 0',
    'ALTER TABLE `s2_semantic_modeling_draft_version` ADD COLUMN `result_lock_version` int DEFAULT NULL COMMENT ''RESTORED首次成功响应的草稿锁版本'' AFTER `request_fingerprint`'
);
PREPARE stage4_restore_stmt FROM @stage4_restore_ddl;
EXECUTE stage4_restore_stmt;
DEALLOCATE PREPARE stage4_restore_stmt;
SET @stage4_restore_ddl = NULL;
