package com.zhishiku.kb.app.storage.service;

import com.zhishiku.kb.app.storage.ListObjectsPage;
import com.zhishiku.kb.app.storage.ObjectStorage;
import com.zhishiku.kb.app.storage.StorageReconciliationProperties;
import com.zhishiku.kb.app.storage.StorageReconciliationReport;
import com.zhishiku.kb.app.storage.StoredObject;
import com.zhishiku.kb.app.storage.mapper.StorageReconciliationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对象存储对账的轮次编排：以桶列举为权威方向识别孤儿对象，并按护栏条件删除。
 *
 * <p>孤儿定义为“桶中对象键减去存活引用”。上传侧孤儿（写库失败、进程崩溃）在数据库
 * 中没有任何痕迹，只能从桶方向发现；删除侧孤儿（软删除后对象删除失败）的文档已被
 * 标记 {@code deleted}，也被同一存活谓词覆盖。存活引用与文档状态、空间是否归档、
 * 是否为当前版本均无关：它们仍可被下载、预览与引用，误删即数据丢失。
 *
 * <p>删除默认关闭（报告模式）。开启后每个候选仍须依次通过：键在扫描前缀内、
 * {@code LastModified} 早于宽限期、删除前复核不再是存活引用；列举或数据库访问失败
 * 即中止本轮且不做任何删除；存活引用整体为空时拒绝删除并记高优先级告警。单个对象
 * 删除失败只记 WARN 并继续，失败对象由下一轮自然重试。数据库侧全程只读。
 *
 * <p>缺失检测按版本 ID 倒序抽样最近若干条存活引用执行只读 HEAD：{@code NotFound}
 * 计缺失、其他异常计检查失败，与缺失分开；不写库、不重建、不触发重解析。
 *
 * <p>每轮以 INFO 打印孤儿键（每轮至多 100 条）与一条汇总行：扫描数、孤儿数、删除数、
 * 删除失败数、缺失数、检查失败数。
 *
 * <p>单飞用专用连接的会话级 {@code pg_try_advisory_lock(741306012)}（与既有
 * 741306011 并列登记），整轮持有、finally 显式解锁后归还连接池；连接断开时锁随
 * 会话终止自动释放。锁语句不经 MyBatis：映射器每条语句可能落在不同池连接上，
 * 无法保证同一会话。
 */
@Service
public class StorageReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciliationService.class);

    /**
     * 单飞 advisory lock 编号，会话级；固定不变，避免多实例各自加锁。
     */
    private static final long ADVISORY_LOCK_KEY = 741306012L;

    private static final String LOCK_SQL = "SELECT pg_try_advisory_lock(" + ADVISORY_LOCK_KEY + ")";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")";

    /**
     * 比对分页大小：每页一次 IN 查询，不在内存中保留全桶键集合。
     */
    private static final int PAGE_SIZE = 1000;

    /**
     * 日志键上限：孤儿键每轮至多打印 100 条。
     */
    private static final int LOG_KEY_LIMIT = 100;

    private final ObjectStorage storage;
    private final StorageReconciliationMapper mapper;
    private final DataSource dataSource;
    private final StorageReconciliationProperties properties;
    private final Clock clock;

    @Autowired
    public StorageReconciliationService(ObjectStorage storage, StorageReconciliationMapper mapper,
                                        DataSource dataSource, StorageReconciliationProperties properties) {
        this(storage, mapper, dataSource, properties, Clock.systemUTC());
    }

    /**
     * 供单元测试注入固定时钟；生产装配走 {@code @Autowired} 构造器。
     */
    public StorageReconciliationService(ObjectStorage storage, StorageReconciliationMapper mapper,
                                        DataSource dataSource, StorageReconciliationProperties properties,
                                        Clock clock) {
        this.storage = storage;
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 执行一轮对账；任何失败都收敛为本轮结果，不向调用方抛出。
     *
     * @return 本轮计数；未获单飞锁或连接级失败时为零值报告
     */
    public StorageReconciliationReport runCycle() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection)) {
                log.info("对账单飞锁被其他实例持有，本轮跳过");
                return StorageReconciliationReport.empty();
            }
            try {
                return reconcile();
            } finally {
                unlockQuietly(connection);
            }
        } catch (SQLException e) {
            log.error("对账无法获取数据库连接或单飞锁，本轮跳过", e);
            return StorageReconciliationReport.empty();
        }
    }

    private StorageReconciliationReport reconcile() {
        boolean liveEmpty;
        try {
            liveEmpty = !mapper.hasLiveReference();
        } catch (RuntimeException e) {
            log.error("存活引用探测失败，本轮跳过", e);
            return StorageReconciliationReport.empty();
        }

        long scanned = 0;
        long orphans = 0;
        List<StoredObject> candidates = new ArrayList<>();
        List<String> orphanLogKeys = new ArrayList<>();
        try {
            String cursor = null;
            do {
                ListObjectsPage page = storage.list(properties.prefix(), cursor, PAGE_SIZE);
                List<StoredObject> inPrefix = new ArrayList<>();
                for (StoredObject object : page.objects()) {
                    scanned++;
                    if (object.key().startsWith(properties.prefix())) {
                        inPrefix.add(object);
                    } else {
                        log.warn("列举返回了扫描前缀之外的对象，跳过: key={}", object.key());
                    }
                }
                if (!inPrefix.isEmpty()) {
                    orphans += collectPageOrphans(inPrefix, candidates, orphanLogKeys);
                }
                cursor = page.nextCursor();
            } while (cursor != null);
        } catch (RuntimeException e) {
            log.error("对账扫描失败，本轮中止且不执行任何删除（已扫描 {}，孤儿 {}）", scanned, orphans, e);
            return new StorageReconciliationReport(scanned, orphans, 0, 0, 0, 0);
        }

        boolean refuseDeletes = liveEmpty && orphans > 0;
        if (refuseDeletes) {
            log.warn("存活引用为空但桶中存在 {} 个孤儿对象，疑似连接到错误数据库，本轮拒绝任何删除", orphans);
        }
        long deleted = 0;
        long deleteFailed = 0;
        if (properties.deleteEnabled() && !refuseDeletes) {
            for (StoredObject candidate : candidates) {
                if (deleted + deleteFailed >= properties.maxDeletesPerCycle()) {
                    break;
                }
                List<String> rechecked;
                try {
                    rechecked = mapper.listLiveObjectKeys(List.of(candidate.key()));
                } catch (RuntimeException e) {
                    log.error("删除前复核查询失败，本轮停止后续删除（已删除 {} 个）", deleted, e);
                    break;
                }
                if (!rechecked.isEmpty()) {
                    continue;
                }
                try {
                    storage.delete(candidate.key());
                    deleted++;
                } catch (RuntimeException e) {
                    deleteFailed++;
                    log.warn("删除孤儿对象失败，由下一轮自然重试: key={}", candidate.key(), e);
                }
            }
        }
        long missing = 0;
        long missingCheckFailed = 0;
        if (properties.missingCheckEnabled() && properties.missingCheckMaxPerCycle() > 0) {
            try {
                for (String key : mapper.sampleLiveObjectKeys(properties.missingCheckMaxPerCycle())) {
                    try {
                        if (!storage.objectExists(key)) {
                            missing++;
                            log.warn("存活引用的对象在桶中缺失: key={}", key);
                        }
                    } catch (RuntimeException e) {
                        missingCheckFailed++;
                        log.warn("缺失检测 HEAD 失败，计入检查失败: key={}", key, e);
                    }
                }
            } catch (RuntimeException e) {
                log.error("缺失抽样查询失败，本轮跳过缺失检测", e);
            }
        }
        for (String key : orphanLogKeys) {
            log.info("孤儿对象: key={}", key);
        }
        log.info("对账轮次完成: 扫描={} 孤儿={} 删除={} 删除失败={} 缺失={} 检查失败={}",
                scanned, orphans, deleted, deleteFailed, missing, missingCheckFailed);
        return new StorageReconciliationReport(scanned, orphans, deleted, deleteFailed, missing, missingCheckFailed);
    }

    /**
     * 单页 IN 比对：返回该页孤儿数，并按上限保留删除候选与日志键。
     */
    private long collectPageOrphans(List<StoredObject> inPrefix, List<StoredObject> candidates,
                                    List<String> orphanLogKeys) {
        Set<String> live = new HashSet<>(mapper.listLiveObjectKeys(
                inPrefix.stream().map(StoredObject::key).toList()));
        long orphans = 0;
        for (StoredObject object : inPrefix) {
            if (live.contains(object.key())) {
                continue;
            }
            orphans++;
            if (orphanLogKeys.size() < LOG_KEY_LIMIT) {
                orphanLogKeys.add(object.key());
            }
            if (properties.deleteEnabled() && isExpired(object)
                    && candidates.size() < properties.maxDeletesPerCycle()) {
                candidates.add(object);
            }
        }
        return orphans;
    }

    /**
     * 宽限期判定：LastModified 缺失时不作为删除候选。
     */
    private boolean isExpired(StoredObject object) {
        Instant lastModified = object.lastModified();
        if (lastModified == null) {
            return false;
        }
        return lastModified.isBefore(clock.instant().minusSeconds(properties.orphanGraceSeconds()));
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL);
             ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }

    /**
     * 解锁失败不改变本轮结果：锁随会话终止释放，最坏下一轮推迟一个周期。
     */
    private static void unlockQuietly(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.executeQuery();
        } catch (SQLException e) {
            log.warn("释放对账单飞锁失败；连接归还池后锁残留，直至该会话终止", e);
        }
    }
}
