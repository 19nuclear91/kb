import type { Dates, Detail, Item, Management, Page, Receipt, Revision, Stewardship, Workspace } from './types';
export const dates: Dates = { effectiveFrom: null, untilKind: 'UNDECLARED', effectiveUntil: null, state: 'UNDECLARED' };
export const stewardship: Stewardship = { state: 'OVERDUE', responsible: { userId: '7', displayName: '责任人' }, cycleMonths: 3,
  firstAssignedOn: '2026-01-31', lastConfirmedOn: null, dueDate: '2026-04-30', version: '1' };
export const revision: Revision = { cardId: '9007199254740993', revisionId: '21', revisionNo: '1', branchId: null, question: '标准问题', aliases: ['常见问法'], answer: '审定原文<script>literal</script>', applicabilityText: '适用部门', dates,
  sources: [{ documentId: '31', versionId: '32', versionNo: '2', title: '固定依据', applicabilityVersion: '0', boundDates: dates, currentDates: dates,
    currentApplicabilityVersion: '0', currentVersionId: '32', previewPath: '/api/documents/31/versions/32/preview' }], availability: 'AVAILABLE' };
export const item: Item = { cardId: revision.cardId, revisionId: revision.revisionId, revisionNo: '1', question: revision.question, answerExcerpt: revision.answer,
  truncated: false, applicabilityText: revision.applicabilityText, dates, stewardshipState: 'OVERDUE', detailPath: `/answer-cards/${revision.cardId}/revisions/21` };
export const detail: Detail = { revision, stewardship: { ...stewardship, responsible: null }, asOfDate: '2026-11-26' };
export const workspace: Workspace = { cardId: revision.cardId, spaceId: '9', cardVersion: '2', published: { ...revision, branchId: '20' }, draft: null, review: null, stewardship,
  capabilities: { canEdit: true, canSubmit: false, canReview: false, canWithdraw: false, canConfirm: true }, asOfDate: '2026-11-26' };
export const management: Management = { cardId: revision.cardId, spaceId: '9', cardVersion: '2', workflowState: 'PUBLISHED', spaceArchived: false, stewardship, asOfDate: '2026-11-26' };
export const receipt: Receipt = { cardId: revision.cardId, cardVersion: '3', branchId: '22', revisionId: '23', reviewId: null, reviewVersion: null, stewardshipVersion: null, state: 'DRAFT', committedAt: '2026-11-26T12:00:00+08:00', replayed: false };
export const page = <T>(items: T[], nextCursor: string | null = null): Page<T> => ({ items, hasMore: nextCursor !== null, nextCursor, asOfDate: '2026-11-26' });
export const response = (data: unknown, headers: Record<string, string> = {}) => new Response(JSON.stringify({ code: 200, message: 'ok', data }),
  { headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Answer-Card-Version': '1', ...headers } });
