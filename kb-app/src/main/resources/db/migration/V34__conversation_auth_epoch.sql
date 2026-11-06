-- 历史轮次不推断认证代次；缺失代次只允许维护任务关闭及结算。
ALTER TABLE kb_conversation_turn ADD COLUMN auth_epoch BIGINT CHECK (auth_epoch > 0);
ALTER TABLE kb_conversation_turn ADD COLUMN cancellation_reason TEXT
    CHECK (cancellation_reason IS NULL OR (cancellation_reason = 'ACCOUNT_REVOKED' AND status = 'CANCELLED'));
