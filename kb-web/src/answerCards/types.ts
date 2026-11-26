export type UntilKind = 'UNDECLARED' | 'OPEN_ENDED' | 'DATE';
export type DateState = 'UNDECLARED' | 'NOT_YET_EFFECTIVE' | 'EXPIRED' | 'EXPIRING_SOON' | 'EFFECTIVE';
export type StewardState = 'UNASSIGNED' | 'NEEDS_REASSIGN' | 'DUE_SOON' | 'OVERDUE' | 'OK';
export type Availability = 'AVAILABLE' | 'SOURCE_CHANGED' | 'NOT_YET_EFFECTIVE' | 'EXPIRED' | 'DRAFT' | 'HISTORICAL' | 'UNAVAILABLE';
export interface Dates { effectiveFrom: string | null; untilKind: UntilKind; effectiveUntil: string | null; state: DateState }
export interface Person { userId: string; displayName: string }
export interface Stewardship { state: StewardState; responsible: Person | null; cycleMonths: number | null; firstAssignedOn: string | null; lastConfirmedOn: string | null; dueDate: string | null; version: string }
export interface SourceKey { documentId: string; versionId: string; applicabilityVersion: string }
export interface Source extends SourceKey { versionNo: string; title: string; boundDates: Dates; currentDates: Dates; currentApplicabilityVersion: string; currentVersionId: string | null; previewPath: string }
export interface SourceOption extends SourceKey { versionNo: string; title: string; dates: Dates }
export interface Revision { cardId: string; revisionId: string; revisionNo: string; branchId: string | null; question: string; aliases: string[]; answer: string; applicabilityText: string; dates: Dates; sources: Source[]; availability: Availability }
export interface Item { cardId: string; revisionId: string; revisionNo: string; question: string; answerExcerpt: string; truncated: boolean; applicabilityText: string; dates: Dates; stewardshipState: StewardState; detailPath: string }
export interface Page<T> { items: T[]; hasMore: boolean; nextCursor: string | null; asOfDate: string }
export interface Search { items: Item[]; asOfDate: string; evaluatedAt: string; appliedQuery: string; appliedSpaceId: string | null }
export interface Detail { revision: Revision; stewardship: Stewardship; asOfDate: string }
export type ReviewState = 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
export interface Review { reviewId: string; revisionId: string; roundNo: string; state: ReviewState; version: string; submittedBy: string; submittedAt: string; decidedAt: string | null; reason: string | null }
export interface Capabilities { canEdit: boolean; canSubmit: boolean; canReview: boolean; canWithdraw: boolean; canConfirm: boolean }
export interface Workspace { cardId: string; spaceId: string; cardVersion: string; published: Revision | null; draft: Revision | null; review: Review | null; stewardship: Stewardship; capabilities: Capabilities; asOfDate: string }
export interface Management { cardId: string; spaceId: string; cardVersion: string; workflowState: 'DRAFT' | 'IN_REVIEW' | 'PUBLISHED'; spaceArchived: boolean; stewardship: Stewardship; asOfDate: string }
export interface History { revision: Revision | null; review: Review | null }
export interface Content { question: string; aliases: string[]; answer: string; applicabilityText: string; dates: Omit<Dates, 'state'>; sources: SourceKey[] }
export type Operation = 'ANSWER_CARD_CREATE' | 'ANSWER_CARD_SAVE' | 'ANSWER_CARD_SUBMIT' | 'ANSWER_CARD_APPROVE' | 'ANSWER_CARD_REJECT' | 'ANSWER_CARD_WITHDRAW' | 'ANSWER_CARD_STEWARDSHIP_UPDATE' | 'ANSWER_CARD_CONFIRM';
export interface Intent { operation: Operation; cardId: string | null; reviewId: string | null; payload: Record<string, unknown> }
export interface Receipt { cardId: string; cardVersion: string; branchId: string | null; revisionId: string | null; reviewId: string | null; reviewVersion: string | null; stewardshipVersion: string | null; state: 'DRAFT' | ReviewState | 'ASSIGNED' | 'CONFIRMED'; committedAt: string; replayed: boolean }
export interface CommandResult { status: 'COMMITTED' | 'NOT_FOUND'; receipt: Receipt | null }
export interface PageInput { spaceId?: string | null; view?: string; revisionId?: string; limit?: number; cursor?: string | null }
