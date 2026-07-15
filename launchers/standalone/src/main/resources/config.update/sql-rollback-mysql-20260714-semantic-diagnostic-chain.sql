-- 回滚仅删除新增诊断列；问答与既有缺口治理数据不受影响。
ALTER TABLE `s2_semantic_gap`
    DROP COLUMN `diagnostic_stage`,
    DROP COLUMN `error_code`,
    DROP COLUMN `trace_id`,
    DROP COLUMN `error_line`,
    DROP COLUMN `error_column`,
    DROP COLUMN `error_token`,
    DROP COLUMN `suggestion`;
