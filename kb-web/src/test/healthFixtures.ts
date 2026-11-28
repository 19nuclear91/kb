import type { HealthReport } from '../admin/healthApi';

export function healthFixture(): HealthReport {
  return { definitionVersion: 'KNOWLEDGE_HEALTH_V1', spaceId: '2', asOf: '2026-11-27T08:00:00Z', asOfDate: '2026-11-27', dayTimeZone: 'Asia/Shanghai', signal: 'ALL', limit: 20,
    counts: { stewardUnassigned: '0', stewardNeedsReassign: '0', reviewOverdue: '1', reviewDueSoon: '0', applicabilityExpired: '1', applicabilityExpiringSoon: '0' },
    affectedDocumentCount: '1', items: [{ documentId: '9223372036854775807', signals: ['REVIEW_OVERDUE', 'APPLICABILITY_EXPIRED'],
      stewardship: { state: 'OVERDUE', responsible: { userId: '7', displayName: '负责人' }, dueDate: '2026-11-01' },
      applicability: { versionId: '99', state: 'EXPIRED', effectiveFrom: null, effectiveUntil: '2026-11-26' } }], hasMore: false, nextCursor: null };
}
