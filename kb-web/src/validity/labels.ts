import type { Applicability, Badge, Declaration, StewardshipState } from './api';

export const applicabilityLabels = { UNDECLARED: '未声明', NOT_YET_EFFECTIVE: '尚未生效', EXPIRED: '已届满', EXPIRING_SOON: '即将届满', EFFECTIVE: '有效' } as const;
export const stewardshipLabels: Record<StewardshipState, string> = { UNASSIGNED: '未指定', NEEDS_REASSIGN: '需重新指定', OVERDUE: '已逾期', DUE_SOON: '即将到期', OK: '复审正常' };
export const attentionLabels: Record<string, string> = { REVIEW_DUE_SOON: '复审即将到期', REVIEW_OVERDUE: '复审已逾期', APPLICABILITY_EXPIRING_SOON: '当前版本适用期即将届满', APPLICABILITY_EXPIRED: '当前版本适用期已届满' };
export function applicabilityLabel(value: Badge | Applicability): string {
  if (value.state === 'NOT_YET_EFFECTIVE') return `尚未生效 · ${value.effectiveFrom}`;
  if (value.state === 'EXPIRED' || value.state === 'EXPIRING_SOON') return `${applicabilityLabels[value.state]} · ${value.effectiveUntil}`;
  return value.state === 'EFFECTIVE' && value.untilKind === 'OPEN_ENDED' ? '明确长期有效' : applicabilityLabels[value.state];
}
export const declarationLabel = (value: Declaration) => `生效日：${value.effectiveFrom ?? '未声明'}；截止日：${value.untilKind === 'OPEN_ENDED' ? '明确长期有效' : value.effectiveUntil ?? '未声明'}`;
