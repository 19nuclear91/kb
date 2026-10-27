import { metricLabels, type AiEvent, type AiMetadata, type AiOverview, type AiPage, type Metrics } from '../admin/aiUsageApi';
export const aiMeta: AiMetadata = { from: '2026-10-17T00:00:00.123456Z', to: '2026-10-24T00:00:00.123456Z', asOf: '2026-10-24T00:00:00.123456Z',
  dayTimeZone: 'UTC', collectionEnabled: true, collectionStatus: 'ENABLED', retentionDays: '180', coverageStartedAt: '2026-10-17T00:00:00Z',
  coverageIntervals: [{ from: '2026-10-17T00:00:00Z', to: '2026-10-24T00:00:00.123456Z' }], coverageIntervalsTruncated: false, historyMayBeIncomplete: false };
export const aiMetrics = (): Metrics => ({ ...Object.fromEntries(Object.keys(metricLabels).map(key => [key, '0'])), callCount: '2', knownCount: '1', unknownCount: '1',
  promptTokens: '10', completionTokens: '5', plannedChargedTokens: '12015', settledChargedTokens: '15' } as Metrics);
export const aiOverviewData = (): AiOverview => ({ ...aiMeta, metrics: aiMetrics() });
export const aiEvent = (number = 1): AiEvent => ({ id: String(10 - number), eventId: `${number}2345678-1234-1234-1234-123456789012`,
  callId: '92345678-1234-1234-1234-123456789012', eventType: number === 1 ? 'FINALIZED' : 'SETTLED', occurredAt: '2026-10-20T00:00:00Z',
  recordedAt: aiMeta.asOf, snapshotAvailable: true, snapshot: { callId: '92345678-1234-1234-1234-123456789012', turnId: null, userId: '9007199254740993', departmentId: '2',
    mode: 'SINGLE', generationStatus: 'COMPLETED', resultCode: 'MODEL', usageStatus: 'UNKNOWN', promptTokens: null, completionTokens: null,
    plannedChargedTokens: '0', settledChargedTokens: null, settlementStatus: 'PENDING', settlementPolicy: 'SINGLE_EXISTING_V1', startedAt: '2026-10-20T00:00:00Z' } });
export const aiPageData = (kind: 'usage' | 'audits' = 'audits', more = true): AiPage => ({ ...aiMeta, items: kind === 'audits' ? [aiEvent()] : [{ key: '2026-10-20', metrics: aiMetrics(), asOf: aiMeta.asOf }],
  hasMore: more, nextCursor: more ? 'cursor_A' : null, ...(kind === 'usage' ? { groupBy: 'day' as const } : {}) });
