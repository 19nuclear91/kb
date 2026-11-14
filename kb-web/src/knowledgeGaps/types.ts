export const gapStates = ['SUBMITTED', 'IN_PROGRESS', 'AWAITING_CONFIRMATION', 'RESOLVED', 'CLOSED', 'WITHDRAWN'] as const;
export const gapCategories = ['MISSING_KNOWLEDGE', 'INACCURATE', 'OUTDATED', 'OTHER'] as const;
export const gapSources = ['MANUAL', 'SEARCH', 'CONVERSATION'] as const;
export const gapEvents = ['GAP_CREATED', 'GAP_ASSIGNED', 'NOTE_ADDED', 'GAP_RETURNED', 'GAP_SOLUTION_SUBMITTED',
  'GAP_SOLUTION_REJECTED', 'GAP_RESOLVED', 'GAP_WITHDRAWN', 'GAP_CLOSED'] as const;
export type GapState = typeof gapStates[number];
export type GapCategory = typeof gapCategories[number];
export type GapSource = typeof gapSources[number];
export type GapEvent = typeof gapEvents[number];
export type GapEntryKind = 'NOTE' | 'RETURN' | 'SOLUTION' | 'REJECT_SOLUTION' | 'WITHDRAW' | 'CLOSE';
export type ExactInteger = string | number | bigint;
export interface GapParticipant { readonly userId: string; readonly label: string | null }
export interface GapDocument { readonly documentId: string; readonly versionId: string; readonly title: string }
export interface GapEntry {
  readonly entryId: string; readonly position: bigint; readonly kind: GapEntryKind; readonly author: GapParticipant;
  readonly text: string; readonly referenceState: 'NONE' | 'AVAILABLE' | 'UNAVAILABLE';
  readonly reference: GapDocument | null; readonly createdAt: string;
}
export interface GapItem {
  readonly gapId: string; readonly category: GapCategory; readonly source: GapSource; readonly state: GapState; readonly version: bigint;
  readonly reporter: GapParticipant; readonly assignee: GapParticipant | null; readonly needsReassignment: boolean;
  readonly createdAt: string; readonly updatedAt: string;
}
export interface GapCapabilities {
  readonly assign: boolean; readonly note: boolean; readonly returnToReception: boolean; readonly submitSolution: boolean;
  readonly confirm: boolean; readonly rejectSolution: boolean; readonly withdraw: boolean; readonly close: boolean;
}
export interface GapDetail extends GapItem {
  readonly description: string; readonly consentVersion: 'GAP_SHARE_V1'; readonly capabilities: GapCapabilities;
  readonly currentSolution: GapEntry | null;
}
export interface GapHistoryItem {
  readonly eventId: string; readonly kind: GapEvent; readonly resultVersion: bigint; readonly actor: GapParticipant;
  readonly assignee: GapParticipant | null; readonly occurredAt: string; readonly solutionId: string | null; readonly entry: GapEntry | null;
}
export interface GapPage<T> { readonly items: readonly T[]; readonly hasMore: boolean; readonly nextCursor: string | null }
export interface GapReceipt {
  readonly gapId: string; readonly resultVersion: bigint; readonly state: GapState; readonly eventId: string;
  readonly solutionId: string | null; readonly replayed: boolean;
}
export interface GapListQuery { view?: 'mine' | 'assigned'; status?: GapState | null; limit?: number; cursor?: string | null }
export interface GapPageQuery { limit?: number; cursor?: string | null }
export interface GapSearch extends GapPageQuery { nameQuery?: string | null }
export const gapStateLabels: Record<GapState, string> = {
  SUBMITTED: '待受理', IN_PROGRESS: '处理中', AWAITING_CONFIRMATION: '待确认', RESOLVED: '已解决', CLOSED: '已关闭', WITHDRAWN: '已撤回',
};
export const gapCategoryLabels: Record<GapCategory, string> = {
  MISSING_KNOWLEDGE: '缺少知识', INACCURATE: '内容不准确', OUTDATED: '内容过时', OTHER: '其他需求',
};
export const gapEventLabels: Record<GapEvent, string> = {
  GAP_CREATED: '提交问题', GAP_ASSIGNED: '分派或改派', NOTE_ADDED: '补充共享说明', GAP_RETURNED: '退回受理',
  GAP_SOLUTION_SUBMITTED: '提交待确认方案', GAP_SOLUTION_REJECTED: '仍未解决', GAP_RESOLVED: '本人确认解决',
  GAP_WITHDRAWN: '本人撤回', GAP_CLOSED: '带原因关闭',
};
export const gapShareNotice = '仅分享你明确确认的描述，原提问者、当前合格处理人和授权受理人员可读。已分享业务记录会保留，撤回停止处理，不等于删除。请勿粘贴不应分享的内容。';
export const gapUnknownNotice = '提交结果待核对，服务器可能已经执行。请重读事实，或显式按原内容和原请求标识重试；不会自动重发。';
export function gapTerminal(state: GapState): boolean { return state === 'RESOLVED' || state === 'CLOSED' || state === 'WITHDRAWN'; }
