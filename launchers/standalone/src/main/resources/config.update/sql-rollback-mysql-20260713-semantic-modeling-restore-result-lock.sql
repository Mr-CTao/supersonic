-- 20260713 phase 4 restore replay determinism rollback.
SET @stage4_restore_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE()
             AND table_name = 's2_semantic_modeling_draft_version'
             AND column_name = 'result_lock_version'),
    'ALTER TABLE `s2_semantic_modeling_draft_version` DROP COLUMN `result_lock_version`',
    'DO 0'
);
PREPARE stage4_restore_stmt FROM @stage4_restore_ddl;
EXECUTE stage4_restore_stmt;
DEALLOCATE PREPARE stage4_restore_stmt;
SET @stage4_restore_ddl = NULL;
