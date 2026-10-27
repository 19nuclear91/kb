import type { OperationsCoverage, OperationsReport } from '../admin/operationsReportsApi';
export const reportsFrom = '2026-10-26T00:00:00Z', reportsTo = '2026-10-27T00:00:00Z';
export function reportsCoverage(): OperationsCoverage { return { status: 'COMPLETE', coverageStartedAt: '2026-10-25T00:00:00Z', effectiveFrom: reportsFrom, effectiveTo: reportsTo }; }
export function operationsReportData(): OperationsReport {
  const review = { approvedCount: '1', rejectedCount: '1', durationSampleCount: '2', durationUnknownCount: '0', decisionElapsedMsTotal: '3', meanDecisionMs: '1.50', coverage: reportsCoverage() };
  const comments = { rootCommentCount: '0', replyCount: '1', coverage: reportsCoverage() };
  return { definitionVersion: 'OPERATIONS_V1', asOf: '2026-10-27T12:00:00Z', from: reportsFrom, to: reportsTo, dayTimeZone: 'UTC',
    inventory: { documentCount: '2', archivedDocumentCount: '1', versionCount: '3', formats: { pdf: '2', txt: '0', md: '0', doc: '0', docx: '0', xls: '0', xlsx: '0', ppt: '0', pptx: '0', UNKNOWN: '1' }, parsingStates: { pending: '0', parsing: '0', indexed: '2', failed: '1', UNKNOWN: '0' } },
    review: { ...structuredClone(review), pendingReviewCount: '1' }, comments: structuredClone(comments),
    days: [{ day: '2026-10-26', from: reportsFrom, to: reportsTo, partialDay: false, review, comments }] };
}
export function unknownComments(data = operationsReportData()): OperationsReport {
  const comments = { rootCommentCount: null, replyCount: null, coverage: { status: 'NOT_STARTED' as const, coverageStartedAt: null, effectiveFrom: null, effectiveTo: null } };
  data.comments = structuredClone(comments); data.days[0].comments = comments; return data;
}
