package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.collaboration.mapper.CollaborationEventMapper;
import com.zhishiku.kb.app.notification.mapper.NotificationDerivationMapper;
import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import com.zhishiku.kb.app.notification.model.NotificationBacklog;
import com.zhishiku.kb.common.review.CollaborationEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 派生仓储的内存替身：按迁移脚本与 Mapper XML 约定复刻分支语义，时间由虚拟时钟推进。
 * 只用于验证服务分支，不证明真实提交原子性、行锁、SKIP LOCKED 或多实例互斥。
 */
final class InMemoryNotificationStore implements NotificationDerivationMapper {
    static final Set<String> NOTIFICATION_TYPES = Set.of("REVIEW_SUBMITTED", "REVIEW_APPROVED", "REVIEW_REJECTED",
            "COMMENT_REPLIED");
    Instant now = Instant.parse("2026-10-25T00:00:00Z");
    final Map<UUID, Event> events = new LinkedHashMap<>();
    final Map<UUID, TreeSet<Long>> recipients = new HashMap<>();
    final Set<UUID> locked = new HashSet<>();
    final List<String> calls = new ArrayList<>();
    Consumer<String> fault = ignored -> { };
    private Map<UUID, Projection> projections = new LinkedHashMap<>();
    private Map<String, Delivered> notifications = new LinkedHashMap<>();
    private long nextId = 1;

    /**
     * 源协作事实，派生过程只读。
     *
     * @param id 事件标识
     * @param kind 事件种类原文
     * @param actor 操作者
     * @param occurredAt 事实时间
     */
    record Event(UUID id, String kind, Long actor, Instant occurredAt) {
    }

    /**
     * 已生成通知。
     *
     * @param id 内部位置
     * @param notificationId 通知 UUID
     * @param eventId 源事件
     * @param recipient 收件人
     * @param type 通知类型
     * @param createdAt 生成时间
     * @param readAt 首次已读时间
     */
    record Delivered(long id, UUID notificationId, UUID eventId, long recipient, String type, Instant createdAt,
                     Instant readAt) {
    }

    static final class Projection {
        String status = "PENDING";
        String owner;
        Instant leaseUntil;
        long token;
        long cursor;
        int attempts;
        Instant nextAttemptAt;
        String errorClass;
        Instant completedAt;

        Projection copy() {
            var copy = new Projection();
            copy.status = status;
            copy.owner = owner;
            copy.leaseUntil = leaseUntil;
            copy.token = token;
            copy.cursor = cursor;
            copy.attempts = attempts;
            copy.nextAttemptAt = nextAttemptAt;
            copy.errorClass = errorClass;
            copy.completedAt = completedAt;
            return copy;
        }
    }

    /**
     * 事务快照；源事实不属于派生事务。
     *
     * @param projections 派生进度副本
     * @param notifications 通知副本
     * @param nextId 下一内部位置
     */
    record Snapshot(Map<UUID, Projection> projections, Map<String, Delivered> notifications, long nextId) {
    }

    Snapshot snapshot() {
        Map<UUID, Projection> copy = new LinkedHashMap<>();
        projections.forEach((id, value) -> copy.put(id, value.copy()));
        return new Snapshot(copy, new LinkedHashMap<>(notifications), nextId);
    }

    void restore(Snapshot snapshot) {
        projections = snapshot.projections();
        notifications = snapshot.notifications();
        nextId = snapshot.nextId();
    }

    void advanceTime(Duration duration) {
        now = now.plus(duration);
    }

    /** 以真实 CollaborationEventService 写入的源事实协议。 */
    CollaborationEventMapper collaborationMapper() {
        Set<String> keys = new HashSet<>();
        return new CollaborationEventMapper() {
            @Override
            public int append(CollaborationEvent event) {
                if (!keys.add(event.businessKey())) {
                    return 0;
                }
                events.put(event.eventId(), new Event(event.eventId(), event.kind().name(), event.actorUserId(),
                        event.occurredAt()));
                recipients.put(event.eventId(), new TreeSet<>());
                return 1;
            }

            @Override
            public int recipient(UUID eventId, long userId) {
                return recipients.get(eventId).add(userId) ? 1 : 0;
            }

            @Override
            public int startCoverage(Instant ignored) {
                return 0;
            }

            @Override
            public int startCommentCoverage(Instant ignored) {
                return 0;
            }
        };
    }

    /** 直接写入尚无 Java 枚举的源种类（例如后续评论生产者）。 */
    UUID addRaw(String kind, Long actor, Instant occurredAt, Long... users) {
        UUID id = UUID.randomUUID();
        events.put(id, new Event(id, kind, actor, occurredAt));
        recipients.put(id, new TreeSet<>(List.of(users)));
        return id;
    }

    void read(UUID eventId, long user) {
        var key = key(eventId, user);
        var value = notifications.get(key);
        if (value != null && value.readAt() == null) {
            notifications.put(key, new Delivered(value.id(), value.notificationId(), value.eventId(), value.recipient(),
                    value.type(), value.createdAt(), now));
        }
    }

    /** 模拟 projection 丢失（例如由较旧备份恢复）；数据库触发器正常情况下拒绝删除。 */
    void loseProjection(UUID eventId) {
        projections.remove(eventId);
    }

    Projection projection(UUID eventId) {
        return projections.get(eventId);
    }

    List<Delivered> delivered() {
        return List.copyOf(notifications.values());
    }

    List<Delivered> delivered(UUID eventId) {
        return notifications.values().stream().filter(item -> item.eventId().equals(eventId)).toList();
    }

    @Override
    public int discover(int limit) {
        calls.add("discover");
        fault.accept("discover");
        var candidates = events.values().stream().filter(event -> !projections.containsKey(event.id()))
                .sorted(Comparator.comparing(Event::occurredAt).thenComparing(Event::id)).limit(limit).toList();
        for (var event : candidates) {
            var projection = new Projection();
            projection.nextAttemptAt = now;
            projections.put(event.id(), projection);
        }
        return candidates.size();
    }

    @Override
    public List<ClaimedEvent> claim(String owner, int limit, int leaseSeconds) {
        calls.add("claim");
        fault.accept("claim");
        var candidates = projections.entrySet().stream()
                .filter(entry -> !"DONE".equals(entry.getValue().status) && !entry.getValue().nextAttemptAt.isAfter(now)
                        && (entry.getValue().leaseUntil == null || entry.getValue().leaseUntil.isBefore(now))
                        && !locked.contains(entry.getKey()))
                .sorted(Comparator.<Map.Entry<UUID, Projection>, Instant>comparing(entry -> entry.getValue().nextAttemptAt)
                        .thenComparing(Map.Entry::getKey))
                .limit(limit).toList();
        List<ClaimedEvent> claimed = new ArrayList<>();
        for (var entry : candidates) {
            var p = entry.getValue();
            boolean takeover = p.owner != null;
            p.owner = owner;
            p.leaseUntil = now.plusSeconds(leaseSeconds);
            p.token++;
            p.status = "PENDING";
            claimed.add(new ClaimedEvent(entry.getKey(), events.get(entry.getKey()).kind(), p.token, p.cursor, p.attempts,
                    takeover));
        }
        return claimed;
    }

    @Override
    public List<Long> recipients(UUID eventId, long afterUserId, int limit) {
        calls.add("recipients");
        fault.accept("recipients");
        Long actor = events.get(eventId).actor();
        return recipients.get(eventId).tailSet(afterUserId, false).stream()
                .filter(id -> actor == null || id.longValue() != actor).limit(limit).toList();
    }

    @Override
    public int advance(UUID eventId, String owner, long token, long cursor, int leaseSeconds) {
        calls.add("advance");
        fault.accept("advance");
        var p = projections.get(eventId);
        if (!held(p, owner, token) || p.cursor >= cursor) {
            return 0;
        }
        p.cursor = cursor;
        p.leaseUntil = now.plusSeconds(leaseSeconds);
        return 1;
    }

    @Override
    public int deliver(UUID eventId, String type, List<Long> users) {
        calls.add("deliver");
        fault.accept("deliver");
        if (!NOTIFICATION_TYPES.contains(type)) {
            throw new IllegalStateException("type check constraint");
        }
        if (!events.get(eventId).kind().equals(type)) {
            return 0;
        }
        int inserted = 0;
        for (long user : users.stream().sorted().toList()) {
            if (!recipients.get(eventId).contains(user)) {
                continue;
            }
            String key = key(eventId, user);
            if (!notifications.containsKey(key)) {
                notifications.put(key, new Delivered(nextId++, UUID.randomUUID(), eventId, user, type, now, null));
                inserted++;
            }
        }
        return inserted;
    }

    @Override
    public int complete(UUID eventId, String owner, long token) {
        calls.add("complete");
        fault.accept("complete");
        var p = projections.get(eventId);
        if (!held(p, owner, token)) {
            return 0;
        }
        p.status = "DONE";
        p.owner = null;
        p.leaseUntil = null;
        p.errorClass = null;
        p.completedAt = now;
        return 1;
    }

    @Override
    public int release(UUID eventId, String owner, long token) {
        calls.add("release");
        fault.accept("release");
        var p = projections.get(eventId);
        if (!held(p, owner, token)) {
            return 0;
        }
        p.owner = null;
        p.leaseUntil = null;
        p.nextAttemptAt = now;
        return 1;
    }

    @Override
    public int postpone(UUID eventId, String owner, long token, String status, String errorClass, long delaySeconds) {
        calls.add("postpone");
        fault.accept("postpone");
        var p = projections.get(eventId);
        if (!held(p, owner, token)) {
            return 0;
        }
        p.status = status;
        p.errorClass = errorClass;
        p.attempts++;
        p.owner = null;
        p.leaseUntil = null;
        p.nextAttemptAt = now.plusSeconds(delaySeconds);
        return 1;
    }

    @Override
    public NotificationBacklog backlog() {
        calls.add("backlog");
        fault.accept("backlog");
        long pending = projections.values().stream().filter(p -> "PENDING".equals(p.status)).count();
        long waiting = projections.values().stream().filter(p -> "WAITING_SOURCE".equals(p.status)).count();
        long oldest = projections.entrySet().stream().filter(entry -> !"DONE".equals(entry.getValue().status))
                .map(entry -> events.get(entry.getKey()).occurredAt()).min(Comparator.naturalOrder())
                .map(time -> Math.max(0, Duration.between(time, now).toSeconds())).orElse(0L);
        return new NotificationBacklog(pending, waiting, oldest);
    }

    private static boolean held(Projection p, String owner, long token) {
        return p != null && Objects.equals(p.owner, owner) && p.token == token && "PENDING".equals(p.status);
    }

    private static String key(UUID eventId, long user) {
        return eventId + ":" + user;
    }
}
