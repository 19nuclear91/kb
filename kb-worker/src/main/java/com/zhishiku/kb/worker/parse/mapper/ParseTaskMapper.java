package com.zhishiku.kb.worker.parse.mapper;

import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 解析任务队列的数据访问。
 *
 * <p>队列跑在 PG 里而不是 MQ 上，理由见《架构决策记录.md》第 7 章"队列二选一"：
 * 任务状态与业务数据同库同事务，不会出现"消息发出去了但业务没提交"
 * 这类两阶段不一致；代价是要自己实现领取、租约与重投。
 * 这个选择<b>尚未定稿</b>——量级上来后可能换 RabbitMQ，
 * 届时这个接口就是替换边界。
 */
@Mapper
public interface ParseTaskMapper {

    /**
     * 原子领取一批任务。
     *
     * <p>这段 SQL 的每一处都是有意为之，改动前请读完：
     *
     * <ul>
     * <li><b>{@code FOR UPDATE SKIP LOCKED}</b>：多个 Worker 并发领取时，
     *     后来者跳过已被锁住的行而不是排队等待。没有 SKIP LOCKED，
     *     N 个 Worker 会在同一行上串行阻塞，并发度退化成 1。
     *
     * <li><b>子查询 + 外层 UPDATE</b>：锁必须在子查询里取，外层再更新。
     *     直接 {@code UPDATE ... LIMIT} 在 PG 里不成立。
     *
     * <li><b>领取时即 {@code attempts + 1}</b>，而不是失败时才加。
     *     若改成失败时加，Worker 进程被 kill、OOM、或机器断电时
     *     没人来写失败记录，租约到期后任务被重投，attempts 永远是 0,
     *     于是同一个必然失败的脏文件会被无限重试，
     *     把 Worker 集群整个占住。领取即计数意味着
     *     "崩溃也算一次尝试"，这是唯一能收敛的语义。
     *
     * <li><b>{@code attempts < max_attempts}</b> 写在领取条件里，
     *     超限任务不会被捞起来，由巡检把它标 failed（见 ParseTaskRecoveryService）。
     *
     * <li><b>租约回收条件 {@code lease_until < now()}</b>：
     *     status='running' 但租约已过期，说明上一个持有者没能完成。
     *     租约长度必须大于最慢文档的解析耗时，否则会出现
     *     "还在正常解析就被别人抢走"的重复处理。
     *
     * <li><b>{@code ORDER BY id}</b>：近似 FIFO，避免大批量导入时
     *     早提交的任务被一直插队饿死。
     * </ul>
     *
     * @param owner        持有者标识（主机名+PID），仅用于排障时定位是哪个实例卡住了
     * @param leaseSeconds 租约时长（秒）
     * @param limit        本次领取上限
     */
    @Select("""
            WITH claimed AS (
                UPDATE kb_parse_task t
                   SET status      = 'running',
                       attempts    = t.attempts + 1,
                       claim_token = t.claim_token + 1,
                       embedding_profile_id = #{profileId},
                       embedding_epoch = #{epoch},
                       lease_until = now() + make_interval(secs => #{leaseSeconds}),
                       lease_owner = #{owner},
                       updated_at  = now()
                 WHERE t.id IN (
                         SELECT c.id
                          FROM kb_parse_task c
                         WHERE c.attempts < c.max_attempts
                            AND ( (c.status = 'pending'
                                   AND (c.next_attempt_at IS NULL OR c.next_attempt_at <= now()))
                                  OR (c.status = 'running' AND c.lease_until < now()) )
                          ORDER BY c.id
                          LIMIT #{limit}
                          FOR UPDATE SKIP LOCKED
                       )
            RETURNING t.id, t.version_id, t.attempts, t.max_attempts, t.lease_until, t.lease_owner,
                      t.next_attempt_at, t.failure_class, t.failure_code, t.failure_summary,
                      t.last_failure_at, t.execution_generation,
                      t.embedding_profile_id, t.embedding_epoch, t.claim_token
            )
            SELECT c.id            AS id,
                   c.version_id    AS version_id,
                   c.attempts      AS attempts,
                   c.max_attempts  AS max_attempts,
                   c.lease_until   AS lease_until,
                   c.lease_owner   AS lease_owner,
                   c.next_attempt_at AS next_attempt_at,
                   c.failure_class AS failure_class,
                   c.failure_code AS failure_code,
                   c.failure_summary AS failure_summary,
                   c.last_failure_at AS last_failure_at,
                   c.execution_generation AS execution_generation,
                   v.object_key    AS object_key,
                   v.file_name     AS file_name,
                   v.file_size     AS file_size,
                   v.mime_type     AS mime_type,
                   c.embedding_profile_id AS embedding_profile_id,
                   p.fingerprint AS embedding_fingerprint,
                   c.embedding_epoch AS embedding_epoch,
                   c.claim_token AS claim_token
              FROM claimed c
              JOIN kb_document_version v ON v.id = c.version_id
              JOIN kb_embedding_profile p ON p.profile_id = c.embedding_profile_id
             ORDER BY c.id
            """)
    List<ParseTask> claim(@Param("owner") String owner,
                          @Param("leaseSeconds") int leaseSeconds,
                          @Param("limit") int limit,
                          @Param("profileId") String profileId,
                          @Param("epoch") long epoch);

    /**
     * 同步文档版本的索引状态，供前台展示。与任务状态分开是因为二者生命周期不同。
     */
    @Update("""
            UPDATE kb_document_version
               SET index_status = #{status},
                   index_error  = left(#{error}, 2000)
             WHERE id = #{versionId}
            """)
    int updateVersionIndexStatus(@Param("versionId") long versionId,
                                 @Param("status") String status,
                                 @Param("error") String error);

    /**
     * 队列积压量，供健康检查与容量评估。
     */
    @Select("""
            SELECT count(*)
              FROM kb_parse_task
             WHERE status = 'pending'
               AND attempts < max_attempts
               AND (next_attempt_at IS NULL OR next_attempt_at <= now())
            """)
    long countBacklog();

    /**
     * 当前未解决死信数量，供 Worker gauge 使用。
     */
    @Select("SELECT count(*) FROM kb_parse_task_dead_letter WHERE status = 'OPEN'")
    long countOpenDeadLetters();
}
