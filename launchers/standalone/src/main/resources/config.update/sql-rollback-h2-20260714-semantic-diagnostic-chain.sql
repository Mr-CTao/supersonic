-- 回滚仅删除新增诊断列；问答与既有缺口治理数据不受影响。
ALTER TABLE s2_semantic_gap DROP COLUMN IF EXISTS diagnostic_stage;
ALTER TABLE s2_semantic_gap DROP COLUMN IF EXISTS error_code;
ALTER TABLE s2_semantic_gap DROP COLUMN IF EXISTS trace_id;
ALTER TABLE s2_semantic_gap DROP COLUMN IF EXISTS error_line;
ALTER TABLE s2_semantic_gap DROP COLUMN IF EXISTS error_column;
ALTER TABLE s2_semantic_gap DROP COLUMN IF EXISTS error_token;
ALTER TABLE s2_semantic_gap DROP COLUMN IF EXISTS suggestion;
