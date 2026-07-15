-- 语义诊断链路正向迁移：新增长度受限的可查询诊断列，不回填历史记录。
ALTER TABLE s2_semantic_gap ADD COLUMN IF NOT EXISTS diagnostic_stage varchar(64);
ALTER TABLE s2_semantic_gap ADD COLUMN IF NOT EXISTS error_code varchar(64);
ALTER TABLE s2_semantic_gap ADD COLUMN IF NOT EXISTS trace_id varchar(64);
ALTER TABLE s2_semantic_gap ADD COLUMN IF NOT EXISTS error_line integer;
ALTER TABLE s2_semantic_gap ADD COLUMN IF NOT EXISTS error_column integer;
ALTER TABLE s2_semantic_gap ADD COLUMN IF NOT EXISTS error_token varchar(128);
ALTER TABLE s2_semantic_gap ADD COLUMN IF NOT EXISTS suggestion varchar(1000);
