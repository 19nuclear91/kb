package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.NotificationProperties;
import com.zhishiku.kb.app.notification.mapper.NotificationDerivationMapper;
import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import com.zhishiku.kb.app.notification.model.NotificationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 派生的短事务边界：只执行数据库写入，不调用 HTTP、源业务或其他模块写服务。
 * 每批在同一事务内先推进游标再写通知，token 不匹配即整体回滚。
 */
@Service
public class NotificationDerivationTransaction {
    private final NotificationDerivationMapper mapper;
    private final NotificationProperties properties;

    public NotificationDerivationTransaction(NotificationDerivationMapper mapper, NotificationProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional(timeout = NotificationProperties.BATCH_TIMEOUT_SECONDS)
    public int discover() {
        return mapper.discover(properties.getDiscoverLimit());
    }

    @Transactional(timeout = NotificationProperties.BATCH_TIMEOUT_SECONDS)
    public List<ClaimedEvent> claim(String owner) {
        var claimed = mapper.claim(owner, properties.getClaimLimit(), properties.getLeaseSeconds());
        if (claimed == null || claimed.size() > properties.getClaimLimit())
            throw new IllegalStateException("Notification claim is unavailable");
        return claimed;
    }

    /**
     * 处理一个收件人批次。
     *
     * @param type 源事件对应的固定通知类型
     * @param cursor 本次执行已提交的收件人位置
     * @return 新游标；事件已完成时 done 为真
     */
    @Transactional(timeout = NotificationProperties.BATCH_TIMEOUT_SECONDS)
    public Batch batch(ClaimedEvent claim, String owner, NotificationType type, long cursor) {
        int size = properties.getRecipientBatchSize();
        var recipients = mapper.recipients(claim.eventId(), cursor, size);
        if (recipients == null || recipients.size() > size) throw new IllegalStateException("Recipient batch is unavailable");
        if (recipients.isEmpty()) {
            if (mapper.complete(claim.eventId(), owner, claim.claimToken()) != 1) throw new NotificationLeaseLost();
            return new Batch(cursor, true);
        }
        long last = cursor;
        for (long id : recipients) {
            if (id <= last) throw new IllegalStateException("Recipient batch is not ordered");
            last = id;
        }
        // 先在 projection 行上取得行锁并校验 token，再写通知；接管者推进 token 后旧执行在此失败。
        if (mapper.advance(claim.eventId(), owner, claim.claimToken(), last, properties.getLeaseSeconds()) != 1)
            throw new NotificationLeaseLost();
        int written = mapper.deliver(claim.eventId(), type.name(), recipients);
        if (written < 0 || written > recipients.size()) throw new IllegalStateException("Notification batch was not saved");
        if (recipients.size() < size) {
            if (mapper.complete(claim.eventId(), owner, claim.claimToken()) != 1) throw new NotificationLeaseLost();
            return new Batch(last, true);
        }
        return new Batch(last, false);
    }

    /** 不产生提醒的源事件直接完成，不生成通知。 */
    @Transactional(timeout = NotificationProperties.BATCH_TIMEOUT_SECONDS)
    public void ignore(ClaimedEvent claim, String owner) {
        if (mapper.complete(claim.eventId(), owner, claim.claimToken()) != 1) throw new NotificationLeaseLost();
    }

    @Transactional(timeout = NotificationProperties.BATCH_TIMEOUT_SECONDS)
    public void release(ClaimedEvent claim, String owner) {
        if (mapper.release(claim.eventId(), owner, claim.claimToken()) != 1) throw new NotificationLeaseLost();
    }

    /**
     * 退避重新排期；不改变源业务，不把失败视作成功空收件人。
     *
     * @param waitingSource 真表示类型尚无解析器，保持 WAITING_SOURCE
     */
    @Transactional(timeout = NotificationProperties.BATCH_TIMEOUT_SECONDS)
    public void postpone(ClaimedEvent claim, String owner, boolean waitingSource) {
        long delay = properties.backoffSeconds(claim.attempts());
        if (mapper.postpone(claim.eventId(), owner, claim.claimToken(), waitingSource ? "WAITING_SOURCE" : "PENDING",
                waitingSource ? "SOURCE_NOT_READY" : "DERIVATION_FAILED", delay) != 1) throw new NotificationLeaseLost();
    }

    /**
     * 单批结果。
     *
     * @param cursor 已提交的收件人位置
     * @param done 事件是否已完成
     */
    public record Batch(long cursor, boolean done) {
    }
}
