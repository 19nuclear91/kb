import type { ReviewCommand, ReviewState, ReviewStatus } from './api';

export function reviewLabel(state: Pick<ReviewState, 'indexStatus' | 'reviewState' | 'publicationEvidence' | 'waitingReason' | 'policy'>): string {
  if (state.publicationEvidence === 'PUBLISHED' || state.publicationEvidence === 'OBSERVED_CURRENT') {
    return '已发布';
  }
  if (state.waitingReason === 'INDEX_REFRESH_REQUIRED') {
    return '索引待刷新';
  }
  if (state.waitingReason === 'EMBEDDING_MAINTENANCE') {
    return '维护等待';
  }
  if (state.indexStatus !== 'indexed') {
    return ({ pending: '排队中', parsing: '处理中', failed: '解析失败' } as Record<string, string>)[state.indexStatus] ?? '处理中';
  }
  return ({ IN_REVIEW: '审核中', REJECTED: '已驳回 · 待重新送审', WITHDRAWN: '已撤回 · 待重新送审', CANCELLED: '审核已取消 · 待重新送审' } as Record<string, string>)[state.reviewState]
    ?? (state.policy === 'REVIEW_REQUIRED' ? '待送审' : '已索引 · 尚未发布');
}
export const commands: [ReviewCommand, keyof ReviewStatus['capabilities'], string][] = [
  ['submit', 'canSubmit', '手动送审'], ['approve', 'canApprove', '通过并发布'], ['reject', 'canReject', '驳回'],
  ['withdraw', 'canWithdraw', '撤回本轮'], ['refresh', 'canRefresh', '刷新候选索引'],
];
