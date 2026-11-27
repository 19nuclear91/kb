-- 知识订阅与周报：保存发布时归属、生效顺序、个人更新和独立提醒来源。
-- 升级前排空旧 app/Worker 写入者；不回填历史发布，不修改原通知身份或首次已读时间。

-- 生效顺序由事务持有的单例行锁确定，回滚不消耗位置；时钟回拨不倒退业务日期。
CREATE TABLE kb_knowledge_order (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    position BIGINT NOT NULL CHECK (position >= 0),
    business_time TIMESTAMPTZ NOT NULL,
    coverage_at TIMESTAMPTZ NOT NULL,
    protocol_version INTEGER NOT NULL CHECK (protocol_version = 1)
);
COMMENT ON TABLE kb_knowledge_order IS '订阅发布协议的事务顺序、非递减业务时间及覆盖起点';
COMMENT ON COLUMN kb_knowledge_order.singleton IS '唯一控制行，恒为true';
COMMENT ON COLUMN kb_knowledge_order.position IS '成功事务顺序，从0开始，每次生效递增';
COMMENT ON COLUMN kb_knowledge_order.business_time IS '最近顺序对应的非递减数据库时间，带时区';
COMMENT ON COLUMN kb_knowledge_order.coverage_at IS '本协议启用时间，带时区，不回填更早发布';
COMMENT ON COLUMN kb_knowledge_order.protocol_version IS '协议版本，当前固定1';
INSERT INTO kb_knowledge_order
SELECT TRUE, 0, t, t, 1 FROM (SELECT clock_timestamp() AS t) initial;

CREATE FUNCTION kb_knowledge_next_order()
RETURNS TABLE (position BIGINT, business_time TIMESTAMPTZ) AS $$
DECLARE
    previous kb_knowledge_order%ROWTYPE;
BEGIN
    PERFORM set_config('lock_timeout', '3s', TRUE);
    SELECT * INTO STRICT previous FROM kb_knowledge_order WHERE singleton FOR UPDATE;
    RETURN QUERY UPDATE kb_knowledge_order k
        SET position = previous.position + 1,
            business_time = GREATEST(clock_timestamp(), previous.business_time)
        WHERE k.singleton AND k.protocol_version = 1
        RETURNING k.position, k.business_time;
END;
$$ LANGUAGE plpgsql;
COMMENT ON FUNCTION kb_knowledge_next_order() IS '在调用事务内持有顺序行锁至提交，返回下一位置及北京时间周归属所用时间';

CREATE FUNCTION protect_knowledge_order() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge order is retained';
    END IF;
    IF (NEW.singleton, NEW.coverage_at, NEW.protocol_version)
        IS DISTINCT FROM (OLD.singleton, OLD.coverage_at, OLD.protocol_version)
        OR NEW.position <> OLD.position + 1 OR NEW.business_time < OLD.business_time THEN
        RAISE EXCEPTION 'knowledge order must advance monotonically';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_knowledge_order_guard BEFORE UPDATE OR DELETE ON kb_knowledge_order
    FOR EACH ROW EXECUTE FUNCTION protect_knowledge_order();

CREATE FUNCTION kb_knowledge_week_start(value TIMESTAMPTZ) RETURNS DATE AS $$
    SELECT date_trunc('week', value AT TIME ZONE 'Asia/Shanghai')::date
$$ LANGUAGE sql IMMUTABLE STRICT;
COMMENT ON FUNCTION kb_knowledge_week_start(TIMESTAMPTZ) IS '将带时区发布时间转换为北京时间所属周的周一日期';

-- 个人关系使用稳定身份；历史目标不建立会阻止合法空空间删除的外键。
CREATE TABLE kb_knowledge_subscription (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    kind TEXT NOT NULL CHECK (kind IN ('DOCUMENT', 'SPACE', 'TAG')),
    target_id BIGINT NOT NULL CHECK (target_id > 0),
    state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'CANCELLED')),
    generation_id UUID NOT NULL,
    state_version BIGINT NOT NULL CHECK (state_version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, kind, target_id),
    UNIQUE (id, user_id)
);
COMMENT ON TABLE kb_knowledge_subscription IS '本人知识订阅的稳定目标关系及当前代次';
COMMENT ON COLUMN kb_knowledge_subscription.id IS '订阅关系UUID，重新订阅时保持';
COMMENT ON COLUMN kb_knowledge_subscription.user_id IS '订阅所属用户，关联sys_user';
COMMENT ON COLUMN kb_knowledge_subscription.kind IS '目标类型：DOCUMENT文档、SPACE空间、TAG标签';
COMMENT ON COLUMN kb_knowledge_subscription.target_id IS '对应类型的稳定目标ID，历史关系不阻止空空间删除';
COMMENT ON COLUMN kb_knowledge_subscription.state IS 'ACTIVE有效、CANCELLED已取消';
COMMENT ON COLUMN kb_knowledge_subscription.generation_id IS '当前或最近一次订阅代次UUID';
COMMENT ON COLUMN kb_knowledge_subscription.state_version IS '关系并发版本，从1递增';
COMMENT ON COLUMN kb_knowledge_subscription.created_at IS '首次建立关系的业务时间，带时区';
COMMENT ON COLUMN kb_knowledge_subscription.updated_at IS '最近订阅或取消的业务时间，带时区';
CREATE INDEX idx_knowledge_subscription_target ON kb_knowledge_subscription(kind, target_id, user_id);
CREATE INDEX idx_knowledge_subscription_owner ON kb_knowledge_subscription(user_id, id);

CREATE TABLE kb_knowledge_subscription_period (
    id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    start_position BIGINT NOT NULL CHECK (start_position > 0),
    start_at TIMESTAMPTZ NOT NULL,
    end_position BIGINT,
    end_at TIMESTAMPTZ,
    FOREIGN KEY (subscription_id, user_id) REFERENCES kb_knowledge_subscription(id, user_id),
    UNIQUE (id, subscription_id, user_id),
    UNIQUE (id, user_id),
    CHECK ((end_position IS NULL) = (end_at IS NULL)),
    CHECK (end_position IS NULL OR (end_position > start_position AND end_at >= start_at))
);
COMMENT ON TABLE kb_knowledge_subscription_period IS '订阅代次的不可改生效区间，取消只允许首次关闭';
COMMENT ON COLUMN kb_knowledge_subscription_period.id IS '独立代次UUID，重新订阅创建新值';
COMMENT ON COLUMN kb_knowledge_subscription_period.subscription_id IS '所属稳定订阅关系UUID';
COMMENT ON COLUMN kb_knowledge_subscription_period.user_id IS '所属用户，与订阅关系保持一致';
COMMENT ON COLUMN kb_knowledge_subscription_period.start_position IS '生效事务位置，仅之后的发布可匹配';
COMMENT ON COLUMN kb_knowledge_subscription_period.start_at IS '生效业务时间，带时区';
COMMENT ON COLUMN kb_knowledge_subscription_period.end_position IS '取消事务位置，空为尚未取消';
COMMENT ON COLUMN kb_knowledge_subscription_period.end_at IS '取消业务时间，带时区，未取消为空';
CREATE UNIQUE INDEX uq_knowledge_period_active ON kb_knowledge_subscription_period(subscription_id) WHERE end_position IS NULL;
CREATE INDEX idx_knowledge_period_match ON kb_knowledge_subscription_period(subscription_id, start_position, end_position);
ALTER TABLE kb_knowledge_subscription ADD CONSTRAINT fk_knowledge_subscription_generation
    FOREIGN KEY (generation_id, id, user_id) REFERENCES kb_knowledge_subscription_period(id, subscription_id, user_id)
    DEFERRABLE INITIALLY DEFERRED;

-- 个人频率与追加修订分开，发布位置决定所用频率，读取默认值不创建行。
CREATE TABLE kb_knowledge_preference (
    user_id BIGINT PRIMARY KEY REFERENCES sys_user(id),
    mode TEXT NOT NULL CHECK (mode IN ('PER_PUBLICATION', 'WEEKLY')),
    version BIGINT NOT NULL CHECK (version > 0),
    effective_position BIGINT NOT NULL CHECK (effective_position > 0),
    effective_at TIMESTAMPTZ NOT NULL
);
COMMENT ON TABLE kb_knowledge_preference IS '本人当前统一提醒频率，无记录时每周汇总且版本0';
COMMENT ON COLUMN kb_knowledge_preference.user_id IS '偏好所属用户，关联sys_user';
COMMENT ON COLUMN kb_knowledge_preference.mode IS 'PER_PUBLICATION逐次提醒、WEEKLY每周汇总';
COMMENT ON COLUMN kb_knowledge_preference.version IS '偏好并发版本，从首次修改的1递增';
COMMENT ON COLUMN kb_knowledge_preference.effective_position IS '当前偏好生效的事务位置';
COMMENT ON COLUMN kb_knowledge_preference.effective_at IS '偏好生效业务时间，带时区';
CREATE TABLE kb_knowledge_preference_revision (
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    position BIGINT NOT NULL CHECK (position > 0),
    mode TEXT NOT NULL CHECK (mode IN ('PER_PUBLICATION', 'WEEKLY')),
    effective_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, position)
);
COMMENT ON TABLE kb_knowledge_preference_revision IS '不可变提醒频率历史，供按发布时位置选择';
COMMENT ON COLUMN kb_knowledge_preference_revision.user_id IS '偏好所属用户，关联sys_user';
COMMENT ON COLUMN kb_knowledge_preference_revision.position IS '频率修订生效事务位置';
COMMENT ON COLUMN kb_knowledge_preference_revision.mode IS 'PER_PUBLICATION逐次提醒、WEEKLY每周汇总';
COMMENT ON COLUMN kb_knowledge_preference_revision.effective_at IS '本修订生效业务时间，带时区';

CREATE TABLE kb_knowledge_command_receipt (
    actor_id BIGINT NOT NULL REFERENCES sys_user(id),
    request_id TEXT NOT NULL CHECK (char_length(request_id) BETWEEN 1 AND 128 AND request_id !~ '[[:cntrl:]]'),
    operation TEXT NOT NULL CHECK (operation IN ('SUBSCRIBE', 'CANCEL', 'PREFERENCE')),
    input_digest TEXT NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    receipt JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor_id, request_id)
);
COMMENT ON TABLE kb_knowledge_command_receipt IS '本人订阅命令原意图与最小回执，随业务原子提交';
COMMENT ON COLUMN kb_knowledge_command_receipt.actor_id IS '可信操作用户，限定本人核对';
COMMENT ON COLUMN kb_knowledge_command_receipt.request_id IS '本人请求标识，最多128字符';
COMMENT ON COLUMN kb_knowledge_command_receipt.operation IS 'SUBSCRIBE订阅、CANCEL取消、PREFERENCE改频率';
COMMENT ON COLUMN kb_knowledge_command_receipt.input_digest IS '完整规范输入绑定的SHA256十六进制值，含目标代次和预期版本';
COMMENT ON COLUMN kb_knowledge_command_receipt.receipt IS '最小JSON回执，仅身份、状态、版本和时间，不含目标名称或正文';
COMMENT ON COLUMN kb_knowledge_command_receipt.created_at IS '命令登记时间，带时区';

-- 发布源只保存ID与时间，空间和标签采用发布事务内锁定的文档事实。
ALTER TABLE kb_collaboration_event ADD CONSTRAINT uq_collaboration_publication_owner UNIQUE (event_id, doc_id, version_id);
CREATE FUNCTION kb_knowledge_valid_tags(value BIGINT[]) RETURNS BOOLEAN AS $$
    SELECT value IS NOT NULL AND cardinality(value) <= 20
        AND value = ARRAY(SELECT DISTINCT item FROM unnest(value) item WHERE item > 0 ORDER BY item)
$$ LANGUAGE sql IMMUTABLE;
COMMENT ON FUNCTION kb_knowledge_valid_tags(BIGINT[]) IS '校验发布标签集合最多20个，均为正整数且升序去重';
CREATE TABLE kb_knowledge_publication (
    event_id UUID PRIMARY KEY,
    position BIGINT NOT NULL UNIQUE CHECK (position > 0),
    document_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL UNIQUE,
    space_id BIGINT NOT NULL CHECK (space_id > 0),
    tag_ids BIGINT[] NOT NULL CHECK (kb_knowledge_valid_tags(tag_ids)),
    published_at TIMESTAMPTZ NOT NULL,
    week_start DATE NOT NULL,
    FOREIGN KEY (event_id, document_id, version_id) REFERENCES kb_collaboration_event(event_id, doc_id, version_id),
    CHECK (week_start = kb_knowledge_week_start(published_at))
);
COMMENT ON TABLE kb_knowledge_publication IS '正式发布时固定的订阅上下文，不回填历史、不复制内容';
COMMENT ON COLUMN kb_knowledge_publication.event_id IS '关联唯一VERSION_PUBLISHED协作事件UUID';
COMMENT ON COLUMN kb_knowledge_publication.position IS '正式发布事务顺序';
COMMENT ON COLUMN kb_knowledge_publication.document_id IS '发布所属文档ID，与源事件一致';
COMMENT ON COLUMN kb_knowledge_publication.version_id IS '实际正式发布的固定文件版本ID';
COMMENT ON COLUMN kb_knowledge_publication.space_id IS '发布时所属空间ID，后续搬迁不改写';
COMMENT ON COLUMN kb_knowledge_publication.tag_ids IS '发布时标签ID集合，升序去重且最多20项';
COMMENT ON COLUMN kb_knowledge_publication.published_at IS '与版本证据及源事件一致的数据库业务时间，带时区';
COMMENT ON COLUMN kb_knowledge_publication.week_start IS '北京时间发布所属周一日期，格式YYYY-MM-DD';
CREATE INDEX idx_knowledge_publication_week ON kb_knowledge_publication(week_start, position);

-- 匹配进度可接管；个人更新与全部原命中代次同批提交，不保存当时正文或权限快照。
CREATE TABLE kb_knowledge_match_projection (
    event_id UUID PRIMARY KEY REFERENCES kb_knowledge_publication(event_id),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'WAITING_SOURCE', 'DONE')),
    lease_owner TEXT CHECK (char_length(lease_owner) BETWEEN 1 AND 64),
    lease_until TIMESTAMPTZ,
    claim_token BIGINT NOT NULL DEFAULT 0 CHECK (claim_token >= 0),
    recipient_cursor BIGINT NOT NULL DEFAULT 0 CHECK (recipient_cursor >= 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    error_class TEXT CHECK (error_class IN ('SOURCE_NOT_READY', 'DERIVATION_FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((status = 'DONE') = (completed_at IS NOT NULL)),
    CHECK (status <> 'DONE' OR lease_owner IS NULL)
);
COMMENT ON TABLE kb_knowledge_match_projection IS '订阅发布匹配的独立租约、收件人游标与退避进度';
COMMENT ON COLUMN kb_knowledge_match_projection.event_id IS '关联订阅发布上下文UUID';
COMMENT ON COLUMN kb_knowledge_match_projection.status IS 'PENDING待处理、WAITING_SOURCE等待协议、DONE匹配完成';
COMMENT ON COLUMN kb_knowledge_match_projection.lease_owner IS '当前执行实例标识，未领取为空';
COMMENT ON COLUMN kb_knowledge_match_projection.lease_until IS '数据库时钟下租约截止时间，带时区';
COMMENT ON COLUMN kb_knowledge_match_projection.claim_token IS '每次领取递增的执行代次，拒绝旧执行写入';
COMMENT ON COLUMN kb_knowledge_match_projection.recipient_cursor IS '最近原子完成匹配的用户ID，初始0';
COMMENT ON COLUMN kb_knowledge_match_projection.attempts IS '累计退避次数，初始0';
COMMENT ON COLUMN kb_knowledge_match_projection.next_attempt_at IS '下一次允许领取时间，带时区';
COMMENT ON COLUMN kb_knowledge_match_projection.error_class IS '固定失败分类，正常为空，不含异常正文';
COMMENT ON COLUMN kb_knowledge_match_projection.created_at IS '首次登记进度时间，带时区';
COMMENT ON COLUMN kb_knowledge_match_projection.updated_at IS '最近进度更新时间，带时区';
COMMENT ON COLUMN kb_knowledge_match_projection.completed_at IS '完成匹配时间，带时区，未完成为空';
CREATE INDEX idx_knowledge_match_due ON kb_knowledge_match_projection(next_attempt_at, event_id) WHERE status <> 'DONE';
CREATE TRIGGER trg_knowledge_match_guard BEFORE UPDATE OR DELETE ON kb_knowledge_match_projection
    FOR EACH ROW EXECUTE FUNCTION protect_notification_projection();

CREATE TABLE kb_knowledge_update (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    event_id UUID NOT NULL REFERENCES kb_knowledge_publication(event_id),
    mode TEXT NOT NULL CHECK (mode IN ('PER_PUBLICATION', 'WEEKLY')),
    week_start DATE NOT NULL CHECK (extract(isodow FROM week_start) = 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (user_id, event_id),
    UNIQUE (id, user_id)
);
COMMENT ON TABLE kb_knowledge_update IS '本人发布时命中订阅的无正文更新记录，取消后保留';
COMMENT ON COLUMN kb_knowledge_update.id IS '个人更新UUID，固定对应一次正式发布';
COMMENT ON COLUMN kb_knowledge_update.user_id IS '个人更新所属用户，关联sys_user';
COMMENT ON COLUMN kb_knowledge_update.event_id IS '原正式发布上下文UUID';
COMMENT ON COLUMN kb_knowledge_update.mode IS '发布时频率：PER_PUBLICATION逐次、WEEKLY每周';
COMMENT ON COLUMN kb_knowledge_update.week_start IS '发布所属北京时间周一日期，YYYY-MM-DD';
COMMENT ON COLUMN kb_knowledge_update.created_at IS '实际形成个人更新的时间，带时区';
CREATE INDEX idx_knowledge_update_week ON kb_knowledge_update(week_start, mode, user_id, id);
CREATE INDEX idx_knowledge_update_owner ON kb_knowledge_update(user_id, event_id);
CREATE TABLE kb_knowledge_update_match (
    update_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    generation_id UUID NOT NULL,
    PRIMARY KEY (update_id, generation_id),
    FOREIGN KEY (update_id, user_id) REFERENCES kb_knowledge_update(id, user_id),
    FOREIGN KEY (generation_id, user_id) REFERENCES kb_knowledge_subscription_period(id, user_id)
);
COMMENT ON TABLE kb_knowledge_update_match IS '每次个人发布命中的全部原订阅代次，重叠订阅独立支持提醒';
COMMENT ON COLUMN kb_knowledge_update_match.update_id IS '关联本人更新UUID';
COMMENT ON COLUMN kb_knowledge_update_match.user_id IS '更新与代次共同所属的用户ID';
COMMENT ON COLUMN kb_knowledge_update_match.generation_id IS '发布当时命中的订阅代次UUID，后来重订阅不得替换';

-- 每周封口等待全部发布匹配完成，再分批登记用户提醒；DONE仅表示工作已登记。
CREATE TABLE kb_knowledge_week (
    week_start DATE PRIMARY KEY CHECK (extract(isodow FROM week_start) = 1),
    due_at TIMESTAMPTZ NOT NULL,
    close_position BIGINT NOT NULL UNIQUE CHECK (close_position > 0),
    status TEXT NOT NULL CHECK (status IN ('WAITING_MATCHES', 'READY', 'DONE')),
    lease_owner TEXT CHECK (char_length(lease_owner) BETWEEN 1 AND 64),
    lease_until TIMESTAMPTZ,
    claim_token BIGINT NOT NULL DEFAULT 0 CHECK (claim_token >= 0),
    recipient_cursor BIGINT NOT NULL DEFAULT 0 CHECK (recipient_cursor >= 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    error_class TEXT CHECK (error_class IN ('DERIVATION_FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((status = 'DONE') = (completed_at IS NOT NULL)),
    CHECK (status <> 'DONE' OR lease_owner IS NULL),
    CHECK (due_at = ((week_start + 7)::timestamp + interval '9 hours') AT TIME ZONE 'Asia/Shanghai')
);
COMMENT ON TABLE kb_knowledge_week IS '固定业务周封口、匹配完整性与周报收件人发现进度';
COMMENT ON COLUMN kb_knowledge_week.week_start IS '北京时间统计周的周一日期，含起点不含下周一';
COMMENT ON COLUMN kb_knowledge_week.due_at IS '统计周结束后周一北京时间09:00的最早提醒时间';
COMMENT ON COLUMN kb_knowledge_week.close_position IS '可信封口顺序，之前的发布事务均已结束';
COMMENT ON COLUMN kb_knowledge_week.status IS 'WAITING_MATCHES等匹配、READY发现收件人、DONE登记完成';
COMMENT ON COLUMN kb_knowledge_week.lease_owner IS '当前周任务领取实例，空为无租约';
COMMENT ON COLUMN kb_knowledge_week.lease_until IS '周任务租约截止时间，带时区';
COMMENT ON COLUMN kb_knowledge_week.claim_token IS '周任务领取代次，拒绝旧执行推进';
COMMENT ON COLUMN kb_knowledge_week.recipient_cursor IS '最近已登记周报的用户ID，初始0';
COMMENT ON COLUMN kb_knowledge_week.attempts IS '周任务失败退避次数，初始0';
COMMENT ON COLUMN kb_knowledge_week.next_attempt_at IS '周任务下一允许领取时间，带时区';
COMMENT ON COLUMN kb_knowledge_week.error_class IS '固定失败分类DERIVATION_FAILED，正常为空';
COMMENT ON COLUMN kb_knowledge_week.created_at IS '周期首次封口时间，带时区';
COMMENT ON COLUMN kb_knowledge_week.completed_at IS '全部用户提醒工作登记完成时间，未完成为空';
CREATE INDEX idx_knowledge_week_due ON kb_knowledge_week(next_attempt_at, week_start) WHERE status <> 'DONE';

CREATE TABLE kb_knowledge_delivery (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    kind TEXT NOT NULL CHECK (kind IN ('PER_PUBLICATION', 'WEEKLY')),
    update_id UUID,
    week_start DATE REFERENCES kb_knowledge_week(week_start),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'NOTIFIED', 'NO_ELIGIBLE')),
    lease_owner TEXT CHECK (char_length(lease_owner) BETWEEN 1 AND 64),
    lease_until TIMESTAMPTZ,
    claim_token BIGINT NOT NULL DEFAULT 0 CHECK (claim_token >= 0),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    error_class TEXT CHECK (error_class IN ('DERIVATION_FAILED')),
    dispatch_position BIGINT CHECK (dispatch_position > 0),
    dispatched_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (update_id, user_id) REFERENCES kb_knowledge_update(id, user_id),
    UNIQUE (id, user_id),
    UNIQUE (user_id, update_id),
    UNIQUE (user_id, week_start),
    CHECK ((kind = 'PER_PUBLICATION' AND update_id IS NOT NULL AND week_start IS NULL)
        OR (kind = 'WEEKLY' AND update_id IS NULL AND week_start IS NOT NULL)),
    CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CHECK ((dispatch_position IS NULL) = (dispatched_at IS NULL)),
    CHECK ((status = 'PENDING') = (dispatch_position IS NULL)),
    CHECK (status = 'PENDING' OR lease_owner IS NULL)
);
COMMENT ON TABLE kb_knowledge_delivery IS '本人逐次或周报提醒的独立工作、去重终态与生成顺序';
COMMENT ON COLUMN kb_knowledge_delivery.id IS '提醒工作UUID，周报时也作为digestId';
COMMENT ON COLUMN kb_knowledge_delivery.user_id IS '提醒接收用户，关联sys_user';
COMMENT ON COLUMN kb_knowledge_delivery.kind IS 'PER_PUBLICATION逐次提醒、WEEKLY周报提醒';
COMMENT ON COLUMN kb_knowledge_delivery.update_id IS '逐次提醒的本人更新UUID，周报为空';
COMMENT ON COLUMN kb_knowledge_delivery.week_start IS '周报的统计周一日期，逐次提醒为空';
COMMENT ON COLUMN kb_knowledge_delivery.status IS 'PENDING待生成、NOTIFIED已生成通知、NO_ELIGIBLE无合格项目';
COMMENT ON COLUMN kb_knowledge_delivery.lease_owner IS '当前领取实例，未领取或终态为空';
COMMENT ON COLUMN kb_knowledge_delivery.lease_until IS '执行租约截止时间，带时区';
COMMENT ON COLUMN kb_knowledge_delivery.claim_token IS '每次领取递增的执行代次';
COMMENT ON COLUMN kb_knowledge_delivery.attempts IS '失败退避次数，初始0';
COMMENT ON COLUMN kb_knowledge_delivery.next_attempt_at IS '下次允许领取时间，带时区';
COMMENT ON COLUMN kb_knowledge_delivery.error_class IS '固定失败分类DERIVATION_FAILED，正常为空';
COMMENT ON COLUMN kb_knowledge_delivery.dispatch_position IS '首次终结顺序，确定取消订阅是否早于提醒生成';
COMMENT ON COLUMN kb_knowledge_delivery.dispatched_at IS '首次终结业务时间，带时区，不代表用户已读';
COMMENT ON COLUMN kb_knowledge_delivery.created_at IS '提醒工作登记时间，带时区';
CREATE INDEX idx_knowledge_delivery_due ON kb_knowledge_delivery(next_attempt_at, id) WHERE status = 'PENDING';

-- 共享收件箱新增第三来源，完整保留旧两个来源的外键和唯一键。
ALTER TABLE kb_user_notification ADD COLUMN knowledge_delivery_id UUID;
COMMENT ON COLUMN kb_user_notification.knowledge_delivery_id IS '知识订阅提醒工作UUID，仅订阅发布和周报类型非空';
ALTER TABLE kb_user_notification
    DROP CONSTRAINT ck_user_notification_source,
    DROP CONSTRAINT ck_user_notification_type_source,
    ADD CONSTRAINT ck_user_notification_source CHECK (num_nonnulls(event_id, gap_event_id, knowledge_delivery_id) = 1),
    ADD CONSTRAINT ck_user_notification_type_source CHECK (
        (event_id IS NOT NULL AND type IN ('REVIEW_SUBMITTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED', 'COMMENT_REPLIED'))
        OR (gap_event_id IS NOT NULL AND type IN ('GAP_CREATED', 'GAP_RETURNED', 'GAP_ASSIGNED',
            'GAP_SOLUTION_SUBMITTED', 'GAP_SOLUTION_REJECTED', 'GAP_RESOLVED', 'GAP_WITHDRAWN', 'GAP_CLOSED'))
        OR (knowledge_delivery_id IS NOT NULL AND type IN ('SUBSCRIBED_VERSION_PUBLISHED', 'KNOWLEDGE_WEEKLY_DIGEST'))),
    ADD CONSTRAINT uq_user_notification_knowledge UNIQUE (knowledge_delivery_id, recipient_user_id),
    ADD CONSTRAINT fk_user_notification_knowledge FOREIGN KEY (knowledge_delivery_id, recipient_user_id)
        REFERENCES kb_knowledge_delivery(id, user_id);

CREATE OR REPLACE FUNCTION protect_user_notification() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'user notification cleanup is not defined';
    END IF;
    IF (NEW.id, NEW.notification_id, NEW.event_id, NEW.gap_event_id, NEW.knowledge_delivery_id,
        NEW.recipient_user_id, NEW.type, NEW.created_at) IS DISTINCT FROM
       (OLD.id, OLD.notification_id, OLD.event_id, OLD.gap_event_id, OLD.knowledge_delivery_id,
        OLD.recipient_user_id, OLD.type, OLD.created_at) THEN
        RAISE EXCEPTION 'user notification identity is immutable';
    END IF;
    IF OLD.read_at IS NOT NULL AND NEW.read_at IS DISTINCT FROM OLD.read_at THEN
        RAISE EXCEPTION 'first read time is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 历史事实追加保存，取消只首次关闭代次，稳定关系仅允许按版本推进。
CREATE TRIGGER trg_knowledge_preference_revision_immutable BEFORE UPDATE OR DELETE ON kb_knowledge_preference_revision
    FOR EACH ROW EXECUTE FUNCTION reject_collaboration_fact_mutation();
CREATE TRIGGER trg_knowledge_publication_immutable BEFORE UPDATE OR DELETE ON kb_knowledge_publication
    FOR EACH ROW EXECUTE FUNCTION reject_collaboration_fact_mutation();
CREATE TRIGGER trg_knowledge_update_immutable BEFORE UPDATE OR DELETE ON kb_knowledge_update
    FOR EACH ROW EXECUTE FUNCTION reject_collaboration_fact_mutation();
CREATE TRIGGER trg_knowledge_update_match_immutable BEFORE UPDATE OR DELETE ON kb_knowledge_update_match
    FOR EACH ROW EXECUTE FUNCTION reject_collaboration_fact_mutation();

CREATE FUNCTION protect_knowledge_period() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'subscription history is retained';
    END IF;
    IF (NEW.id, NEW.subscription_id, NEW.user_id, NEW.start_position, NEW.start_at)
        IS DISTINCT FROM (OLD.id, OLD.subscription_id, OLD.user_id, OLD.start_position, OLD.start_at)
        OR OLD.end_position IS NOT NULL OR NEW.end_position IS NULL THEN
        RAISE EXCEPTION 'subscription period can close only once';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_knowledge_period_guard BEFORE UPDATE OR DELETE ON kb_knowledge_subscription_period
    FOR EACH ROW EXECUTE FUNCTION protect_knowledge_period();

CREATE FUNCTION protect_knowledge_subscription() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'subscription relation is retained';
    END IF;
    IF (NEW.id, NEW.user_id, NEW.kind, NEW.target_id, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.user_id, OLD.kind, OLD.target_id, OLD.created_at)
        OR NEW.state_version <> OLD.state_version + 1 OR NEW.updated_at < OLD.updated_at
        OR NOT ((OLD.state = 'ACTIVE' AND NEW.state = 'CANCELLED' AND NEW.generation_id = OLD.generation_id)
            OR (OLD.state = 'CANCELLED' AND NEW.state = 'ACTIVE' AND NEW.generation_id <> OLD.generation_id)) THEN
        RAISE EXCEPTION 'invalid subscription transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_knowledge_subscription_guard BEFORE UPDATE OR DELETE ON kb_knowledge_subscription
    FOR EACH ROW EXECUTE FUNCTION protect_knowledge_subscription();

CREATE FUNCTION protect_knowledge_command() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge command is retained';
    END IF;
    IF (NEW.actor_id, NEW.request_id, NEW.operation, NEW.input_digest, NEW.created_at)
        IS DISTINCT FROM (OLD.actor_id, OLD.request_id, OLD.operation, OLD.input_digest, OLD.created_at)
        OR OLD.receipt IS NOT NULL OR NEW.receipt IS NULL THEN
        RAISE EXCEPTION 'knowledge command receipt can complete only once';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_knowledge_command_guard BEFORE UPDATE OR DELETE ON kb_knowledge_command_receipt
    FOR EACH ROW EXECUTE FUNCTION protect_knowledge_command();

-- 关系与代次必须同事务一致提交，取消旧代次与新建代次不得混淆。
CREATE FUNCTION verify_knowledge_subscription() RETURNS TRIGGER AS $$
DECLARE
    relation_uuid UUID;
    relation kb_knowledge_subscription%ROWTYPE;
    period kb_knowledge_subscription_period%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'kb_knowledge_subscription' THEN
        relation_uuid := NEW.id;
    ELSE
        relation_uuid := NEW.subscription_id;
    END IF;
    SELECT * INTO STRICT relation FROM kb_knowledge_subscription WHERE id = relation_uuid;
    SELECT * INTO STRICT period FROM kb_knowledge_subscription_period WHERE id = relation.generation_id;
    IF period.subscription_id <> relation.id OR period.user_id <> relation.user_id
        OR (relation.state = 'ACTIVE') <> (period.end_position IS NULL)
        OR relation.updated_at <> COALESCE(period.end_at, period.start_at)
        OR EXISTS (SELECT 1 FROM kb_knowledge_subscription_period p WHERE p.subscription_id = relation.id
            AND p.end_position IS NULL AND p.id <> relation.generation_id) THEN
        RAISE EXCEPTION 'subscription and generation must commit together';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_knowledge_subscription_complete AFTER INSERT OR UPDATE ON kb_knowledge_subscription
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_knowledge_subscription();
CREATE CONSTRAINT TRIGGER trg_knowledge_period_complete AFTER INSERT OR UPDATE ON kb_knowledge_subscription_period
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_knowledge_subscription();

-- 偏好变更保持追加修订，未完成回执不得独立提交。
CREATE FUNCTION protect_knowledge_preference() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge preference is retained';
    END IF;
    IF NEW.user_id <> OLD.user_id OR NEW.version <> OLD.version + 1
        OR NEW.effective_position <= OLD.effective_position OR NEW.effective_at < OLD.effective_at
        OR NEW.mode = OLD.mode THEN
        RAISE EXCEPTION 'invalid knowledge preference transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_knowledge_preference_guard BEFORE UPDATE OR DELETE ON kb_knowledge_preference
    FOR EACH ROW EXECUTE FUNCTION protect_knowledge_preference();
CREATE FUNCTION verify_knowledge_command_and_preference() RETURNS TRIGGER AS $$
BEGIN
    IF TG_TABLE_NAME = 'kb_knowledge_command_receipt' THEN
        IF NOT EXISTS (SELECT 1 FROM kb_knowledge_command_receipt c
            WHERE c.actor_id = NEW.actor_id AND c.request_id = NEW.request_id AND c.receipt IS NOT NULL) THEN
            RAISE EXCEPTION 'knowledge command requires a completed receipt';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM kb_knowledge_preference p JOIN kb_knowledge_preference_revision r
            ON r.user_id = p.user_id AND r.position = p.effective_position AND r.mode = p.mode
                AND r.effective_at = p.effective_at WHERE p.user_id = NEW.user_id) THEN
            RAISE EXCEPTION 'knowledge preference requires an immutable revision';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_knowledge_command_complete AFTER INSERT OR UPDATE ON kb_knowledge_command_receipt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_knowledge_command_and_preference();
CREATE CONSTRAINT TRIGGER trg_knowledge_preference_complete AFTER INSERT OR UPDATE ON kb_knowledge_preference
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_knowledge_command_and_preference();

-- 周和提醒进度只能前进；终态不能重开，旧执行不能降低代次或游标。
CREATE FUNCTION protect_knowledge_work() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'knowledge work is retained';
    END IF;
    IF NEW.claim_token < OLD.claim_token OR NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'knowledge work progress is monotonic';
    END IF;
    IF TG_TABLE_NAME = 'kb_knowledge_week' THEN
        IF (NEW.week_start, NEW.due_at, NEW.close_position, NEW.created_at)
            IS DISTINCT FROM (OLD.week_start, OLD.due_at, OLD.close_position, OLD.created_at)
            OR NEW.recipient_cursor < OLD.recipient_cursor OR OLD.status = 'DONE'
            OR (OLD.status = 'READY' AND NEW.status = 'WAITING_MATCHES') THEN
            RAISE EXCEPTION 'invalid knowledge week transition';
        END IF;
    ELSE
        IF (NEW.id, NEW.user_id, NEW.kind, NEW.update_id, NEW.week_start, NEW.created_at)
            IS DISTINCT FROM (OLD.id, OLD.user_id, OLD.kind, OLD.update_id, OLD.week_start, OLD.created_at)
            OR OLD.status <> 'PENDING' THEN
            RAISE EXCEPTION 'invalid knowledge delivery transition';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_knowledge_week_guard BEFORE UPDATE OR DELETE ON kb_knowledge_week
    FOR EACH ROW EXECUTE FUNCTION protect_knowledge_work();
CREATE TRIGGER trg_knowledge_delivery_guard BEFORE UPDATE OR DELETE ON kb_knowledge_delivery
    FOR EACH ROW EXECUTE FUNCTION protect_knowledge_work();

-- 三个发布入口在已经持有文档锁的同一事务调用；此处只读取归属，不再申请资源行锁。
CREATE FUNCTION kb_knowledge_capture_publication(source_id UUID, source_position BIGINT, source_time TIMESTAMPTZ)
RETURNS INTEGER AS $$
DECLARE
    inserted INTEGER;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM kb_knowledge_order k WHERE k.singleton
        AND k.position = source_position AND k.business_time = source_time AND k.protocol_version = 1) THEN
        RAISE EXCEPTION 'publication order is not current';
    END IF;
    INSERT INTO kb_knowledge_publication(event_id, position, document_id, version_id, space_id, tag_ids, published_at, week_start)
    SELECT e.event_id, source_position, e.doc_id, e.version_id, d.space_id,
        ARRAY(SELECT r.tag_id FROM kb_document_tag_relation r WHERE r.document_id = d.id ORDER BY r.tag_id),
        source_time, kb_knowledge_week_start(source_time)
    FROM kb_collaboration_event e JOIN kb_document d ON d.id = e.doc_id
    JOIN kb_document_version v ON v.id = e.version_id AND v.doc_id = e.doc_id
    WHERE e.event_id = source_id AND e.kind = 'VERSION_PUBLISHED' AND e.occurred_at = source_time
        AND v.publication_evidence = 'PUBLISHED' AND v.published_at = source_time;
    GET DIAGNOSTICS inserted = ROW_COUNT;
    IF inserted <> 1 THEN
        RAISE EXCEPTION 'publication context was not captured';
    END IF;
    RETURN inserted;
END;
$$ LANGUAGE plpgsql;
COMMENT ON FUNCTION kb_knowledge_capture_publication(UUID, BIGINT, TIMESTAMPTZ) IS '在发布事务内固定空间标签及统一顺序，失败使发布整体回滚';

-- 约束在提交时检查完整证据，拒绝迁移后仍运行的旧发布写入者；不改造历史发布行。
CREATE FUNCTION verify_knowledge_publication() RETURNS TRIGGER AS $$
DECLARE
    target_version BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'kb_document_version' THEN
        IF OLD.publication_evidence <> 'NONE' OR NEW.publication_evidence <> 'PUBLISHED' THEN
            RETURN NEW;
        END IF;
        target_version := NEW.id;
    ELSE
        IF NEW.kind <> 'VERSION_PUBLISHED' THEN
            RETURN NEW;
        END IF;
        target_version := NEW.version_id;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM kb_knowledge_publication p
        JOIN kb_document_version v ON v.id = p.version_id AND v.doc_id = p.document_id
        JOIN kb_collaboration_event e ON e.event_id = p.event_id AND e.doc_id = p.document_id AND e.version_id = p.version_id
        WHERE p.version_id = target_version AND v.publication_evidence = 'PUBLISHED'
            AND e.kind = 'VERSION_PUBLISHED' AND v.published_at = p.published_at AND e.occurred_at = p.published_at
    ) THEN
        RAISE EXCEPTION 'publication requires knowledge subscription protocol';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_knowledge_version_complete AFTER UPDATE ON kb_document_version
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_knowledge_publication();
CREATE CONSTRAINT TRIGGER trg_knowledge_event_complete AFTER INSERT ON kb_collaboration_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_knowledge_publication();

-- NOTIFIED与收件箱必须成对提交，NO_ELIGIBLE不得产生通知，提醒类型必须与工作种类一致。
CREATE FUNCTION verify_knowledge_delivery() RETURNS TRIGGER AS $$
DECLARE
    delivery_uuid UUID;
    work kb_knowledge_delivery%ROWTYPE;
    notification_count BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'kb_user_notification' THEN
        delivery_uuid := NEW.knowledge_delivery_id;
        IF delivery_uuid IS NULL THEN
            RETURN NEW;
        END IF;
    ELSE
        delivery_uuid := NEW.id;
    END IF;
    SELECT * INTO STRICT work FROM kb_knowledge_delivery WHERE id = delivery_uuid;
    SELECT count(*) INTO notification_count FROM kb_user_notification n
        WHERE n.knowledge_delivery_id = work.id AND n.recipient_user_id = work.user_id
            AND n.type = CASE work.kind WHEN 'WEEKLY' THEN 'KNOWLEDGE_WEEKLY_DIGEST' ELSE 'SUBSCRIBED_VERSION_PUBLISHED' END;
    IF (work.status = 'NOTIFIED' AND notification_count <> 1)
        OR (work.status <> 'NOTIFIED' AND EXISTS (SELECT 1 FROM kb_user_notification WHERE knowledge_delivery_id = work.id)) THEN
        RAISE EXCEPTION 'knowledge notification and delivery must commit together';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_knowledge_delivery_complete AFTER INSERT OR UPDATE ON kb_knowledge_delivery
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_knowledge_delivery();
CREATE CONSTRAINT TRIGGER trg_knowledge_notification_complete AFTER INSERT ON kb_user_notification
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_knowledge_delivery();
