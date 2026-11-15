import { expect, it } from 'vitest';
import { applicabilityLabels, applicabilityLabel, stewardshipLabels } from './labels';
import type { Badge } from './api';

it('完整映射全部适用和复审状态，长期有效不等于未声明', () => {
  expect(Object.values(applicabilityLabels)).toEqual(['未声明', '尚未生效', '已届满', '即将届满', '有效']);
  expect(Object.values(stewardshipLabels)).toEqual(['未指定', '需重新指定', '已逾期', '即将到期', '复审正常']);
  const badge: Badge = { documentId: '1', versionId: '2', state: 'EFFECTIVE', effectiveFrom: null, untilKind: 'OPEN_ENDED', effectiveUntil: null, corrected: false, current: true };
  expect(applicabilityLabel(badge)).toBe('明确长期有效');
  expect(applicabilityLabel({ ...badge, state: 'NOT_YET_EFFECTIVE', effectiveFrom: '2027-01-01' })).toContain('2027-01-01');
  expect(applicabilityLabel({ ...badge, state: 'EXPIRED', untilKind: 'DATE', effectiveUntil: '2026-12-31' })).toContain('2026-12-31');
});
