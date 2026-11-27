export type Kind = 'DOCUMENT' | 'SPACE' | 'TAG';
export type Mode = 'PER_PUBLICATION' | 'WEEKLY';
export type State = 'ACTIVE' | 'CANCELLED';
export interface Page<T> { items: T[]; hasMore: boolean; nextCursor: string | null; asOf: string }
export interface Candidate { kind: Kind; targetId: string; label: string; enabled: boolean; subscriptionId: string | null; generationId: string | null; version: string; state: State | null }
export interface Subscription { subscriptionId: string; generationId: string; kind: Kind; state: State; version: string; available: boolean; targetId: string | null; label: string | null; enabled: boolean | null; createdAt: string; updatedAt: string }
export interface Preference { mode: Mode; version: string; effectiveAt: string | null; timeZone: string; weeklySchedule: string }
export interface Receipt { requestId: string; operation: 'SUBSCRIBE' | 'CANCEL' | 'PREFERENCE'; subscriptionId: string | null; generationId: string | null; version: string; state: State | null; mode: Mode | null; effectiveAt: string; replayed: boolean }
export interface Lookup { requestId: string; status: 'CONFIRMED' | 'UNKNOWN'; receipt: Receipt | null }
export interface Update { updateId: string; documentId: string; versionId: string; versionNo: number; title: string; publishedAt: string; weekStart: string; mode: Mode }
export interface Detail { update: Update; hasNote: boolean; text: string | null; noteVersion: string; corrected: boolean; createdAfterVisible: boolean; baseVersionId: string | null; baseVersionNo: number | null; comparisonStatus: 'BASE_AVAILABLE' | 'NO_BASE' | 'BASE_UNAVAILABLE' }
export interface Digest { digestId: string; weekStart: string; weekEnd: string; generatedAt: string }
export interface Group { documentId: string; title: string; publicationCount: string; latestPublishedAt: string }
export interface DigestDetail { digest: Digest; groups: Page<Group> }
export const kinds: Record<Kind, string> = { DOCUMENT: '文档', SPACE: '空间', TAG: '标签' };
export const modes: Record<Mode, string> = { PER_PUBLICATION: '逐次提醒', WEEKLY: '每周汇总' };
