-- 仅保存时间界限，不保留已删除调用的明细替身。
CREATE TABLE kb_ai_observation_coverage (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    history_cleared_through TIMESTAMPTZ
);

-- 旧采集期间可能已清理，无法通过剩余明细证明历史完整。
-- 未开始采集的空库保持空界限；已有区间则保守标记到迁移时刻。
INSERT INTO kb_ai_observation_coverage (singleton_id, history_cleared_through)
SELECT 1, CASE WHEN EXISTS (SELECT 1 FROM kb_ai_collection_period)
    THEN clock_timestamp() ELSE NULL END;
