package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 持久化告警投递边界；发送失败保留待投递状态并可再次尝试。
 */
@Service
public class TaskAlertOutbox {

    private static final Logger log = LoggerFactory.getLogger(TaskAlertOutbox.class);

    private final JdbcTemplate jdbc;
    private final TaskFailureAlert alert;
    private final ParseTaskMetrics metrics;

    public TaskAlertOutbox(JdbcTemplate jdbc, TaskFailureAlert alert) {
        this(jdbc, alert, new ParseTaskMetrics());
    }

    @Autowired
    public TaskAlertOutbox(JdbcTemplate jdbc, TaskFailureAlert alert, ParseTaskMetrics metrics) {
        this.jdbc = jdbc;
        this.alert = alert;
        this.metrics = metrics;
    }

    @Transactional
    public void enqueue(ParseTask task, String eventType,
                        FailureClassifier.Classification classification,
                        String dedupeKey, boolean exhausted) {
        jdbc.update("""
                        INSERT INTO kb_parse_alert_delivery
                            (task_id, version_id, execution_generation, event_type,
                             failure_class, failure_code, error_summary, dedupe_key)
                        VALUES (?, ?, ?, ?, ?, ?, left(?, 2000), ?)
                        ON CONFLICT (dedupe_key) DO NOTHING
                        """, task.id(), task.versionId(), generation(task), eventType,
                classification.metricClass(), classification.code(), classification.summary(), dedupeKey);
    }

    /**
     * 由调度线程执行；实际生产适配器可替换默认日志实现。
     */
    @Scheduled(fixedDelayString = "${kb.worker.alert-dispatch-interval-ms:5000}")
    @Transactional
    public void dispatch() {
        List<Delivery> deliveries = jdbc.query("""
                SELECT id, task_id, version_id, execution_generation,
                       event_type, failure_class, failure_code, error_summary
                  FROM kb_parse_alert_delivery
                 WHERE status <> 'SENT' AND next_attempt_at <= now()
                 ORDER BY id
                 LIMIT 50
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new Delivery(rs.getLong("id"), rs.getLong("task_id"),
                rs.getLong("version_id"), rs.getLong("execution_generation"),
                rs.getString("event_type"), rs.getString("failure_class"),
                rs.getString("failure_code"), rs.getString("error_summary")));
        for (Delivery delivery : deliveries) {
            try {
                ParseTask task = new ParseTask(delivery.taskId(), delivery.versionId(), 0, 0,
                        null, null, null, null, null, null, null,
                        delivery.generation(), null, null, null, null);
                alert.onFailure(task, delivery.summary(), "DEAD_LETTERED".equals(delivery.eventType()));
                jdbc.update("""
                        UPDATE kb_parse_alert_delivery
                           SET status = 'SENT', sent_at = now(), last_error = NULL
                         WHERE id = ?
                        """, delivery.id());
            } catch (RuntimeException failure) {
                log.error("解析告警投递失败 deliveryId={}", delivery.id(), failure);
                metrics.alertFailed();
                jdbc.update("""
                        UPDATE kb_parse_alert_delivery
                           SET status = 'PENDING', attempts = attempts + 1,
                               next_attempt_at = now() + make_interval(secs => LEAST(900, 30 * (attempts + 1))),
                               last_error = left(?, 2000)
                         WHERE id = ?
                        """, FailureClassifier.summarize(failure), delivery.id());
            }
        }
    }

    /**
     * 队列老化只产生一个时间窗口内的事件，避免每次采样重复告警。
     */
    @Scheduled(fixedDelayString = "${kb.worker.alert-aging-interval-ms:30000}")
    @Transactional
    public void detectQueueAging() {
        List<ParseTask> aged = jdbc.query("""
                        SELECT id, version_id, attempts, max_attempts, execution_generation
                          FROM kb_parse_task
                         WHERE status = 'pending'
                           AND created_at < now() - make_interval(secs => ?)
                         ORDER BY created_at
                         LIMIT 20
                        """, (rs, rowNum) -> new ParseTask(rs.getLong("id"), rs.getLong("version_id"),
                        rs.getInt("attempts"), rs.getInt("max_attempts"), null, null,
                        null, null, "QUEUE_AGING", "解析队列等待时间过长", null,
                        rs.getLong("execution_generation"), null, null, null, null),
                900L);
        FailureClassifier.Classification aging = new FailureClassifier.Classification(
                FailureClassifier.FailureClass.RETRYABLE, "QUEUE_AGING", "解析队列等待时间过长", false);
        for (ParseTask task : aged) {
            String dedupe = "queue-aging:" + task.id() + ":" + generation(task) + ":"
                    + LocalDate.now(ZoneOffset.UTC);
            enqueue(task, "QUEUE_AGING", aging, dedupe, false);
        }
    }

    private static long generation(ParseTask task) {
        return task.executionGeneration() == null ? 0L : task.executionGeneration();
    }

    /**
     * 待投递的告警记录投影。
     *
     * @param id           投递记录 ID
     * @param taskId       关联的解析任务 ID
     * @param versionId    关联的文档版本 ID
     * @param generation   任务执行代数，用于区分重新入队的同一任务
     * @param eventType    事件类型，例如 DEAD_LETTERED 或 QUEUE_AGING
     * @param failureClass 失败分类的指标标签
     * @param failureCode  失败分类的错误码
     * @param summary      失败摘要，入库时已截断到 2000 字符
     */
    record Delivery(long id, long taskId, long versionId, long generation,
                    String eventType, String failureClass, String failureCode,
                    String summary) {
    }
}
