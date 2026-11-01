export type Id = string;
export interface Identity { userId: Id; username: string; displayName: string; departmentId: Id | null; clearanceLevel: number; enabled: boolean }
export interface Me { user: Identity; roles: Id[]; mustChangePassword: boolean }
export interface Credential { tokenName: string; tokenValue: string }
export interface LoginResult extends Credential { mustChangePassword: boolean; user: Identity }
export interface QueryPage<T> { items: T[]; hasMore?: boolean; nextCursor: string | null }
export interface Space { spaceId: Id; name: string; archived: boolean; viewable: boolean; manageable: boolean }
export interface UploadSpace { spaceId: Id; name: string }
export interface Capabilities { documentId: Id; canDownload: boolean; canUploadVersion: boolean; canRetry: boolean; canDelete?: boolean; canEditMetadata?: boolean }
export interface Progress {
  versionId: Id; versionNo: number; indexStatus: string; taskStatus?: string; waitingReason?: string;
  publicationPolicy?: 'AUTO' | 'REVIEW_REQUIRED'; publicationEvidence?: 'NONE' | 'PUBLISHED' | 'OBSERVED_CURRENT';
  reviewState?: string; stateVersion?: string | number | bigint;
  nextAttemptAt?: string; attempts?: number; maxAttempts?: number; failureSummary?: string;
  indexError?: string; parseTruncated?: boolean; parseIssueSummary?: string; deadLetterStatus?: string;
}
export interface Version extends Progress { id: Id; current: boolean; fileName: string; fileSize: number; mimeType: string; createdAt: string }
export interface DocumentSummary {
  id: Id; spaceId: Id; title: string; securityLevel: number; currentVersionId: Id | null;
  currentVersionNo: number | null; currentFileName: string | null; currentMimeType: string | null;
  currentIndexStatus: string | null; updatedAt: string; versions?: Version[];
}
export interface DocumentPage { page: number; size: number; total: number; items: DocumentSummary[] }
export interface UploadResult { documentId: Id; versionId: Id }
export interface Citation {
  docId?: Id; documentId?: Id; chunkId: Id; versionId: Id | null; versionNo?: number;
  docTitle?: string; title?: string; pageNo?: number | null; headingPath?: string | null;
  structureType?: string; sourceLineStart?: number; sourceLineEnd?: number; contextTruncated?: boolean;
}
export interface Hit extends Citation { content: string }
export interface CitationDetail extends Citation {
  status: 'AVAILABLE' | 'CHUNK_UNAVAILABLE'; chunk: ({ content: string } & Citation) | null;
  previewPath: string; downloadPath: string | null;
}
export interface Conversation { id: Id; title: string; createdAt: string; lastTurnSeq: bigint; activeTurnId: string | null }
export interface Turn {
  id: string; turnSeq: bigint; question: string; answer: string | null; status: string; visibility: string;
  message?: string; citations: Citation[]; usageStatus: string; settlementState: string;
  promptTokens: number | null; completionTokens: number | null; chargedTokens: number | null;
}
export interface TurnPage { items: Turn[]; nextCursor: bigint | null }
