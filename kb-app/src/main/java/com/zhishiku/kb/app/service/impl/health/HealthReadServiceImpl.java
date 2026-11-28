package com.zhishiku.kb.app.service.impl.health;

import com.zhishiku.kb.app.document.applicability.ValidityProperties;
import com.zhishiku.kb.app.document.applicability.mapper.BusinessClockMapper;
import com.zhishiku.kb.app.domain.dto.health.HealthSelection;
import com.zhishiku.kb.app.domain.req.health.HealthQuery;
import com.zhishiku.kb.app.domain.vo.health.KnowledgeHealthRadarVo;
import com.zhishiku.kb.app.enums.health.HealthSignal;
import com.zhishiku.kb.app.mapper.health.HealthMapper;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.service.health.HealthFailure;
import com.zhishiku.kb.app.service.health.HealthReadService;
import com.zhishiku.kb.common.api.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 授权、统计和当前页共享一份只读快照，不缓存跨请求资格或业务结果。 */
@Service
@RequiredArgsConstructor
public class HealthReadServiceImpl implements HealthReadService {
    private final HealthAccess access;
    private final HealthMapper mapper;
    private final BusinessClockMapper clock;
    private final ValidityProperties properties;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 6)
    public KnowledgeHealthRadarVo read(long spaceId, HealthQuery query) {
        try (var budget = new HealthBudget()) {
            if (spaceId <= 0) {
                throw HealthFailure.invalid();
            }
            HealthQueries.validate(query);
            var scope = access.require(spaceId);
            var instant = clock.now();
            if (instant == null) {
                throw HealthFailure.unavailable();
            }
            var today = instant.atZoneSameInstant(properties.zoneId()).toLocalDate();
            Long after = HealthCursor.decode(scope.userId(), spaceId, query, today);
            var selection = new HealthSelection(spaceId, today, scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                    scope.clearanceLevel(), query.signal(), after, query.limit() + 1);
            var totals = mapper.totals(selection);
            var counts = HealthProjection.counts(totals);
            budget.remainingMillis();
            var rows = mapper.page(selection);
            if (rows == null || rows.size() > query.limit() + 1
                    || BigInteger.valueOf(rows.size()).compareTo(HealthProjection.number(totals.affectedDocumentCount())) > 0) {
                throw HealthFailure.unavailable();
            }
            long previous = after == null ? 0 : after;
            var projected = new ArrayList<KnowledgeHealthRadarVo.Item>();
            for (var row : rows) {
                if (row.documentId() <= previous) {
                    throw HealthFailure.unavailable();
                }
                var item = HealthProjection.item(row, today);
                if (!"ALL".equals(query.signal()) && !item.signals().contains(HealthSignal.valueOf(query.signal()))) {
                    throw HealthFailure.unavailable();
                }
                projected.add(item);
                previous = row.documentId();
            }
            var categoryCounts = List.of(totals.stewardUnassigned(), totals.stewardNeedsReassign(), totals.reviewOverdue(),
                    totals.reviewDueSoon(), totals.applicabilityExpired(), totals.applicabilityExpiringSoon());
            for (var signal : HealthSignal.values()) {
                long observed = projected.stream().filter(item -> item.signals().contains(signal)).count();
                if (BigInteger.valueOf(observed).compareTo(HealthProjection.number(categoryCounts.get(signal.ordinal()))) > 0) {
                    throw HealthFailure.unavailable();
                }
            }
            BigInteger selectedCount = HealthProjection.number("ALL".equals(query.signal()) ? totals.affectedDocumentCount()
                    : categoryCounts.get(HealthSignal.valueOf(query.signal()).ordinal()));
            if (after == null && rows.size() != selectedCount.min(BigInteger.valueOf(query.limit() + 1)).intValueExact()) {
                throw HealthFailure.unavailable();
            }
            boolean more = projected.size() > query.limit();
            var page = List.copyOf(projected.subList(0, Math.min(query.limit(), projected.size())));
            String next = more ? HealthCursor.encode(scope.userId(), spaceId, query, today,
                    Long.parseLong(page.getLast().documentId())) : null;
            budget.remainingMillis();
            return new KnowledgeHealthRadarVo("KNOWLEDGE_HEALTH_V1", Long.toString(spaceId), instant, today,
                    properties.businessTimeZone(), query.signal(), query.limit(), counts, totals.affectedDocumentCount(), page, more, next);
        } catch (HealthFailure | QueryException | ApiException expected) {
            throw expected;
        } catch (RuntimeException ignored) {
            throw HealthFailure.unavailable();
        }
    }
}
