import type { Availability, StewardState } from './types';
export const stewardLabel: Record<StewardState, string> = { UNASSIGNED: '尚未指定责任人', NEEDS_REASSIGN: '需重新指定责任人', DUE_SOON: '临近周期复审', OVERDUE: '周期复审逾期，待复审', OK: '周期复审正常' };
export const availabilityLabel: Record<Availability, string> = { AVAILABLE: '当前可展示', SOURCE_CHANGED: '依据已变化，需保存新稿并重新审核', NOT_YET_EFFECTIVE: '尚未生效', EXPIRED: '已超过适用日期', DRAFT: '草稿尚未发布', HISTORICAL: '历史批准修订，已由新版取代', UNAVAILABLE: '尚不满足展示条件' };
